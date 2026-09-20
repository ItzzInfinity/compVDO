package com.compvdo.app.ui.screens

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.compvdo.app.data.MediaScanner
import com.compvdo.app.data.VideoInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val videos: List<VideoInfo> = emptyList(),
    val selectedVideos: List<VideoInfo> = emptyList(),
    val sortOrder: MediaScanner.SortOrder = MediaScanner.SortOrder.SAVINGS,
    val isLoading: Boolean = false,
)

class HomeViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    fun scanVideos(context: Context) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, selectedVideos = emptyList()) }
            try {
                val videos = MediaScanner.scanVideos(context, _uiState.value.sortOrder)
                _uiState.update { it.copy(videos = videos, isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(videos = emptyList(), isLoading = false) }
            }
        }
    }

    fun setSortOrder(order: MediaScanner.SortOrder, context: Context) {
        _uiState.update { it.copy(sortOrder = order) }
        scanVideos(context)
    }

    fun toggleSelection(video: VideoInfo, selected: Boolean) {
        _uiState.update { state ->
            val newSelection = if (selected) {
                state.selectedVideos + video
            } else {
                state.selectedVideos - video
            }
            state.copy(selectedVideos = newSelection)
        }
    }

    fun selectAll() {
        _uiState.update { it.copy(selectedVideos = it.videos) }
    }

    fun deselectAll() {
        _uiState.update { it.copy(selectedVideos = emptyList()) }
    }
}
