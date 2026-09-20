package com.compvdo.app.compression

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.compvdo.app.data.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles output naming via MediaStore — implements R1.1, R1.2, R1.3.
 *
 * Every ContentResolver call here is a suspend function on Dispatchers.IO.
 * They used to be plain functions invoked from the batch path, which runs on
 * Dispatchers.Main.immediate — a MediaStore query and insert per file, on the
 * UI thread, once per video in the batch.
 *
 * R1.1: Output is <stem>_compressed.<ext> beside the original.
 * R1.2: If that name exists, append (2), (3)… never overwrite.
 * R1.3: Output container is always .mp4 on Android (HEVC in MP4).
 */
object OutputNaming {

    /**
     * Compute the output display name from the source video.
     * Returns e.g. "VID_20230715_162642_compressed.mp4"
     */
    fun outputDisplayName(source: VideoInfo): String {
        val stem = source.displayName.substringBeforeLast('.')
        return "${stem}_compressed.mp4"
    }

    /**
     * Insert a placeholder entry in MediaStore for the output file.
     * Handles R1.2 collision avoidance by checking existing entries.
     */
    suspend fun createOutputUri(context: Context, source: VideoInfo): Uri? = withContext(Dispatchers.IO) {
        var name = outputDisplayName(source)
        val relativePath = source.relativePath.ifBlank {
            "${Environment.DIRECTORY_MOVIES}/compVDO"
        }

        // R1.2: check for collisions and append (N) if needed
        var attempt = 1
        while (nameExists(context, name, relativePath)) {
            val stem = source.displayName.substringBeforeLast('.')
            attempt++
            name = "${stem}_compressed ($attempt).mp4"
        }

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        context.contentResolver.insert(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values,
        )
    }

    /**
     * Mark the output as complete (clear IS_PENDING).
     */
    suspend fun finalizeOutput(context: Context, outputUri: Uri) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(outputUri, values, null, null)
        }
        Unit
    }

    /**
     * Remove a failed/cancelled output entry from MediaStore.
     */
    suspend fun deleteOutput(context: Context, outputUri: Uri) = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.delete(outputUri, null, null)
        } catch (_: Exception) {
            // Best effort cleanup
        }
        Unit
    }

    private fun nameExists(context: Context, name: String, relativePath: String): Boolean {
        val selection = "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.RELATIVE_PATH} = ?"
        val args = arrayOf(name, relativePath)
        context.contentResolver.query(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(MediaStore.Video.Media._ID),
            selection,
            args,
            null,
        )?.use { cursor ->
            return cursor.count > 0
        }
        return false
    }
}
