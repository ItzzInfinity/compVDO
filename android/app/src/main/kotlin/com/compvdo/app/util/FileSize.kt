package com.compvdo.app.util

/**
 * File size formatting helpers.
 */
object FileSize {

    fun format(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val min = totalSec / 60
        val sec = totalSec % 60
        return if (min > 0) "${min}m ${sec}s" else "${sec}s"
    }

    fun formatRatio(ratio: Double): String = "%.0f%%".format(ratio * 100)

    /**
     * A short resolution label: "1080p", "4K", or "WxH" when it fits no rung.
     *
     * "1920×1080" is eleven characters that cannot be abbreviated by an
     * ellipsis without becoming nonsense, and in a row that also carries a size
     * and a duration it was the thing that wrapped — on device it broke across
     * three lines as "192 / 0×10 / 80". The short rung is also what people
     * actually recognise.
     *
     * Keyed on the SHORTER side, so a portrait 1080x1920 is "1080p" too.
     */
    fun formatResolution(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return ""
        return when (minOf(width, height)) {
            in 2000..Int.MAX_VALUE -> "4K"
            in 1400..1999 -> "1440p"
            in 1000..1399 -> "1080p"
            in 700..999 -> "720p"
            in 460..699 -> "480p"
            else -> "${width}x${height}"
        }
    }
}
