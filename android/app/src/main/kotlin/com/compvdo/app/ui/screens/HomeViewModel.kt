package com.compvdo.app.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.compvdo.app.data.MediaScanner
import com.compvdo.app.data.VideoFolder
import com.compvdo.app.data.VideoInfo
import com.compvdo.app.util.AppLog
import com.compvdo.app.util.FileSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the video list is drawn (3b.8a). Folders are always a grid. */
enum class ViewMode { GRID, LIST }

data class HomeUiState(
    val folders: List<VideoFolder> = emptyList(),
    val videos: List<VideoInfo> = emptyList(),
    /** Null while showing the album grid; set once the user opens a folder. */
    val openFolder: VideoFolder? = null,
    val selectedVideos: List<VideoInfo> = emptyList(),
    val sortOrder: MediaScanner.SortOrder = MediaScanner.SortOrder.SAVINGS,
    val viewMode: ViewMode = ViewMode.GRID,
    val isLoading: Boolean = false,
    val needsFolderGrant: Boolean = false,
    val error: String = "",
) {
    /** The videos actually on screen: the open folder's, or everything. */
    val visibleVideos: List<VideoInfo>
        get() = openFolder?.let { folder ->
            videos.filter { it.bucketId == folder.bucketId }
        } ?: videos

    val selectedSize: Long get() = selectedVideos.sumOf { it.size }
    val selectedEstimatedSaving: Long get() = selectedVideos.sumOf { it.estimatedSaving }
}

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * Whether a scan has completed at least once.
     *
     * The screen's `LaunchedEffect` re-runs every time Home re-enters
     * composition — switching tabs, coming back from a batch — and on device
     * that produced four full rescans of a 34 GB library in three seconds, plus
     * more while compressing. [ensureScanned] makes the first one happen and
     * the rest no-ops; Refresh stays explicit.
     */
    private var hasScanned = false

    /** Scan only if we never have. Use [scanVideos] for an explicit refresh. */
    fun ensureScanned(context: Context) {
        if (hasScanned || _uiState.value.isLoading) return
        scanVideos(context)
    }

    /**
     * One scan produces both the folder grid and the flat list, so opening a
     * folder and switching view mode never costs another MediaStore pass.
     */
    fun scanVideos(context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedVideos = emptyList(), error = "") }
            try {
                val result = MediaScanner.scan(context, _uiState.value.sortOrder)
                AppLog.info(
                    "scanned ${result.videos.size} video(s) in ${result.folders.size} folder(s), " +
                        FileSize.format(result.videos.sumOf { it.size }) + " total"
                )
                hasScanned = true
                _uiState.update {
                    it.copy(
                        videos = result.videos,
                        folders = result.folders,
                        needsFolderGrant = result.needsFolderGrant,
                        isLoading = false,
                        // A folder that vanished between scans must not strand the UI.
                        openFolder = it.openFolder?.let { open ->
                            result.folders.firstOrNull { f -> f.bucketId == open.bucketId }
                        },
                    )
                }
            } catch (e: Exception) {
                AppLog.err("scan failed: ${e.message}")
                _uiState.update {
                    it.copy(
                        videos = emptyList(), folders = emptyList(),
                        isLoading = false, error = e.message ?: "Scan failed",
                    )
                }
            }
        }
    }

    fun setSortOrder(order: MediaScanner.SortOrder, context: Context) {
        _uiState.update { it.copy(sortOrder = order) }
        scanVideos(context)
    }

    fun setViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
    }

    fun openFolder(folder: VideoFolder) {
        _uiState.update { it.copy(openFolder = folder, selectedVideos = emptyList()) }
    }

    /** Back out of a folder to the album grid. */
    fun closeFolder() {
        _uiState.update { it.copy(openFolder = null, selectedVideos = emptyList()) }
    }

    fun toggleSelection(video: VideoInfo, selected: Boolean) {
        _uiState.update { state ->
            state.copy(
                selectedVideos = if (selected) state.selectedVideos + video
                                 else state.selectedVideos - video,
            )
        }
    }

    /** Select/deselect everything currently visible, not the whole library. */
    fun selectAll() {
        _uiState.update { it.copy(selectedVideos = it.visibleVideos) }
    }

    fun deselectAll() {
        _uiState.update { it.copy(selectedVideos = emptyList()) }
    }

    /** Tick everything the ranking thinks is worth doing (R10.3). */
    fun selectSuggested() {
        _uiState.update { state ->
            state.copy(selectedVideos = state.visibleVideos.filter { it.estimatedSaving > 0 })
        }
    }
}
