package com.compvdo.app.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import android.content.IntentSender
import com.compvdo.app.compression.BatchRunner
import com.compvdo.app.compression.TrashRequest
import com.compvdo.app.data.CompressionMode
import com.compvdo.app.data.VideoInfo
import com.compvdo.app.service.CompressionService
import com.compvdo.app.util.AppLog
import com.compvdo.app.util.FileSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CompressUiState(
    val isRunning: Boolean = false,
    val currentFileName: String = "",
    val fileProgress: Int = 0,
    val overallProgress: Float = 0f,
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val results: List<BatchRunner.JobResult> = emptyList(),
    val cancelled: Boolean = false,
    /** Originals the user may be offered to remove once the batch is done (3b.6). */
    val deletable: List<VideoInfo> = emptyList(),
    /** True while the "remove the originals?" prompt is up. */
    val askToDelete: Boolean = false,
    /** Set when the platform needs to run its own consent prompt. */
    val pendingConsent: IntentSender? = null,
    /** How many originals were actually removed, for the closing message. */
    val removedCount: Int = 0,
    val deleteMessage: String = "",
)

@UnstableApi
class CompressViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CompressUiState())
    val uiState: StateFlow<CompressUiState> = _uiState.asStateFlow()

    private var isCancelled = false

    /** Whether the user asked to be offered a delete once the batch finishes. */
    private var offerDelete = false

    fun startBatch(
        context: Context,
        videos: List<VideoInfo>,
        mode: CompressionMode,
        deleteOriginal: Boolean,
    ) {
        offerDelete = deleteOriginal
        if (_uiState.value.isRunning) return
        isCancelled = false

        _uiState.update {
            it.copy(
                isRunning = true,
                totalCount = videos.size,
                results = emptyList(),
                completedCount = 0,
                cancelled = false,
            )
        }

        AppLog.tx(
            "compress ${videos.size} file(s), mode=${mode.label}, " +
                "offerDelete=$deleteOriginal"
        )

        // Start foreground service
        CompressionService.start(context)

        viewModelScope.launch {
            try {
                val results = BatchRunner.runBatch(
                    context = context,
                    videos = videos,
                    mode = mode,
                    onProgress = { fileIndex, totalFiles, fileProgress ->
                        val video = videos.getOrNull(fileIndex)
                        _uiState.update { state ->
                            val overall = if (totalFiles > 0) {
                                (state.completedCount.toFloat() + fileProgress / 100f) / totalFiles
                            } else 0f
                            state.copy(
                                currentFileName = video?.displayName ?: "",
                                fileProgress = fileProgress,
                                overallProgress = overall,
                            )
                        }
                    },
                    onFileComplete = { result ->
                        val ratio = result.ratio?.let { r -> " (${(r * 100).toInt()}%)" } ?: ""
                        val line = "${result.status.name.padEnd(9)} ${result.source.displayName}  " +
                            "${FileSize.format(result.source.size)} -> " +
                            "${FileSize.format(result.outputSize)}$ratio" +
                            if (result.message.isNotBlank() && result.message != "OK")
                                "  · ${result.message}" else ""
                        when (result.status) {
                            BatchRunner.Status.FAILED -> AppLog.err(line)
                            BatchRunner.Status.GREW,
                            BatchRunner.Status.CANCELLED -> AppLog.warn(line)
                            else -> AppLog.info(line)
                        }
                        _uiState.update { state ->
                            state.copy(
                                results = state.results + result,
                                completedCount = state.completedCount + 1,
                            )
                        }
                    },
                    isCancelled = { isCancelled },
                )

                // R2.2/R8.4: only verified, genuinely-smaller results qualify,
                // and even then we ask rather than act.
                val deletable = BatchRunner.deletableOriginals(results)
                val ok = results.count { it.status == BatchRunner.Status.OK }
                val saved = results.filter { it.status == BatchRunner.Status.OK }
                    .sumOf { it.source.size - it.outputSize }
                // dev_guide.md §11: say where the output went, not just that it worked.
                AppLog.info(
                    "batch finished: $ok/${results.size} compressed, " +
                        "${FileSize.format(saved)} saved; outputs are beside the originals"
                )
                if (deletable.isNotEmpty()) {
                    AppLog.info(
                        "${deletable.size} original(s) qualify for removal " +
                            "(verified and smaller); waiting for confirmation"
                    )
                }
                _uiState.update {
                    it.copy(
                        isRunning = false,
                        overallProgress = 1f,
                        fileProgress = 100,
                        deletable = deletable,
                        askToDelete = offerDelete && deletable.isNotEmpty(),
                    )
                }
            } catch (e: Exception) {
                AppLog.err("batch aborted: ${e.message}")
                _uiState.update { it.copy(isRunning = false) }
            } finally {
                CompressionService.stop(context)
            }
        }
    }

    fun cancel() {
        AppLog.warn("cancel requested")
        isCancelled = true
        _uiState.update { it.copy(cancelled = true) }
    }

    // ---------------------------------------------------------------- delete

    /** Show the prompt on demand, e.g. from a button in the summary card. */
    fun offerDeleteNow() {
        if (_uiState.value.deletable.isNotEmpty()) {
            _uiState.update { it.copy(askToDelete = true) }
        }
    }

    fun dismissDeletePrompt() {
        _uiState.update { it.copy(askToDelete = false) }
    }

    /**
     * The user said yes. On API 30+ and 29 the platform still runs its own
     * confirmation; we surface the IntentSender for the screen to launch.
     */
    fun confirmDelete(context: Context) {
        val targets = _uiState.value.deletable
        if (targets.isEmpty()) return

        when (val outcome = TrashRequest.request(context, targets.map { it.uri })) {
            is TrashRequest.Outcome.NeedsUserConsent ->
                _uiState.update {
                    it.copy(askToDelete = false, pendingConsent = outcome.intentSender)
                }

            is TrashRequest.Outcome.Removed -> {
                AppLog.warn("removed ${outcome.count} original(s)")
                _uiState.update {
                    it.copy(
                        askToDelete = false,
                        deletable = emptyList(),
                        removedCount = outcome.count,
                        deleteMessage = "Removed ${outcome.count} original(s).",
                    )
                }
            }

            is TrashRequest.Outcome.Failed -> {
                AppLog.err("originals kept: ${outcome.message}")
                _uiState.update {
                    it.copy(
                        askToDelete = false,
                        deleteMessage = "Originals kept: ${outcome.message}",
                    )
                }
            }
        }
    }

    fun consentLaunched() {
        _uiState.update { it.copy(pendingConsent = null) }
    }

    /** Result of the platform's own confirmation dialog. */
    fun onConsentResult(granted: Boolean) {
        AppLog.warn(if (granted) "user confirmed removal" else "user kept the originals")
        _uiState.update {
            if (granted) {
                it.copy(
                    removedCount = it.deletable.size,
                    deleteMessage = if (TrashRequest.isRecoverable()) {
                        "${it.deletable.size} original(s) moved to the trash."
                    } else {
                        "${it.deletable.size} original(s) deleted."
                    },
                    deletable = emptyList(),
                )
            } else {
                it.copy(deleteMessage = "Originals kept.")
            }
        }
    }
}
