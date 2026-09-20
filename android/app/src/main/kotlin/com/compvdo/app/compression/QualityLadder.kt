package com.compvdo.app.compression

import com.compvdo.app.data.CompressionMode
import com.compvdo.app.data.VideoInfo

/**
 * Maps compression modes to bitrate targets for the hardware encoder.
 *
 * Media3 Transformer has no CRF — it uses VideoEncoderSettings with a bitrate target
 * on the hardware MediaCodec. The ladder maps modes to a fraction of the source bitrate.
 *
 * Implements R3 (quality ladder) adapted for Android.
 */
object QualityLadder {

    /** Minimum bitrate floor — don't go below 500 kbps regardless of ratio */
    private const val MIN_BITRATE = 500_000

    /** Maximum bitrate — cap at 20 Mbps */
    private const val MAX_BITRATE = 20_000_000

    /**
     * Compute the target bitrate for a given mode and source video.
     * Returns bits/sec.
     */
    fun targetBitrate(mode: CompressionMode, source: VideoInfo): Int {
        val sourceBitrate = source.bitrate.coerceAtLeast(1)
        val raw = (sourceBitrate * mode.bitrateRatio).toLong()
        return raw.coerceIn(MIN_BITRATE.toLong(), MAX_BITRATE.toLong()).toInt()
    }

    /**
     * Estimate the output size in bytes for display purposes.
     * This is always labelled "estimated" per R10.4.
     */
    fun estimatedOutputSize(mode: CompressionMode, source: VideoInfo): Long {
        if (source.duration <= 0 || source.bitrate <= 0) return source.size
        val targetBps = targetBitrate(mode, source)
        val durationSec = source.duration / 1000.0
        return ((targetBps * durationSec) / 8).toLong()
    }
}
