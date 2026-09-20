package com.compvdo.app.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.compvdo.app.compression.BatchRunner
import com.compvdo.app.data.CompressionMode
import com.compvdo.app.data.VideoInfo
import com.compvdo.app.service.CompressionService
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
)

@UnstableApi
class CompressViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(CompressUiState())
    val uiState: StateFlow<CompressUiState> = _uiState.asStateFlow()

    private var isCancelled = false

    fun startBatch(
        context: Context,
        videos: List<VideoInfo>,
        mode: CompressionMode,
        deleteOriginal: Boolean,
    ) {
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

        // Start foreground service
        CompressionService.start(context)

        viewModelScope.launch {
            try {
                val results = BatchRunner.runBatch(
                    context = context,
                    videos = videos,
                    mode = mode,
                    deleteOriginal = deleteOriginal,
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
                        _uiState.update { state ->
                            state.copy(
                                results = state.results + result,
                                completedCount = state.completedCount + 1,
                            )
                        }
                    },
                    isCancelled = { isCancelled },
                )

                _uiState.update {
                    it.copy(
                        isRunning = false,
                        overallProgress = 1f,
                        fileProgress = 100,
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isRunning = false) }
            } finally {
                CompressionService.stop(context)
            }
        }
    }

    fun cancel() {
        isCancelled = true
        _uiState.update { it.copy(cancelled = true) }
    }
}
