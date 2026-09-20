package com.compvdo.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans the device for video files via MediaStore.
 * Implements R10.1 (scan fields) and R1.4 (exclude _compressed outputs).
 *
 * ## Why this queries `MediaStore.Files` and not `MediaStore.Video`
 *
 * `MediaStore.Video.Media` is not a folder and not a file list — it is a *view*
 * over the single `files` table restricted to `media_type = 3`
 * (`MEDIA_TYPE_VIDEO`). MediaProvider assigns `media_type` when it indexes a
 * file, from the MIME type it infers. Two very common cases never get
 * `media_type = 3` and are therefore invisible to any query of the Video
 * collection, no matter how the selection is written:
 *
 *  - **`Download/`** — files written by a browser, a download manager or a
 *    messenger are inserted through the *Downloads* collection with
 *    `is_download = 1` and frequently with the server's generic MIME type
 *    (`application/octet-stream`) or none at all. Those rows land as
 *    `MEDIA_TYPE_NONE` / `MEDIA_TYPE_DOCUMENT`. The file plays fine; it is just
 *    not filed as video.
 *  - **WhatsApp "Documents"** — a video sent as a *document* is stored under
 *    `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Documents/` and is
 *    routinely indexed with a document MIME rather than `video/…`.
 *
 * Querying `MediaStore.Files` with a selection that accepts *either* a video
 * `media_type`, *or* a `video/…` MIME, *or* a known video file extension picks
 * all of these up in one pass, and the Video rows are unchanged.
 *
 * ## What MediaStore still cannot see
 *
 * A directory containing a `.nomedia` marker is excluded from the media index
 * entirely — no collection, no `files` row. WhatsApp has historically placed
 * `.nomedia` in some of its Media sub-directories. Those files are reachable
 * only through a SAF tree grant; see [SafVideoScanner].
 *
 * ## Permissions
 *
 * `READ_MEDIA_VIDEO` (API 33+) authorises *video* rows anywhere on external
 * storage, including `Android/media/com.whatsapp/...`. It does **not**
 * authorise rows whose `media_type` is not video and that the app does not own;
 * MediaProvider filters those out of the cursor. On API <= 32 the legacy
 * `READ_EXTERNAL_STORAGE` grant does cover them. So the document-MIME case
 * resolves itself on API <= 32 and needs a SAF grant on API 33+ — this is a
 * deliberate platform rule, not something a different query can work around,
 * and short of `MANAGE_EXTERNAL_STORAGE` (which we will not request) SAF is the
 * only sanctioned route.
 *
 * NOTE (unverified): none of this could be exercised on hardware. The
 * per-volume query is wrapped in try/catch with a reduced-projection retry
 * because the exact set of columns exposed by the `files` projection map varies
 * by API level and by OEM.
 */
object MediaScanner {

    private const val TAG = "MediaScanner"

    enum class SortOrder { SIZE, DATE, NAME, SAVINGS }

    /** Extensions we accept when MediaProvider did not file the row as video. */
    private val VIDEO_EXTENSIONS = listOf(
        "mp4", "m4v", "mkv", "webm", "mov", "3gp", "3g2", "avi",
        "wmv", "flv", "mpg", "mpeg", "ts", "m2ts", "mts",
    )

    /**
     * Folders users expect to see that a `.nomedia` marker can hide from
     * MediaStore completely. Used to decide whether to hint at a SAF grant.
     */
    val UNSCANNABLE_HINTS = listOf("WhatsApp Documents", "Download", "Documents")

    private const val MEDIA_TYPE_VIDEO = MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO

    // ---------------------------------------------------------------- public

    /**
     * Flat list of videos from shared storage, excluding our own compressed
     * outputs (R1.4). Kept for existing callers; prefer [scan].
     */
    suspend fun scanVideos(
        context: Context,
        sortOrder: SortOrder = SortOrder.SAVINGS,
    ): List<VideoInfo> = scan(context, sortOrder).videos

    /**
     * Full scan: flat list + the same items grouped into folders, so a folder
     * grid can be rendered without a second pass.
     *
     * @param includeDocumentTrees also walk any SAF tree the user has already
     *   granted us persistable access to. On by default; this is a no-op until
     *   the user has actually granted a folder, and each SAF file costs one
     *   MediaMetadataRetriever open, so pass false for a cheap refresh.
     */
    suspend fun scan(
        context: Context,
        sortOrder: SortOrder = SortOrder.SAVINGS,
        includeDocumentTrees: Boolean = true,
    ): ScanResult = withContext(Dispatchers.IO) {
        val videos = mutableListOf<VideoInfo>()

        for (volume in externalVolumeNames(context)) {
            videos += queryVolume(context, volume)
        }

        if (includeDocumentTrees) {
            // Anything a SAF grant turns up that MediaStore already knows about
            // is dropped, so a granted Download folder does not double every row.
            val seen = videos.mapTo(HashSet()) { it.displayName to it.size }
            for (extra in SafVideoScanner.scanPersistedTrees(context)) {
                if (seen.add(extra.displayName to extra.size)) videos += extra
            }
        }

        val sorted = videos.sortedWith(comparatorFor(sortOrder))
        val folders = groupIntoFolders(sorted, sortOrder)

        ScanResult(
            videos = sorted,
            folders = folders,
            needsFolderGrant = SafVideoScanner.persistedTrees(context).isEmpty() &&
                folders.none { it.displayName in UNSCANNABLE_HINTS },
        )
    }

    /** Folder tiles only. */
    suspend fun scanFolders(
        context: Context,
        sortOrder: SortOrder = SortOrder.SAVINGS,
    ): List<VideoFolder> = scan(context, sortOrder).folders

    /**
     * MediaStore's `BUCKET_ID` algorithm: the lower-cased parent directory path
     * hashed. Recomputed here for rows/volumes that do not expose the column,
     * so both paths produce the same id for the same folder.
     */
    fun bucketIdFor(parentPath: String): Long =
        parentPath.trimEnd('/').lowercase().hashCode().toLong()

    // --------------------------------------------------------------- private

    private fun externalVolumeNames(context: Context): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Covers the primary volume *and* SD cards / USB OTG, which the old
            // single VOLUME_EXTERNAL query also did, but this is explicit and
            // lets one bad volume fail without killing the whole scan.
            runCatching { MediaStore.getExternalVolumeNames(context).toList() }
                .getOrElse { listOf(MediaStore.VOLUME_EXTERNAL) }
                .ifEmpty { listOf(MediaStore.VOLUME_EXTERNAL) }
        } else {
            listOf(MediaStore.VOLUME_EXTERNAL)
        }

    private fun projection(full: Boolean): Array<String> {
        val cols = mutableListOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            @Suppress("DEPRECATION")
            MediaStore.Files.FileColumns.DATA,
        )
        if (full) {
            cols += MediaStore.Video.Media.DURATION
            cols += MediaStore.Video.Media.WIDTH
            cols += MediaStore.Video.Media.HEIGHT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // WIDTH/HEIGHT are the CODED dimensions. A phone records
                // portrait video as 1920x1080 plus a 90 degree rotation flag,
                // so without this column every portrait clip in the library
                // reports itself as landscape — which is exactly what the list
                // showed on device: 1920x1080 on every single row.
                cols += MediaStore.MediaColumns.ORIENTATION
                cols += MediaStore.Files.FileColumns.RELATIVE_PATH
                cols += MediaStore.Files.FileColumns.BUCKET_ID
                cols += MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // BITRATE only exists from API 30. Below that we fall back to
                // size*8/duration, which is what the old code did anyway.
                cols += MediaStore.Files.FileColumns.BITRATE
            }
        }
        return cols.toTypedArray()
    }

    /**
     * `media_type = video` OR a `video/…` MIME OR a known video extension.
     * All patterns are bound as arguments — MediaProvider runs the selection
     * through a strict grammar check on API 30+ and rejects inline literals in
     * some shapes. SQLite `LIKE` is case-insensitive for ASCII, so `%.mp4`
     * matches `.MP4` too.
     */
    private fun selection(): Pair<String, Array<String>> {
        val clauses = mutableListOf(
            "${MediaStore.Files.FileColumns.MEDIA_TYPE}=?",
            "${MediaStore.Files.FileColumns.MIME_TYPE} LIKE ?",
        )
        val args = mutableListOf(MEDIA_TYPE_VIDEO.toString(), "video/%")
        for (ext in VIDEO_EXTENSIONS) {
            clauses += "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            args += "%.$ext"
        }
        return "(${clauses.joinToString(" OR ")})" to args.toTypedArray()
    }

    private fun queryVolume(context: Context, volume: String): List<VideoInfo> {
        val (sel, args) = selection()
        val uri = MediaStore.Files.getContentUri(volume)

        // Full projection first; if a column is missing on this API level/OEM
        // the provider throws IllegalArgumentException, so retry lean rather
        // than lose the whole volume.
        return runCatching { readCursor(context, uri, volume, projection(true), sel, args) }
            .recoverCatching {
                Log.w(TAG, "full projection failed on '$volume', retrying lean", it)
                readCursor(context, uri, volume, projection(false), sel, args)
            }
            .getOrElse {
                Log.w(TAG, "volume '$volume' not scannable", it)
                emptyList()
            }
    }

    private fun readCursor(
        context: Context,
        collection: Uri,
        volume: String,
        projection: Array<String>,
        sel: String,
        args: Array<String>,
    ): List<VideoInfo> {
        val out = mutableListOf<VideoInfo>()
        context.contentResolver.query(collection, projection, sel, args, null)?.use { c ->
            // getColumnIndex (not …OrThrow): the lean projection legitimately
            // omits several of these.
            val idCol = c.getColumnIndex(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
            val mimeCol = c.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
            val dateCol = c.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val takenCol = c.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
            val typeCol = c.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
            @Suppress("DEPRECATION")
            val dataCol = c.getColumnIndex(MediaStore.Files.FileColumns.DATA)
            val durCol = c.getColumnIndex(MediaStore.Video.Media.DURATION)
            val wCol = c.getColumnIndex(MediaStore.Video.Media.WIDTH)
            val hCol = c.getColumnIndex(MediaStore.Video.Media.HEIGHT)
            val orientationCol = c.getColumnIndex(MediaStore.MediaColumns.ORIENTATION)
            val relCol = c.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val bucketIdCol = c.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_ID)
            val bucketNameCol = c.getColumnIndex(MediaStore.Files.FileColumns.BUCKET_DISPLAY_NAME)
            val bitrateCol = c.getColumnIndex("bitrate")

            if (idCol < 0 || nameCol < 0) return emptyList()

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val name = c.getString(nameCol) ?: continue
                val size = if (sizeCol >= 0) c.getLong(sizeCol) else 0L
                if (size <= 0) continue

                val mediaType = if (typeCol >= 0) c.getInt(typeCol) else MEDIA_TYPE_VIDEO
                val mime = c.getStringOrNull(mimeCol)
                    ?: if (mediaType == MEDIA_TYPE_VIDEO) "video/mp4" else mimeFromName(name)
                // A document-MIME row that is not actually a video would be a
                // guaranteed Transformer failure later, so require one of the
                // two positive signals.
                if (mediaType != MEDIA_TYPE_VIDEO &&
                    !mime.startsWith("video/") &&
                    !hasVideoExtension(name)
                ) continue

                val duration = if (durCol >= 0) c.getLong(durCol) else 0L
                val codedWidth = if (wCol >= 0) c.getInt(wCol) else 0
                val codedHeight = if (hCol >= 0) c.getInt(hCol) else 0
                // Report DISPLAY dimensions, the same way the desktop build
                // does (R5.1, R8.2): a quarter turn swaps the two.
                val rotation = if (orientationCol >= 0) c.getInt(orientationCol) else 0
                val quarterTurned = rotation == 90 || rotation == 270
                val width = if (quarterTurned) codedHeight else codedWidth
                val height = if (quarterTurned) codedWidth else codedHeight
                val dateMod = if (dateCol >= 0) c.getLong(dateCol) else 0L
                var bitrate = if (bitrateCol >= 0) c.getLong(bitrateCol) else 0L
                if (bitrate <= 0 && duration > 0) bitrate = (size * 8 * 1000) / duration

                val data = c.getStringOrNull(dataCol)
                val relPath = c.getStringOrNull(relCol) ?: relativePathFromData(data)
                val parent = data?.substringBeforeLast('/', "") ?: relPath.trimEnd('/')
                val bucketId = if (bucketIdCol >= 0 && !c.isNull(bucketIdCol)) {
                    c.getLong(bucketIdCol)
                } else {
                    bucketIdFor(parent)
                }
                val bucketName = c.getStringOrNull(bucketNameCol)
                    ?: relPath.trimEnd('/').substringAfterLast('/').ifBlank { "Internal storage" }

                // Video rows keep their Video-collection URI (what the rest of
                // the app already handles); document/download rows can only be
                // addressed through the Files collection.
                val rowUri = if (mediaType == MEDIA_TYPE_VIDEO) {
                    ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(volume), id)
                } else {
                    ContentUris.withAppendedId(collection, id)
                }

                val info = VideoInfo(
                    uri = rowUri,
                    displayName = name,
                    size = size,
                    duration = duration,
                    width = width,
                    height = height,
                    mimeType = mime,
                    dateModified = dateMod,
                    // Fall back to the modified time (seconds -> ms) so a row
                    // without DATE_TAKEN still gets a sensible ordering value.
                    dateTaken = if (takenCol >= 0 && !c.isNull(takenCol)) c.getLong(takenCol)
                                else dateMod * 1000L,
                    bitrate = bitrate,
                    relativePath = relPath,
                    bucketId = bucketId,
                    bucketDisplayName = bucketName,
                    indexedAsDocument = mediaType != MEDIA_TYPE_VIDEO,
                )

                // R1.4: never list our own outputs.
                if (!info.isCompressedOutput) out += info
            }
        }
        return out
    }

    private fun android.database.Cursor.getStringOrNull(col: Int): String? =
        if (col >= 0 && !isNull(col)) getString(col) else null

    private fun hasVideoExtension(name: String): Boolean =
        VIDEO_EXTENSIONS.contains(name.substringAfterLast('.', "").lowercase())

    private fun mimeFromName(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "avi" -> "video/x-msvideo"
            "3gp", "3g2" -> "video/3gpp"
            else -> "video/mp4"
        }

    /** "/storage/emulated/0/Download/x.mp4" -> "Download/" */
    private fun relativePathFromData(data: String?): String {
        if (data.isNullOrBlank()) return ""
        val dir = data.substringBeforeLast('/', "")
        val marker = "/0/"
        val idx = dir.indexOf(marker)
        val rel = if (idx >= 0) dir.substring(idx + marker.length) else dir.trimStart('/')
        return if (rel.isBlank()) "" else "$rel/"
    }

    private fun comparatorFor(order: SortOrder): Comparator<VideoInfo> = when (order) {
        SortOrder.SIZE -> compareByDescending { it.size }
        SortOrder.DATE -> compareByDescending { it.dateModified }
        SortOrder.NAME -> compareBy { it.displayName.lowercase() }
        SortOrder.SAVINGS -> compareByDescending { it.estimatedSaving }
    }

    private fun groupIntoFolders(
        videos: List<VideoInfo>,
        sortOrder: SortOrder,
    ): List<VideoFolder> {
        val folders = videos
            .groupBy { it.bucketId }
            .map { (bucketId, items) ->
                VideoFolder(
                    bucketId = bucketId,
                    displayName = items.firstOrNull { it.bucketDisplayName.isNotBlank() }
                        ?.bucketDisplayName
                        ?: "Videos",
                    relativePath = items.first().relativePath,
                    videoCount = items.size,
                    totalSize = items.sumOf { it.size },
                    // Newest item — the most recognisable thumbnail for a tile.
                    representative = items.maxByOrNull { it.dateModified } ?: items.first(),
                    estimatedSaving = items.sumOf { it.estimatedSaving },
                    fromDocumentTree = items.all { it.fromDocumentTree },
                )
            }
        return when (sortOrder) {
            SortOrder.NAME -> folders.sortedBy { it.displayName.lowercase() }
            SortOrder.DATE -> folders.sortedByDescending { it.representative.dateModified }
            SortOrder.SIZE -> folders.sortedByDescending { it.totalSize }
            SortOrder.SAVINGS -> folders.sortedByDescending { it.estimatedSaving }
        }
    }
}
