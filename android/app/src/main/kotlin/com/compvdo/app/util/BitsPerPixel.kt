package com.compvdo.app.util

/**
 * Bits-per-pixel arithmetic — the suggestion ranking signal (R10.3).
 * Same formula as the Python implementation in plan.py.
 */
object BitsPerPixel {

    /**
     * Compute bits per pixel for a video.
     * bpp = bitrate / (width * height * fps)
     */
    fun compute(bitrate: Long, width: Int, height: Int, fps: Double = 30.0): Double {
        if (width <= 0 || height <= 0 || fps <= 0) return 0.0
        return bitrate.toDouble() / (width.toLong() * height.toLong() * fps)
    }

    /**
     * Rank a video for compression priority.
     * Higher rank = more benefit from compression.
     * 0 = compress this first.
     */
    fun rank(bpp: Double, isHevc: Boolean): Int {
        if (bpp <= 0) return Int.MAX_VALUE

        // HEVC sources have less headroom; be more lenient
        val threshold = if (isHevc) 0.06 else 0.10

        return when {
            bpp > threshold * 2 -> 0   // high bpp = high priority
            bpp > threshold * 1.5 -> 1
            bpp > threshold -> 2
            bpp > threshold * 0.5 -> 3
            else -> 4                    // already efficient
        }
    }
}
