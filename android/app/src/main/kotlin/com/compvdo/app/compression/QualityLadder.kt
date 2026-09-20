package com.compvdo.app.compression

import com.compvdo.app.data.AUDIO_MIN_KBPS
import com.compvdo.app.data.AudioSetting
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

    // --- audio (R6) --------------------------------------------------------

    /**
     * The outcome of asking for an audio bitrate.
     *
     * @param kbps null means "stream copy, touch nothing" (R6.1); a number is
     *   the bitrate the encoder should actually be asked for, already clamped.
     * @param note a user-facing message that must be surfaced, or null. The
     *   clamp is never silent (R6.5).
     */
    data class AudioPlan(val kbps: Int?, val note: String?)

    /**
     * Resolve an [AudioSetting] to a bitrate, clamping anything below the
     * floor and reporting the clamp (R6.5).
     *
     * Pure, and deliberately separate from the encode so the UI can show the
     * clamp message before anything is encoded. Mirrors `plan.resolve_audio()`
     * on the desktop side.
     *
     * The ladder in [AudioSetting] cannot currently express a sub-floor value,
     * so the clamp branch is unreachable from the UI today. It is kept anyway:
     * the floor is a rule about what this app will encode, not about what the
     * dropdown happens to contain, and a persisted preference from a future
     * (or hand-edited) build must still hit it.
     */
    fun resolveAudio(setting: AudioSetting): AudioPlan {
        val asked = setting.requestedKbps ?: return AudioPlan(null, null)
        if (asked < AUDIO_MIN_KBPS) {
            return AudioPlan(
                kbps = AUDIO_MIN_KBPS,
                note = "Requested audio bitrate $asked kbps is below the " +
                    "$AUDIO_MIN_KBPS kbps floor; using $AUDIO_MIN_KBPS kbps instead (R6.5)",
            )
        }
        return AudioPlan(asked, null)
    }
}
