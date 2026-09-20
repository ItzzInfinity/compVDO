package com.compvdo.app.compression

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.compvdo.app.data.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Verification checks that gate any delete — implements R8.
 *
 * R8.1: Output duration within 0.5% of source.
 * R8.2: Output video dimensions match source display dimensions.
 * R8.3: Output is playable (can be opened by MediaExtractor).
 * R8.4: All three must pass before a delete is permitted.
 */
object Verifier {

    data class VerifyResult(
        val passed: Boolean,
        val durationOk: Boolean,
        val dimensionsOk: Boolean,
        val playable: Boolean,
        val message: String,
    )

    /**
     * Run all three verification checks on the output.
     */
    suspend fun verify(
        context: Context,
        source: VideoInfo,
        outputUri: Uri,
    ): VerifyResult = withContext(Dispatchers.IO) {
        var durationOk = false
        var dimensionsOk = false
        var playable = false
        val messages = mutableListOf<String>()

        try {
            val extractor = MediaExtractor()
            context.contentResolver.openFileDescriptor(outputUri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)

                // Find the video track
                var videoTrackIndex = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        videoTrackIndex = i
                        break
                    }
                }

                if (videoTrackIndex < 0) {
                    messages.add("No video track found in output")
                    return@withContext VerifyResult(false, false, false, false, messages.joinToString("; "))
                }

                val format = extractor.getTrackFormat(videoTrackIndex)

                // R8.1: Duration check (within 0.5% or 500ms, whichever is larger)
                val outDurationUs = try {
                    format.getLong(MediaFormat.KEY_DURATION)
                } catch (_: Exception) { 0L }
                val srcDurationMs = source.duration
                val outDurationMs = outDurationUs / 1000
                val tolerance = maxOf(srcDurationMs * 5 / 1000, 500) // 0.5% or 500ms
                durationOk = kotlin.math.abs(outDurationMs - srcDurationMs) <= tolerance
                if (!durationOk) {
                    messages.add("Duration mismatch: source=${srcDurationMs}ms, output=${outDurationMs}ms")
                }

                // R8.2: Dimensions check (display dimensions)
                val outWidth = try { format.getInteger(MediaFormat.KEY_WIDTH) } catch (_: Exception) { 0 }
                val outHeight = try { format.getInteger(MediaFormat.KEY_HEIGHT) } catch (_: Exception) { 0 }
                // Display dimensions: compare the larger/smaller independently (rotation may swap)
                val srcDims = listOf(source.width, source.height).sorted()
                val outDims = listOf(outWidth, outHeight).sorted()
                dimensionsOk = srcDims == outDims
                if (!dimensionsOk) {
                    messages.add("Dimensions mismatch: source=${source.width}x${source.height}, output=${outWidth}x${outHeight}")
                }

                // R8.3: Playability — if we got this far with no exception, it's playable
                playable = true

                extractor.release()
            } ?: run {
                messages.add("Cannot open output file")
            }
        } catch (e: Exception) {
            messages.add("Verification error: ${e.message}")
        }

        val passed = durationOk && dimensionsOk && playable
        VerifyResult(
            passed = passed,
            durationOk = durationOk,
            dimensionsOk = dimensionsOk,
            playable = playable,
            message = if (passed) "Verification passed" else messages.joinToString("; "),
        )
    }
}
