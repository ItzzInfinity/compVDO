package com.compvdo.app.compression

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import com.compvdo.app.data.CompressionMode
import com.compvdo.app.data.VideoInfo

/**
 * Manages batch compression of multiple videos — implements R9.
 *
 * Owns:   the compression queue.
 * Reads:  the source videos, through TransformerEngine.
 * Writes: compressed outputs, through MediaStore.
 * Runs:   the hardware encoder, via Media3 Transformer.
 *
 * R9.1: Sequential by default (hardware encoder is the bottleneck).
 * R9.3: One file's failure never aborts the batch.
 *
 * **This class does not delete anything.** It used to, straight after
 * verification, with no way for the user to intervene. Removal now happens
 * only through TrashRequest, only after the batch, and only once the user has
 * confirmed — see [deletableOriginals].
 */
@UnstableApi
object BatchRunner {

    data class JobResult(
        val source: VideoInfo,
        val status: Status,
        val outputUri: Uri?,
        val outputSize: Long,
        val durationMs: Long,
        val verified: Boolean,
        val deleted: Boolean,
        val message: String,
    ) {
        val ratio: Double?
            get() = if (outputSize > 0 && source.size > 0) {
                outputSize.toDouble() / source.size
            } else null

        val grew: Boolean
            get() = ratio != null && ratio!! >= 1.0
    }

    enum class Status {
        OK, GREW, FAILED, CANCELLED, SKIPPED
    }

    /**
     * Which originals it is safe to offer to remove (R2.2, R7.2, R8.4).
     *
     * A result qualifies only if it compressed successfully, passed
     * verification, and actually came out smaller. Anything else keeps its
     * original, whatever the user asked for.
     */
    fun deletableOriginals(results: List<JobResult>): List<VideoInfo> =
        results.filter { it.status == Status.OK && it.verified }.map { it.source }

    /**
     * Run compression on a list of videos sequentially.
     *
     * @param videos The videos to compress
     * @param mode The quality mode
     * @param onProgress Called with (fileIndex, totalFiles, fileProgress 0..100)
     * @param onFileComplete Called when each file finishes
     * @param isCancelled Lambda checked before each file
     */
    suspend fun runBatch(
        context: Context,
        videos: List<VideoInfo>,
        mode: CompressionMode,
        onProgress: (Int, Int, Int) -> Unit = { _, _, _ -> },
        onFileComplete: (JobResult) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): List<JobResult> {
        val results = mutableListOf<JobResult>()

        for ((index, video) in videos.withIndex()) {
            if (isCancelled()) {
                results.add(
                    JobResult(video, Status.CANCELLED, null, 0, 0, false, false, "Cancelled by user")
                )
                continue
            }

            onProgress(index, videos.size, 0)

            try {
                val compressResult = TransformerEngine.compress(
                    context = context,
                    source = video,
                    mode = mode,
                    onProgress = { progress -> onProgress(index, videos.size, progress) },
                )

                if (compressResult.success && compressResult.outputUri != null) {
                    // R7.2: Check if the output grew
                    val grew = compressResult.outputSize >= video.size
                    val status = if (grew) Status.GREW else Status.OK

                    // R8: Verify before allowing delete
                    val verifyResult = Verifier.verify(context, video, compressResult.outputUri)

                    val message = buildString {
                        if (grew) append("Output is larger than original. ")
                        if (!verifyResult.passed) append(verifyResult.message)
                    }.trim()

                    val result = JobResult(
                        source = video,
                        status = status,
                        outputUri = compressResult.outputUri,
                        outputSize = compressResult.outputSize,
                        durationMs = compressResult.durationMs,
                        verified = verifyResult.passed,
                        deleted = false,          // removal is a separate, confirmed step
                        message = message.ifEmpty { "OK" },
                    )
                    results.add(result)
                    onFileComplete(result)
                } else {
                    val result = JobResult(
                        source = video,
                        status = Status.FAILED,
                        outputUri = null,
                        outputSize = 0,
                        durationMs = compressResult.durationMs,
                        verified = false,
                        deleted = false,
                        message = compressResult.error ?: "Compression failed",
                    )
                    results.add(result)
                    onFileComplete(result)
                }
            } catch (e: Exception) {
                val result = JobResult(
                    source = video,
                    status = Status.FAILED,
                    outputUri = null,
                    outputSize = 0,
                    durationMs = 0,
                    verified = false,
                    deleted = false,
                    message = e.message ?: "Unexpected error",
                )
                results.add(result)
                onFileComplete(result)
            }
        }

        return results
    }

}
