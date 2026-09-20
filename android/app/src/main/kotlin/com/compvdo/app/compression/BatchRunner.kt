package com.compvdo.app.compression

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.media3.common.util.UnstableApi
import com.compvdo.app.data.AudioSetting
import com.compvdo.app.data.CompressionMode
import com.compvdo.app.data.VideoInfo
import com.compvdo.app.util.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

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
     * **Cancellation (R12.2).** This is a cancellable suspend function and that
     * is the whole mechanism: the caller cancels the coroutine, the cancellation
     * reaches `TransformerEngine`'s `suspendCancellableCoroutine`, its
     * `invokeOnCancellation` hook calls `Transformer.cancel()`, and the in-flight
     * export stops. The [isCancelled] flag is kept only as a between-files
     * courtesy check for callers that have no Job to hand; on its own it can
     * never stop a running encode, which is exactly the defect this replaced.
     *
     * A cancelled file gets a CANCELLED result, the remaining files are reported
     * as CANCELLED too, and [sweepPendingOutputs] runs under `NonCancellable` so
     * the half-written MediaStore row cannot survive as an orphaned IS_PENDING
     * entry.
     *
     * @param videos The videos to compress
     * @param mode The quality mode
     * @param onProgress Called with (fileIndex, totalFiles, fileProgress 0..100)
     * @param onFileComplete Called when each file finishes
     * @param isCancelled Optional between-files flag; real cancellation is the Job
     */
    suspend fun runBatch(
        context: Context,
        videos: List<VideoInfo>,
        mode: CompressionMode,
        audio: AudioSetting = AudioSetting.DEFAULT,
        onProgress: (Int, Int, Int) -> Unit = { _, _, _ -> },
        onFileComplete: (JobResult) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): List<JobResult> {
        val results = mutableListOf<JobResult>()

        for ((index, video) in videos.withIndex()) {
            // Both doors: a cooperative flag, and the coroutine's own state.
            if (isCancelled() || !coroutineContext.isActive) {
                val cancelled = cancelledResult(video)
                results.add(cancelled)
                onFileComplete(cancelled)
                continue
            }

            onProgress(index, videos.size, 0)

            try {
                val compressResult = TransformerEngine.compress(
                    context = context,
                    source = video,
                    mode = mode,
                    audio = audio,
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
            } catch (c: CancellationException) {
                // R12.2 — the user pressed Cancel mid-export. Transformer.cancel()
                // has already fired via invokeOnCancellation; what is left is to
                // record it and make sure nothing half-written is left behind.
                AppLog.warn("cancelled during ${video.displayName}")
                val cancelled = cancelledResult(video)
                results.add(cancelled)
                onFileComplete(cancelled)
                for (remaining in videos.drop(index + 1)) {
                    val skipped = cancelledResult(remaining)
                    results.add(skipped)
                    onFileComplete(skipped)
                }
                // NonCancellable: this context is already cancelled, so an
                // ordinary withContext(IO) here would throw before doing anything
                // and the IS_PENDING row would be orphaned forever.
                withContext(NonCancellable) { sweepPendingOutputs(context) }
                throw c
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

    private fun cancelledResult(video: VideoInfo) = JobResult(
        source = video,
        status = Status.CANCELLED,
        outputUri = null,
        outputSize = 0,
        durationMs = 0,
        verified = false,
        deleted = false,
        message = "Cancelled by user",
    )

    /**
     * Delete any output row this app left in the IS_PENDING state.
     *
     * `TransformerEngine.compress` cleans up its own output on the failure path,
     * but its cleanup is a suspend call on Dispatchers.IO: once the job is
     * cancelled that call throws before it runs, so the cancellation path alone
     * leaks the row. MediaStore only ever shows an app its *own* pending items,
     * so a sweep here cannot touch another app's work, and a batch is
     * sequential, so it cannot touch a sibling encode either.
     *
     * Call this only from a NonCancellable context.
     */
    suspend fun sweepPendingOutputs(context: Context) = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext
        try {
            val collection =
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val args = android.os.Bundle().apply {
                putString(
                    android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.Video.Media.IS_PENDING} = 1",
                )
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            val doomed = mutableListOf<Pair<Uri, String>>()
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME),
                args,
                null,
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol) ?: continue
                    if (!name.contains("_compressed")) continue
                    doomed += android.content.ContentUris.withAppendedId(
                        collection,
                        cursor.getLong(idCol),
                    ) to name
                }
            }
            for ((uri, name) in doomed) {
                OutputNaming.deleteOutput(context, uri)
                AppLog.info("removed the unfinished output $name")
            }
            if (doomed.isEmpty()) {
                AppLog.info("no unfinished output left behind")
            }
        } catch (e: Exception) {
            AppLog.warn("could not sweep unfinished outputs: ${e.message}")
        }
    }

}
