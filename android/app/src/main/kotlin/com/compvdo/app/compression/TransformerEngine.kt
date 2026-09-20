package com.compvdo.app.compression

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.TransformationRequest
import com.compvdo.app.data.AudioSetting
import com.compvdo.app.data.CompressionMode
import com.compvdo.app.data.VideoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
        /**
         * User-facing messages that must be shown, e.g. the R6.5 clamp or the
         * R6.6 "this file has no audio" no-op. Defaulted so existing callers
         * keep compiling; nothing here is ever swallowed silently.
         */
        val notes: List<String> = emptyList(),
    )

    /**
     * Compress a single video file.
     *
     * @param context Application context
     * @param source The video to compress
     * @param mode The quality mode
     * @param audio The audio ladder choice (R6.3). Defaults to
     *   [AudioSetting.KEEP], which is a genuine stream copy (R6.1).
     * @param onProgress Called with 0..100 progress
     * @return CompressResult with the output URI and stats
     */
    suspend fun compress(
        context: Context,
        source: VideoInfo,
        mode: CompressionMode,
        audio: AudioSetting = AudioSetting.DEFAULT,
        onProgress: (Int) -> Unit = {},
    ): CompressResult {
        val startTime = System.currentTimeMillis()

        // -- audio plan (R6) ---------------------------------------------
        // Resolved before anything is encoded so the clamp can be reported
        // even if the export later fails.
        val audioPlan = QualityLadder.resolveAudio(audio)
        val notes = mutableListOf<String>()
        audioPlan.note?.let { notes.add(it) }

        // R6.6: on a source with no audio track the option is a no-op, and the
        // ignored request is reported rather than silently dropped.
        var audioKbps = audioPlan.kbps
        if (audioKbps != null && !hasAudioTrack(context, source.uri)) {
            notes.add("Source has no audio track; the audio option does nothing (R6.6)")
            audioKbps = null
        }

        // Create MediaStore entry for the output (R1.1, R1.2)
        val outputUri = OutputNaming.createOutputUri(context, source)
            ?: return CompressResult(false, null, 0, 0, "Failed to create output entry")

        return try {
            val targetBitrate = QualityLadder.targetBitrate(mode, source)

            // Get the output file descriptor
            // "rw", not "w": the MP4 muxer seeks back to write the moov atom
            // when the export finishes, and a write-only descriptor cannot.
            val pfd = context.contentResolver.openFileDescriptor(outputUri, "rw")
                ?: return CompressResult(false, null, 0, 0, "Failed to open output for writing").also {
                    OutputNaming.deleteOutput(context, outputUri)
                }

            val outputPath = "/proc/self/fd/${pfd.fd}"

            val result = try {
                runTransformer(
                    context, source, targetBitrate, audioKbps, outputPath, onProgress,
                )
            } finally {
                // Was only closed on the happy path, so every failure or
                // cancellation leaked a file descriptor.
                runCatching { pfd.close() }
            }

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
                    notes = notes + result.notes,
                )
            } else {
                withContext(NonCancellable) { OutputNaming.deleteOutput(context, outputUri) }
                result.copy(
                    durationMs = System.currentTimeMillis() - startTime,
                    notes = notes + result.notes,
                )
            }
        } catch (c: CancellationException) {
            // Cancellation is not a failure, and it must not be swallowed:
            // `catch (Exception)` below would have turned it into an ordinary
            // failed result, leaving the coroutine un-cancelled and the batch
            // merrily continuing to the next file after the user pressed Cancel.
            //
            // NonCancellable is what makes the cleanup actually run — a suspend
            // call inside an already-cancelled coroutine throws immediately,
            // which would strand the IS_PENDING MediaStore row forever.
            withContext(NonCancellable) { OutputNaming.deleteOutput(context, outputUri) }
            throw c
        } catch (e: Exception) {
            withContext(NonCancellable) { OutputNaming.deleteOutput(context, outputUri) }
            CompressResult(
                success = false,
                outputUri = null,
                outputSize = 0,
                durationMs = System.currentTimeMillis() - startTime,
                error = e.message ?: "Unknown error",
                notes = notes,
            )
        }
    }

    private suspend fun runTransformer(
        context: Context,
        source: VideoInfo,
        targetBitrate: Int,
        audioKbps: Int?,
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
            .apply {
                // Only ask for audio encoder settings when we actually intend to
                // re-encode. Requesting them unconditionally would be harmless
                // here but muddies the one property that matters below.
                if (audioKbps != null) {
                    setRequestedAudioEncoderSettings(
                        AudioEncoderSettings.Builder()
                            .setBitrate(audioKbps * 1000)   // the API takes bits/s
                            .build()
                    )
                }
            }
            .setEnableFallback(true)
            .build()

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .apply {
                // R6.1 — this is the whole pass-through guarantee. Transformer
                // only re-encodes audio when it is given a reason to; naming an
                // audio MIME type is that reason. Leave it unset and the audio
                // track is transmuxed, sample for sample. So KEEP must not touch
                // this builder at all, and there is deliberately no `else`.
                if (audioKbps != null) {
                    setAudioMimeType(MimeTypes.AUDIO_AAC)
                }
            }
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

        val mediaItem = MediaItem.fromUri(source.uri)

        try {
            transformer.start(mediaItem, outputPath)

            // Poll progress on the main thread
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val progressRunnable = object : Runnable {
                override fun run() {
                    if (!cont.isActive) return
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

    /**
     * Does this source actually carry audio? (R6.6)
     *
     * Asked before planning an audio re-encode so a silent clip reports that
     * the option did nothing, instead of the setting appearing to apply.
     * MediaExtractor only parses the container header here — no decoding.
     */
    private suspend fun hasAudioTrack(context: Context, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            val extractor = MediaExtractor()
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    extractor.setDataSource(pfd.fileDescriptor)
                    (0 until extractor.trackCount).any { i ->
                        extractor.getTrackFormat(i)
                            .getString(MediaFormat.KEY_MIME)
                            ?.startsWith("audio/") == true
                    }
                } ?: false
            } catch (e: Exception) {
                // Unreadable here means the export will fail anyway; assume audio
                // exists so we do not silently claim the option was a no-op.
                true
            } finally {
                runCatching { extractor.release() }
            }
        }

    private suspend fun getFileSize(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                it.statSize
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
