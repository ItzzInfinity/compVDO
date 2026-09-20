package com.compvdo.app.compression

import android.content.Context
import android.content.IntentSender
import androidx.media3.common.util.UnstableApi
import com.compvdo.app.data.AudioSetting
import com.compvdo.app.data.CompressionMode
import com.compvdo.app.data.VideoInfo
import com.compvdo.app.service.CompressionService
import com.compvdo.app.util.AppLog
import com.compvdo.app.util.FileSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * The compression queue — process-scoped, so work survives navigation.
 *
 * Owns:   every running and pending compression in the app.
 * Reads:  nothing directly.
 * Writes: nothing directly; BatchRunner does the writing.
 * Runs:   the encoder, through BatchRunner.
 *
 * **Why this is an object and not a ViewModel.** The batch used to run in
 * `CompressViewModel`'s `viewModelScope`, which is tied to the `compress`
 * navigation entry. Leaving that screen destroyed the ViewModel and therefore
 * *cancelled the encode*. The app worked around that by hiding the bottom
 * navigation bar during a batch — trapping the user on one screen to protect
 * the job. Hoisting the work here inverts that: the user can browse, queue more
 * and change tabs freely, and nothing stops the encode but an explicit Cancel.
 *
 * **Threading.** The scope is `Dispatchers.Main.immediate` deliberately, not
 * `Default`. Media3's `Transformer` is built, started, polled and cancelled on
 * one Looper thread and calls `verifyApplicationThread()` on each; the main
 * looper is the only one guaranteed to be there for the process's lifetime.
 * The encoding itself does not run on this thread — MediaCodec does that on its
 * own — so the UI is not blocked by it.
 *
 * Batches run strictly one at a time. Two hardware encodes at once do not
 * finish sooner; they contend for the same fixed-function block and multiply
 * peak storage while both outputs are half-written.
 */
@UnstableApi
object CompressionQueue {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ids = AtomicLong(0)

    /** One queued request: a selection plus the settings chosen for it. */
    data class Batch(
        val id: Long,
        val videos: List<VideoInfo>,
        val mode: CompressionMode,
        val audio: AudioSetting,
        val offerDelete: Boolean,
    ) {
        val totalBytes: Long get() = videos.sumOf { it.size }
    }

    data class State(
        val running: Batch? = null,
        val currentFileName: String = "",
        val fileProgress: Int = 0,
        val overallProgress: Float = 0f,
        val completedInBatch: Int = 0,
        val totalInBatch: Int = 0,
        /** Results for the batch currently running, as they land. */
        val results: List<BatchRunner.JobResult> = emptyList(),
        /** Batches waiting behind the running one. */
        val pending: List<Batch> = emptyList(),
    ) {
        val isRunning: Boolean get() = running != null
        val queuedVideoCount: Int get() = pending.sumOf { it.videos.size }
    }

    /** What the completion dialog needs. Held until the user dismisses it. */
    data class Completion(
        val batchId: Long,
        val results: List<BatchRunner.JobResult>,
        /** Verified, genuinely-smaller originals that may be offered for removal. */
        val deletable: List<VideoInfo>,
        val offerDelete: Boolean,
        val cancelled: Boolean,
    ) {
        val ok get() = results.filter { it.status == BatchRunner.Status.OK }
        val failed get() = results.filter { it.status == BatchRunner.Status.FAILED }
        val grew get() = results.filter { it.status == BatchRunner.Status.GREW }
        val beforeBytes get() = ok.sumOf { it.source.size }
        val savedBytes get() = ok.sumOf { it.source.size - it.outputSize }
        val summary: String
            get() = buildString {
                append("${ok.size} of ${results.size} compressed")
                if (savedBytes > 0) append(", ${FileSize.format(savedBytes)} saved")
                if (grew.isNotEmpty()) append(", ${grew.size} grew")
                if (failed.isNotEmpty()) append(", ${failed.size} failed")
                append(".")
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Set when a batch finishes; the UI shows it and calls [dismissCompletion]. */
    private val _completion = MutableStateFlow<Completion?>(null)
    val completion: StateFlow<Completion?> = _completion.asStateFlow()

    /** Set when the platform must run its own delete confirmation. */
    private val _pendingConsent = MutableStateFlow<IntentSender?>(null)
    val pendingConsent: StateFlow<IntentSender?> = _pendingConsent.asStateFlow()

    /** A short note about the last removal, shown under the summary. */
    private val _deleteMessage = MutableStateFlow("")
    val deleteMessage: StateFlow<String> = _deleteMessage.asStateFlow()

    private var worker: Job? = null
    private var currentJob: Job? = null

    // ---------------------------------------------------------------- queue

    /**
     * Add a selection to the queue.
     *
     * @return how many batches are ahead of it: 0 means it starts now.
     */
    fun enqueue(
        context: Context,
        videos: List<VideoInfo>,
        mode: CompressionMode,
        audio: AudioSetting,
        offerDelete: Boolean,
    ): Int {
        if (videos.isEmpty()) return -1
        val app = context.applicationContext
        val batch = Batch(ids.incrementAndGet(), videos, mode, audio, offerDelete)

        val ahead = _state.value.let { if (it.isRunning) it.pending.size + 1 else 0 }
        _state.update { it.copy(pending = it.pending + batch) }
        AppLog.info(
            "queued ${videos.size} file(s) (${FileSize.format(batch.totalBytes)}), " +
                if (ahead == 0) "starting now" else "$ahead batch(es) ahead"
        )
        ensureWorker(app)
        return ahead
    }

    /** Start the drain loop if it is not already going. */
    private fun ensureWorker(app: Context) {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (true) {
                val next = _state.value.pending.firstOrNull() ?: break
                _state.update {
                    it.copy(
                        pending = it.pending.drop(1),
                        running = next,
                        results = emptyList(),
                        completedInBatch = 0,
                        totalInBatch = next.videos.size,
                        fileProgress = 0,
                        overallProgress = 0f,
                        currentFileName = "",
                    )
                }
                runBatch(app, next)
            }
            _state.update { it.copy(running = null, currentFileName = "") }
        }
    }

    private suspend fun runBatch(app: Context, batch: Batch) {
        AppLog.tx(
            "compress ${batch.videos.size} file(s), mode=${batch.mode.label}, " +
                "audio=${batch.audio.label}, offerDelete=${batch.offerDelete}"
        )
        CompressionService.start(app)

        // A CHILD of the queue's scope, not of this coroutine: cancelling one
        // batch must stop that batch and leave the drain loop — and everything
        // queued behind it — running.
        val job = scope.launch {
            val results = BatchRunner.runBatch(
                context = app,
                videos = batch.videos,
                mode = batch.mode,
                audio = batch.audio,
                onProgress = { index, total, progress ->
                    val video = batch.videos.getOrNull(index)
                    _state.update { s ->
                        val overall =
                            if (total > 0) (s.completedInBatch + progress / 100f) / total else 0f
                        s.copy(
                            currentFileName = video?.displayName ?: "",
                            fileProgress = progress,
                            overallProgress = overall,
                        )
                    }
                    CompressionService.updateProgress(
                        app,
                        "${video?.displayName ?: "Compressing"} ($progress%)",
                        (_state.value.overallProgress * 100).toInt(),
                    )
                },
                onFileComplete = { result -> record(result) },
            )
            finish(batch, results, cancelled = false)
        }

        currentJob = job
        // join() returns normally when the child is CANCELLED as well as when
        // it completes, so the outcome has to be read off the job afterwards.
        // Relying on a catch here meant a cancelled batch silently produced no
        // completion at all.
        job.join()
        currentJob = null

        if (job.isCancelled) {
            finish(batch, _state.value.results, cancelled = true)
        }

        // Keep the foreground service alive across a hand-off to the next batch.
        if (_state.value.pending.isEmpty()) CompressionService.stop(app)
    }

    private fun record(result: BatchRunner.JobResult) {
        val ratio = result.ratio?.let { " (${(it * 100).toInt()}%)" } ?: ""
        val line = "${result.status.name.padEnd(9)} ${result.source.displayName}  " +
            "${FileSize.format(result.source.size)} -> " +
            "${FileSize.format(result.outputSize)}$ratio" +
            if (result.message.isNotBlank() && result.message != "OK") "  · ${result.message}" else ""
        when (result.status) {
            BatchRunner.Status.FAILED -> AppLog.err(line)
            BatchRunner.Status.GREW, BatchRunner.Status.CANCELLED -> AppLog.warn(line)
            else -> AppLog.info(line)
        }
        _state.update {
            it.copy(results = it.results + result, completedInBatch = it.completedInBatch + 1)
        }
    }

    private fun finish(batch: Batch, results: List<BatchRunner.JobResult>, cancelled: Boolean) {
        val deletable = BatchRunner.deletableOriginals(results)
        val ok = results.count { it.status == BatchRunner.Status.OK }
        val saved = results.filter { it.status == BatchRunner.Status.OK }
            .sumOf { it.source.size - it.outputSize }
        AppLog.info(
            "batch finished: $ok/${results.size} compressed, " +
                "${FileSize.format(saved)} saved; outputs are beside the originals"
        )
        _completion.value = Completion(
            batchId = batch.id,
            results = results,
            deletable = deletable,
            offerDelete = batch.offerDelete,
            cancelled = cancelled,
        )
    }

    // --------------------------------------------------------------- cancel

    /** Stop the running batch. Anything queued behind it still runs. */
    fun cancelCurrent() {
        val job = currentJob
        if (job == null || !job.isActive) {
            AppLog.info("cancel requested, but nothing is running")
            return
        }
        AppLog.warn("cancel requested — stopping the export now")
        job.cancel(CancellationException("Cancelled by user"))
    }

    /** Stop the running batch and discard everything waiting. */
    fun cancelAll() {
        val dropped = _state.value.queuedVideoCount
        _state.update { it.copy(pending = emptyList()) }
        if (dropped > 0) AppLog.warn("dropped $dropped queued file(s)")
        cancelCurrent()
    }

    /** Remove one waiting batch without touching the running one. */
    fun removePending(id: Long) {
        _state.update { it.copy(pending = it.pending.filterNot { b -> b.id == id }) }
    }

    // --------------------------------------------------------------- delete

    fun dismissCompletion() {
        _completion.value = null
    }

    fun clearDeleteMessage() {
        _deleteMessage.value = ""
    }

    /** The user ticked "also remove the originals" and pressed Done. */
    fun requestDelete(context: Context) {
        val targets = _completion.value?.deletable.orEmpty()
        if (targets.isEmpty()) return
        when (val outcome = TrashRequest.request(context, targets.map { it.uri })) {
            is TrashRequest.Outcome.NeedsUserConsent ->
                _pendingConsent.value = outcome.intentSender

            is TrashRequest.Outcome.Removed -> {
                AppLog.warn("removed ${outcome.count} original(s)")
                _deleteMessage.value = "Removed ${outcome.count} original(s)."
                _completion.update { it?.copy(deletable = emptyList()) }
            }

            is TrashRequest.Outcome.Failed -> {
                AppLog.err("originals kept: ${outcome.message}")
                _deleteMessage.value = "Originals kept: ${outcome.message}"
            }
        }
    }

    fun consentLaunched() {
        _pendingConsent.value = null
    }

    fun onConsentResult(granted: Boolean) {
        val count = _completion.value?.deletable?.size ?: 0
        AppLog.warn(if (granted) "user confirmed removal" else "user kept the originals")
        _deleteMessage.value = when {
            !granted -> "Originals kept."
            TrashRequest.isRecoverable() -> "$count original(s) moved to the trash."
            else -> "$count original(s) deleted."
        }
        if (granted) _completion.update { it?.copy(deletable = emptyList()) }
    }
}
