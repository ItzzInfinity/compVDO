package com.compvdo.app.compression

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import com.compvdo.app.data.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Verification checks that gate any delete — implements R8.
 *
 * Owns:   the decision on whether an output is sound enough to permit removing
 *         the original.
 * Reads:  the output file, through MediaExtractor.
 * Writes: nothing.
 * Runs:   nothing.
 *
 * R8.1: Output duration within 0.5% of source.
 * R8.2: Output video dimensions match source display dimensions.
 * R8.3: Output actually decodes end to end.
 * R8.4: All three must pass before a delete is permitted.
 *
 * R8.3 previously read the track header and then set `playable = true` with the
 * comment "if we got this far with no exception, it's playable". A truncated
 * file has a perfectly valid header, so the check could not fail — and its
 * result is what authorises an irreversible delete. It now walks every sample
 * in the video track, which is what actually catches a half-written file.
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

        val extractor = MediaExtractor()
        try {
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

                // R8.3: walk the whole video track. A truncated or corrupt
                // file fails here; a header-only check cannot.
                val walk = walkSamples(extractor, videoTrackIndex, outDurationUs)
                playable = walk.ok
                if (!walk.ok) messages.add(walk.reason)
            } ?: run {
                messages.add("Cannot open output file")
            }
        } catch (e: Exception) {
            messages.add("Verification error: ${e.message}")
        } finally {
            // Was not in a finally, so it leaked on every exception path.
            runCatching { extractor.release() }
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

    private data class Walk(val ok: Boolean, val reason: String)

    /**
     * Read every sample of [trackIndex] to the end of the stream.
     *
     * This is the Android equivalent of the desktop's full decode pass. It does
     * not decode pixels — it pulls each compressed sample out of the container,
     * which is enough to catch the failure that matters here: an export that
     * stopped early and left a file whose header still claims the full
     * duration.
     */
    private fun walkSamples(
        extractor: MediaExtractor,
        trackIndex: Int,
        expectedDurationUs: Long,
    ): Walk {
        return try {
            extractor.selectTrack(trackIndex)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            val buffer = ByteBuffer.allocate(1 shl 20)   // 1 MiB is ample for one sample
            var samples = 0
            var lastPtsUs = 0L

            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break                      // clean end of stream
                lastPtsUs = maxOf(lastPtsUs, extractor.sampleTime)
                samples++
                if (!extractor.advance()) break
            }
            extractor.unselectTrack(trackIndex)

            when {
                samples == 0 ->
                    Walk(false, "output contains no video samples")
                // A file that stops well before its declared duration is the
                // exact shape of a truncated export.
                expectedDurationUs > 0 && lastPtsUs < expectedDurationUs * 0.98 ->
                    Walk(
                        false,
                        "output is truncated: last frame at ${lastPtsUs / 1000}ms of " +
                            "${expectedDurationUs / 1000}ms declared",
                    )
                else -> Walk(true, "")
            }
        } catch (e: Exception) {
            Walk(false, "decode failed after opening: ${e.message}")
        }
    }
}
