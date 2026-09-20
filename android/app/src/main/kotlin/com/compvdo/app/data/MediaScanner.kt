package com.compvdo.app.data

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans the device for video files via MediaStore.
 * Implements R10.1 (scan fields) and R1.4 (exclude _compressed outputs).
 */
object MediaScanner {

    enum class SortOrder { SIZE, DATE, NAME, SAVINGS }

    /**
     * Query all videos from shared storage, excluding our own compressed outputs.
     */
    suspend fun scanVideos(
        context: Context,
        sortOrder: SortOrder = SortOrder.SAVINGS,
    ): List<VideoInfo> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<VideoInfo>()

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.BITRATE,
            MediaStore.Video.Media.RELATIVE_PATH,
        )

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        context.contentResolver.query(
            collection,
            projection,
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val bitrateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BITRATE)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val name = cursor.getString(nameCol) ?: continue
                val size = cursor.getLong(sizeCol)
                val duration = cursor.getLong(durationCol)
                val width = cursor.getInt(widthCol)
                val height = cursor.getInt(heightCol)
                val mime = cursor.getString(mimeCol) ?: "video/mp4"
                val dateMod = cursor.getLong(dateCol)
                var bitrate = cursor.getLong(bitrateCol)
                val relPath = cursor.getString(pathCol) ?: ""

                // Fallback bitrate estimate from size and duration
                if (bitrate <= 0 && duration > 0) {
                    bitrate = (size * 8 * 1000) / duration
                }

                val uri = ContentUris.withAppendedId(collection, id)

                val info = VideoInfo(
                    uri = uri,
                    displayName = name,
                    size = size,
                    duration = duration,
                    width = width,
                    height = height,
                    mimeType = mime,
                    dateModified = dateMod,
                    bitrate = bitrate,
                    relativePath = relPath,
                )

                // R1.4: exclude files that end with _compressed
                if (!info.isCompressedOutput && size > 0) {
                    videos.add(info)
                }
            }
        }

        // Sort according to the requested order
        when (sortOrder) {
            SortOrder.SIZE -> videos.sortByDescending { it.size }
            SortOrder.DATE -> videos.sortByDescending { it.dateModified }
            SortOrder.NAME -> videos.sortBy { it.displayName.lowercase() }
            SortOrder.SAVINGS -> videos.sortByDescending { it.estimatedSaving }
        }

        videos
    }
}
