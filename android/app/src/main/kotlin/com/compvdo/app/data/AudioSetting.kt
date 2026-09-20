package com.compvdo.app.data

/**
 * Opt-in audio re-encode ladder — requirements.md R6.3 - R6.6.
 *
 * This is a re-implementation of the rules in requirements.md, not a port of
 * `compvdo/plan.py`; it deliberately mirrors that module's ladder so the two
 * platforms offer the user the same four choices and the same floor.
 *
 * Desktop equivalent: `plan.AUDIO_CHOICES` / `plan.AUDIO_DEFAULT`.
 */

/**
 * The one and only audio bitrate floor (R6.5).
 *
 * Below this AAC starts to audibly smear cymbals and sibilance, and the few
 * megabytes it saves are not worth it on a video file. Nothing else in the
 * Android sources may hard-code 128 for audio — clamping happens in exactly
 * one place, [com.compvdo.app.compression.QualityLadder.resolveAudio], and it
 * is always reported.
 *
 * Desktop equivalent: `plan.AUDIO_MIN_KBPS`.
 */
const val AUDIO_MIN_KBPS: Int = 128

/**
 * A short fixed ladder, not a free-form number (R6.3): a control people can
 * only put a sane value into needs no validation UI and no support questions.
 *
 * [KEEP] is the default and is a genuine stream copy (R6.1) — see
 * `TransformerEngine` for how that pass-through is guaranteed.
 */
enum class AudioSetting(
    val label: String,
    val description: String,
    /** Requested bitrate in kbps, or null for "don't touch the audio at all". */
    val requestedKbps: Int?,
) {
    KEEP(
        label = "Keep original",
        description = "Copy the audio exactly as it is — no re-encode",
        requestedKbps = null,
    ),
    KBPS_192(
        label = "AAC 192 kbps",
        description = "Transparent for music and voice",
        requestedKbps = 192,
    ),
    KBPS_160(
        label = "AAC 160 kbps",
        description = "Slightly smaller, still very good",
        requestedKbps = 160,
    ),
    KBPS_128(
        label = "AAC $AUDIO_MIN_KBPS kbps",
        description = "The lowest this app will go",
        requestedKbps = AUDIO_MIN_KBPS,
    );

    companion object {
        /** R6.1/R6.3: doing nothing to the audio is the default. */
        val DEFAULT = KEEP
    }
}
