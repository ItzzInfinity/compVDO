package com.compvdo.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import kotlinx.coroutines.launch
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.compvdo.app.R
import com.compvdo.app.compression.CompressionQueue
import com.compvdo.app.data.AudioSetting
import com.compvdo.app.data.CompressionMode
import com.compvdo.app.data.VideoInfo
import com.compvdo.app.ui.components.CompressOptionsSheet
import com.compvdo.app.ui.components.FolderTile
import com.compvdo.app.ui.components.SortBar
import com.compvdo.app.ui.components.VideoListItem
import com.compvdo.app.ui.components.VideoTile
import com.compvdo.app.util.FileSize

/**
 * Home — the album grid, and a folder's videos once one is opened.
 *
 * Owns:   the Home tab.
 * Reads:  MediaStore, through HomeViewModel → MediaScanner.
 * Writes: nothing.
 * Runs:   nothing; compression is started from here but runs on CompressScreen.
 *
 * Implements R10.1 (scan fields), R10.2 (sort), R1.4 (exclude our own output),
 * and roadmap 3b.8 (album tiles, two view modes, whole-row selection).
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    /** Open the queue detail screen. Compression itself starts here. */
    onOpenQueue: () -> Unit,
    defaultMode: CompressionMode,
    defaultDeleteOriginals: Boolean,
    defaultAudio: AudioSetting,
    viewModel: HomeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var showOptions by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 3.12 — ask for the notification permission at the moment the reason is
    // obvious, which is now here rather than on the compress screen. A refusal
    // costs the progress notification and nothing else.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* the queue runs either way */ }

    fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.scanVideos(context)
    }

    LaunchedEffect(Unit) {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            // ensureScanned, not scanVideos: this effect re-runs on every
            // re-entry to Home, and a full rescan each time is expensive on a
            // large library. The Refresh action is the explicit path.
            viewModel.ensureScanned(context)
        } else {
            permissionLauncher.launch(permission)
        }
    }

    // Inside a folder, Back returns to the album grid rather than leaving Home.
    androidx.activity.compose.BackHandler(enabled = uiState.openFolder != null) {
        viewModel.closeFolder()
    }

    if (showOptions) {
        CompressOptionsSheet(
            fileCount = uiState.selectedVideos.size,
            totalSize = uiState.selectedSize,
            estimatedSaving = uiState.selectedEstimatedSaving,
            defaultMode = defaultMode,
            defaultDeleteOriginals = defaultDeleteOriginals,
            defaultAudio = defaultAudio,
            onStart = { mode, deleteOriginals, audio ->
                showOptions = false
                askForNotificationsOnce()
                val chosen = uiState.selectedVideos
                val ahead = CompressionQueue.enqueue(
                    context, chosen, mode, audio, deleteOriginals,
                )
                // The selection is cleared so the next pick starts fresh; the
                // queue owns those videos now.
                viewModel.deselectAll()
                scope.launch {
                    val result = snackbar.showSnackbar(
                        message = if (ahead == 0)
                            "Compressing ${chosen.size} video(s)"
                        else
                            "Queued ${chosen.size} video(s) — $ahead batch(es) ahead",
                        actionLabel = "View",
                        duration = SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) onOpenQueue()
                }
            },
            onDismiss = { showOptions = false },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.openFolder?.displayName ?: stringResource(R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (uiState.openFolder != null) {
                        IconButton(onClick = { viewModel.closeFolder() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Albums")
                        }
                    }
                },
                actions = {
                    if (uiState.openFolder != null) {
                        IconButton(
                            onClick = {
                                viewModel.setViewMode(
                                    if (uiState.viewMode == ViewMode.GRID) ViewMode.LIST
                                    else ViewMode.GRID
                                )
                            },
                        ) {
                            Icon(
                                if (uiState.viewMode == ViewMode.GRID)
                                    Icons.AutoMirrored.Filled.List else Icons.Default.GridView,
                                contentDescription = "Switch view",
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.scanVideos(context) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        },
        floatingActionButton = {
            if (uiState.selectedVideos.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showOptions = true },
                    icon = { Icon(Icons.Default.Compress, contentDescription = null) },
                    text = {
                        Text("Compress ${uiState.selectedVideos.size} · " +
                            FileSize.format(uiState.selectedSize))
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            // R10.2 — the same sort options in both views, and in both levels.
            SortBar(
                currentSort = uiState.sortOrder,
                onSortChanged = { viewModel.setSortOrder(it, context) },
            )

            if (uiState.isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            if (uiState.error.isNotBlank()) {
                Text(
                    uiState.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            when {
                uiState.openFolder == null -> AlbumGrid(
                    state = uiState,
                    onOpen = { viewModel.openFolder(it) },
                )

                else -> FolderContents(
                    state = uiState,
                    onToggle = { video, selected -> viewModel.toggleSelection(video, selected) },
                    onSelectAll = { viewModel.selectAll() },
                    onSelectNone = { viewModel.deselectAll() },
                    onSelectSuggested = { viewModel.selectSuggested() },
                )
            }
        }
    }
}

@Composable
private fun AlbumGrid(
    state: HomeUiState,
    onOpen: (com.compvdo.app.data.VideoFolder) -> Unit,
) {
    if (state.folders.isEmpty() && !state.isLoading) {
        EmptyMessage(
            "No videos found.\nIf your videos are in Download or a WhatsApp folder, " +
                "they may need a folder permission."
        )
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.folders, key = { it.bucketId }) { folder ->
            FolderTile(folder = folder, onClick = { onOpen(folder) })
        }
    }
}

@Composable
private fun FolderContents(
    state: HomeUiState,
    onToggle: (VideoInfo, Boolean) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onSelectSuggested: () -> Unit,
) {
    val videos = state.visibleVideos

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onSelectSuggested) { Text("Suggested") }
        TextButton(onClick = onSelectAll) { Text("All") }
        TextButton(onClick = onSelectNone) { Text("None") }
        Spacer(Modifier.weight(1f))
        Text(
            "${videos.size} video(s)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (videos.isEmpty()) {
        EmptyMessage("Nothing in this folder.")
        return
    }

    val selectedUris = remember(state.selectedVideos) {
        state.selectedVideos.mapTo(HashSet()) { it.uri }
    }

    when (state.viewMode) {
        ViewMode.GRID -> LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(videos, key = { it.uri.toString() }) { video ->
                val selected = video.uri in selectedUris
                VideoTile(
                    videoUri = video.uri,
                    title = video.displayName,
                    subtitle = video.formattedSize,
                    isSelected = selected,
                    onToggle = { onToggle(video, !selected) },
                )
            }
        }

        ViewMode.LIST -> LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(videos, key = { it.uri.toString() }) { video ->
                VideoListItem(
                    video = video,
                    isSelected = video.uri in selectedUris,
                    onToggleSelection = { selected -> onToggle(video, selected) },
                )
            }
        }
    }
}

@Composable
private fun EmptyMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}
