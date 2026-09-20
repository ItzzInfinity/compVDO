package com.compvdo.app.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Finds videos in folders MediaStore cannot report, through the Storage Access
 * Framework.
 *
 * This is the escape hatch for the two cases [MediaScanner] documents as
 * unreachable:
 *
 *  1. A directory containing a `.nomedia` marker is skipped by the media
 *     scanner completely — there is no `files` row at all. WhatsApp has used
 *     `.nomedia` inside its Media sub-directories, which is why "WhatsApp
 *     Documents" can be entirely absent from a MediaStore scan.
 *  2. On API 33+ a row whose `media_type` is not video and that we do not own
 *     is filtered out of the cursor: `READ_MEDIA_VIDEO` does not authorise it.
 *     This is the `Download/` case for files the download manager filed with a
 *     generic MIME type.
 *
 * SAF is the only route to those that does not require
 * `MANAGE_EXTERNAL_STORAGE`, which we deliberately do not request.
 *
 * The UI owns the picker. This class only (a) suggests where to point it,
 * (b) lists what the user has already granted, and (c) walks those trees.
 *
 * NOTE (unverified — no hardware): the DocumentsProvider authority strings and
 * the `Android/media` grant behaviour below are from the platform contract, not
 * from a device run. See the report for what must be checked.
 */
object SafVideoScanner {

    private const val TAG = "SafVideoScanner"

    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    /**
     * Directories worth offering in a folder picker, as primary-volume
     * relative paths.
     *
     * `Android/media/...` is grantable; `Android/data` and `Android/obb` are
     * blocked from tree grants on API 30+, which is why WhatsApp's move to
     * `Android/media` matters — it keeps its folders pickable.
     */
    val SUGGESTED_PATHS = listOf(
        "Download",
        "Documents",
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video",
        "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Video",
        "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Documents",
    )

    /**
     * A URI to hand to `ACTION_OPEN_DOCUMENT_TREE` as `EXTRA_INITIAL_URI`, so
     * the picker opens on the folder we want instead of at the root.
     */
    fun initialTreeUri(relativePath: String): Uri = DocumentsContract.buildDocumentUri(
        EXTERNAL_STORAGE_AUTHORITY,
        "primary:$relativePath",
    )

    /** Tree grants the user has already given us and that survived a reboot. */
    fun persistedTrees(context: Context): List<Uri> =
        context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .map { it.uri }

    /** Walk every persisted tree grant. Excludes our own outputs (R1.4). */
    suspend fun scanPersistedTrees(context: Context): List<VideoInfo> =
        withContext(Dispatchers.IO) {
            persistedTrees(context).flatMap { scanTree(context, it) }
        }

    /**
     * Walk one granted tree, recursively.
     *
     * @param maxDepth guard against a grant on the storage root turning the
     *   scan into a full-disk walk.
     */
    suspend fun scanTree(
        context: Context,
        treeUri: Uri,
        maxDepth: Int = 4,
    ): List<VideoInfo> = withContext(Dispatchers.IO) {
        val out = mutableListOf<VideoInfo>()
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrElse {
                Log.w(TAG, "not a tree uri: $treeUri", it)
                return@withContext emptyList()
            }
        walk(context, treeUri, rootId, folderName(rootId), maxDepth, out)
        out
    }

    private fun walk(
        context: Context,
        treeUri: Uri,
        documentId: String,
        bucketName: String,
        depthLeft: Int,
        out: MutableList<VideoInfo>,
    ) {
        if (depthLeft < 0) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )

        val rows = mutableListOf<Array<Any?>>()
        try {
            context.contentResolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    rows += arrayOf(
                        c.getString(0),
                        c.getString(1),
                        c.getString(2),
                        if (c.isNull(3)) 0L else c.getLong(3),
                        if (c.isNull(4)) 0L else c.getLong(4),
                    )
                }
            }
        } catch (e: Exception) {
            // A revoked or stale grant throws here; one bad tree must not kill
            // the scan.
            Log.w(TAG, "cannot list $documentId", e)
            return
        }

        for (row in rows) {
            val docId = row[0] as? String ?: continue
            val name = row[1] as? String ?: continue
            val mime = row[2] as? String ?: ""
            val size = row[3] as? Long ?: 0L
            // COLUMN_LAST_MODIFIED is milliseconds; VideoInfo.dateModified is
            // epoch *seconds*, matching MediaStore's DATE_MODIFIED.
            val modifiedSec = ((row[4] as? Long) ?: 0L) / 1000

            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                walk(context, treeUri, docId, name, depthLeft - 1, out)
                continue
            }
            if (size <= 0) continue
            if (!mime.startsWith("video/") && !isVideoName(name)) continue

            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val meta = readMetadata(context, uri)

            val info = VideoInfo(
                uri = uri,
                displayName = name,
                size = size,
                duration = meta.durationMs,
                width = meta.width,
                height = meta.height,
                mimeType = if (mime.startsWith("video/")) mime else "video/mp4",
                dateModified = modifiedSec,
                bitrate = when {
                    meta.bitrate > 0 -> meta.bitrate
                    meta.durationMs > 0 -> (size * 8 * 1000) / meta.durationMs
                    else -> 0L
                },
                // SAF gives no MediaStore RELATIVE_PATH. Leaving it blank makes
                // OutputNaming fall back to Movies/compVDO, which is correct:
                // we cannot insert an output into an arbitrary granted tree
                // through MediaStore anyway.
                relativePath = "",
                bucketId = MediaScanner.bucketIdFor("saf:$treeUri/$bucketName"),
                bucketDisplayName = bucketName,
                indexedAsDocument = !mime.startsWith("video/"),
                fromDocumentTree = true,
            )
            if (!info.isCompressedOutput) out += info
        }
    }

    private fun folderName(documentId: String): String =
        documentId.trimEnd('/').substringAfterLast('/').substringAfterLast(':')
            .ifBlank { "Folder" }

    private fun isVideoName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf(
            "mp4", "m4v", "mkv", "webm", "mov", "3gp", "3g2", "avi",
            "wmv", "flv", "mpg", "mpeg", "ts", "m2ts", "mts",
        )

    private data class Meta(
        val durationMs: Long = 0,
        val width: Int = 0,
        val height: Int = 0,
        val bitrate: Long = 0,
    )

    /**
     * SAF rows carry no duration/resolution/bitrate, and R10.1 requires them,
     * so we read the container header. One retriever per file is not free —
     * this is why SAF trees are only walked when asked for.
     */
    private fun readMetadata(context: Context, uri: Uri): Meta {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            Meta(
                durationMs = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L,
                width = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    ?.toIntOrNull() ?: 0,
                height = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    ?.toIntOrNull() ?: 0,
                bitrate = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                    ?.toLongOrNull() ?: 0L,
            )
        } catch (e: Exception) {
            // Not a decodable video after all — R10.4: do not invent numbers,
            // leave them at 0 so the estimate reads as 0 rather than a guess.
            Log.w(TAG, "no metadata for $uri", e)
            Meta()
        } finally {
            runCatching { retriever.release() }
        }
    }
}
