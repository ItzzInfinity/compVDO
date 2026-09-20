package com.compvdo.app.data

import android.net.Uri

/**
 * Represents a video discovered on the device.
 * Parallel to the Python MediaInfo + ScanEntry, adapted for Android/MediaStore.
 */
data class VideoInfo(
    val uri: Uri,
    val displayName: String,
    val size: Long,            // bytes
    val duration: Long,        // milliseconds
    val width: Int,
    val height: Int,
    val mimeType: String,
    val dateModified: Long,    // epoch seconds
    /**
     * MediaStore DATE_TAKEN in milliseconds, 0 when unknown.
     *
     * This is what a gallery sorts an album by. Carried so a compressed copy
     * can be given the same one and land beside its original instead of
     * jumping to the top of the album.
     */
    val dateTaken: Long = 0L,
    val bitrate: Long,         // bits/s, estimated from size*8/duration if not available
    val relativePath: String,  // e.g. "DCIM/Camera"

    // --- folder grouping (added for the folder-tile UI; defaulted so existing
    // --- construction sites keep compiling) ---
    /** MediaStore BUCKET_ID, or the same hash recomputed from the parent path. */
    val bucketId: Long = 0L,
    /** MediaStore BUCKET_DISPLAY_NAME, e.g. "Camera", "Download". */
    val bucketDisplayName: String = "",
    /**
     * True when this row came from `MediaStore.Files` with a non-video
     * `MEDIA_TYPE` — i.e. a video that was filed as a document/download
     * (WhatsApp Documents, browser downloads). Kept because such items are the
     * ones most likely to fail to open, and the UI may want to mark them.
     */
    val indexedAsDocument: Boolean = false,
    /** True when discovered through a SAF tree grant rather than MediaStore. */
    val fromDocumentTree: Boolean = false,
) {
    /** Bits per pixel — the ranking signal (R10.3) */
    val bpp: Double
        get() {
            if (width <= 0 || height <= 0 || duration <= 0) return 0.0
            val fps = 30.0 // assume 30 fps when unknown; MediaStore doesn't expose fps
            return bitrate.toDouble() / (width * height * fps)
        }

    /** Estimated bytes saved — rough, always labelled "estimated" (R10.4) */
    val estimatedSaving: Long
        get() {
            if (bpp <= 0) return 0
            val targetBpp = 0.04 // approximate HEVC CRF 24 target
            val ratio = (targetBpp / bpp).coerceIn(0.0, 1.0)
            return ((1.0 - ratio) * size).toLong().coerceAtLeast(0)
        }

    /** Human-readable size */
    val formattedSize: String
        get() = formatFileSize(size)

    /** Is this already a compressed output from us? (R1.4) */
    val isCompressedOutput: Boolean
        get() {
            val stem = displayName.substringBeforeLast('.')
            return stem.endsWith("_compressed")
        }

    companion object {
        fun formatFileSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
        }
    }
}
