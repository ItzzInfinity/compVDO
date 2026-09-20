package com.compvdo.app.data

/**
 * Compression quality modes — maps to requirements.md R3.
 *
 * NOTE: ARCHIVE mode does not exist on Android. There is no FFV1 on MediaCodec,
 * and no way to do true mathematical lossless. The UI must not offer it.
 */
enum class CompressionMode(
    val label: String,
    val bitrateRatio: Double,  // fraction of source bitrate to target
    val description: String,
) {
    LOW(
        label = "Low",
        bitrateRatio = 0.25,
        description = "75–85% smaller, softer on detail"
    ),
    MEDIUM(
        label = "Medium",
        bitrateRatio = 0.50,
        description = "55–70% smaller, no visible loss in motion"
    ),
    HIGH(
        label = "High",
        bitrateRatio = 0.75,
        description = "40–55% smaller, visually lossless"
    );

    companion object {
        val DEFAULT = MEDIUM
    }
}
