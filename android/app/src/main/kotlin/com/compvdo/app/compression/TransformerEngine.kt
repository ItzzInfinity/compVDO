package com.compvdo.app.compression

import android.content.Context
import android.media.MediaCodecInfo
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.TransformationRequest
import com.compvdo.app.data.CompressionMode
import com.compvdo.app.data.VideoInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Wraps Media3 Transformer for video compression.
 * Implements the encode step from the architecture: plan → encode → verify.
 *
 * Uses hardware HEVC encoder via MediaCodec — no ffmpeg, no native code.
 */
@UnstableApi
object TransformerEngine {

    data class CompressResult(
        val success: Boolean,
        val outputUri: Uri?,
        val outputSize: Long,
        val durationMs: Long,
        val error: String?,
    )

    /**
     * Compress a single video file.
     *
     * @param context Application context
     * @param source The video to compress
     * @param mode The quality mode
     * @param onProgress Called with 0..100 progress
     * @return CompressResult with the output URI and stats
     */
    suspend fun compress(
        context: Context,
        source: VideoInfo,
        mode: CompressionMode,
        onProgress: (Int) -> Unit = {},
    ): CompressResult {
        val startTime = System.currentTimeMillis()

        // Create MediaStore entry for the output (R1.1, R1.2)
        val outputUri = OutputNaming.createOutputUri(context, source)
            ?: return CompressResult(false, null, 0, 0, "Failed to create output entry")

        return try {
            val targetBitrate = QualityLadder.targetBitrate(mode, source)

            // Get the output file descriptor
            val pfd = context.contentResolver.openFileDescriptor(outputUri, "w")
                ?: return CompressResult(false, null, 0, 0, "Failed to open output for writing").also {
                    OutputNaming.deleteOutput(context, outputUri)
                }

            val outputPath = "/proc/self/fd/${pfd.fd}"

            val result = runTransformer(context, source, targetBitrate, outputPath, onProgress)

            pfd.close()

            if (result.success) {
                // Finalize the MediaStore entry (clear IS_PENDING)
                OutputNaming.finalizeOutput(context, outputUri)

                // Check output size
                val outputSize = getFileSize(context, outputUri)
                val elapsed = System.currentTimeMillis() - startTime

                CompressResult(
                    success = true,
                    outputUri = outputUri,
                    outputSize = outputSize,
                    durationMs = elapsed,
                    error = null,
                )
            } else {
                OutputNaming.deleteOutput(context, outputUri)
                result.copy(durationMs = System.currentTimeMillis() - startTime)
            }
        } catch (e: Exception) {
            OutputNaming.deleteOutput(context, outputUri)
            CompressResult(
                success = false,
                outputUri = null,
                outputSize = 0,
                durationMs = System.currentTimeMillis() - startTime,
                error = e.message ?: "Unknown error",
            )
        }
    }

    private suspend fun runTransformer(
        context: Context,
        source: VideoInfo,
        targetBitrate: Int,
        outputPath: String,
        onProgress: (Int) -> Unit,
    ): CompressResult = suspendCancellableCoroutine { cont ->
        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                androidx.media3.transformer.VideoEncoderSettings.Builder()
                    .setBitrate(targetBitrate)
                    .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    .build()
            )
            .setEnableFallback(true)
            .build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(encoderFactory)
            .setTransformationRequest(
                TransformationRequest.Builder()
                    .build()
            )
            .addListener(object : Transformer.Listener {
                override fun onCompleted(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: androidx.media3.transformer.ExportResult
                ) {
                    if (cont.isActive) {
                        onProgress(100)
                        cont.resume(
                            CompressResult(true, null, 0, 0, null)
                        )
                    }
                }

                override fun onError(
                    composition: androidx.media3.transformer.Composition,
                    exportResult: androidx.media3.transformer.ExportResult,
                    exportException: androidx.media3.transformer.ExportException
                ) {
                    if (cont.isActive) {
                        cont.resume(
                            CompressResult(false, null, 0, 0, exportException.message)
                        )
                    }
                }
            })
            .build()

        // Set up progress polling
        transformer.addListener(object : Transformer.Listener {})

        val mediaItem = MediaItem.fromUri(source.uri)

        try {
            transformer.start(mediaItem, outputPath)

            // Poll progress on the main thread
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val progressRunnable = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
                    val progressState = transformer.getProgress(
                        androidx.media3.transformer.ProgressHolder()
                    )
                    val holder = androidx.media3.transformer.ProgressHolder()
                    val state = transformer.getProgress(holder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress)
                    }
                    handler.postDelayed(this, 500)
                }
            }
            handler.postDelayed(progressRunnable, 500)

            cont.invokeOnCancellation {
                handler.removeCallbacks(progressRunnable)
                transformer.cancel()
            }
        } catch (e: Exception) {
            if (cont.isActive) {
                cont.resume(CompressResult(false, null, 0, 0, e.message))
            }
        }
    }

    private fun getFileSize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                it.statSize
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
