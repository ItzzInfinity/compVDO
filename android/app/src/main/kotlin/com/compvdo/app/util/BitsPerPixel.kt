package com.compvdo.app.util

/**
 * Bits-per-pixel arithmetic — the suggestion ranking signal (R10.3).
 *
 * [compute] matches `plan.py`'s `MediaInfo.bpp` exactly.
 *
 * [rank] does **not**. The desktop sorts by estimated bytes saved and hands
 * back a strict ordering; this buckets into 0-4 against a threshold. Both put
 * the same files near the top, but they are different algorithms and the
 * numbers are not comparable. Said plainly here because the comment used to
 * claim the whole file was "the same formula", which sent a reader looking for
 * a bug that did not exist — and hid the divergence that does.
 *
 * See roadmap 3.13.
 */
object BitsPerPixel {

    /**
     * Compute bits per pixel for a video: `bitrate / (width * height * fps)`.
     *
     * **[fps] defaults to 30 only because MediaStore does not reliably expose a
     * frame rate.** That is a real inaccuracy, not a neutral default: on 60 fps
     * footage it halves the true pixel rate and so doubles the apparent bpp,
     * making the file look far more compressible than it is. Three of the four
     * clips in the project's own trial were 60 fps. The desktop refuses to
     * guess and returns 0 instead (R10.4); matching that here needs
     * `CAPTURE_FRAMERATE`, which only exists from API 30.
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
