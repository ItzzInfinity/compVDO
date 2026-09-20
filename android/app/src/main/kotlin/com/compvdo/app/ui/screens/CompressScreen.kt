package com.compvdo.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.compvdo.app.R
import com.compvdo.app.compression.BatchRunner
import com.compvdo.app.data.CompressionMode
import com.compvdo.app.data.VideoInfo
import com.compvdo.app.ui.components.ProgressCard
import com.compvdo.app.util.FileSize

/**
 * Compression screen — shows progress during batch compression.
 * Implements R12.1 (never blocks), R12.3 (per-file + overall progress).
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressScreen(
    videos: List<VideoInfo>,
    mode: CompressionMode,
    deleteOriginal: Boolean,
    onNavigateBack: () -> Unit,
    viewModel: CompressViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // Start compression when screen opens
    LaunchedEffect(videos) {
        if (!uiState.isRunning && uiState.results.isEmpty()) {
            viewModel.startBatch(context, videos, mode, deleteOriginal)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.compressing)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState.isRunning) {
                        IconButton(onClick = { viewModel.cancel() }) {
                            Icon(Icons.Default.Cancel, contentDescription = stringResource(R.string.cancel))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Progress card
            if (uiState.isRunning || uiState.results.isNotEmpty()) {
                ProgressCard(
                    currentFileName = uiState.currentFileName,
                    fileProgress = uiState.fileProgress,
                    overallProgress = uiState.overallProgress,
                    completedCount = uiState.completedCount,
                    totalCount = uiState.totalCount,
                    results = uiState.results,
                )
            }

            // Completion summary
            if (!uiState.isRunning && uiState.results.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                    ) {
                        val okResults = uiState.results.filter { it.status == BatchRunner.Status.OK }
                        val totalSaved = okResults.sumOf { it.source.size - it.outputSize }
                        val totalOriginal = uiState.results.sumOf { it.source.size }

                        Text(
                            text = stringResource(R.string.completed),
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${okResults.size} / ${uiState.results.size} files compressed",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        if (totalSaved > 0) {
                            Text(
                                text = "Saved ${FileSize.format(totalSaved)} of ${FileSize.format(totalOriginal)} " +
                                        "(${FileSize.formatRatio(totalSaved.toDouble() / totalOriginal)})",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // Results list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(uiState.results) { result ->
                    ResultItem(result = result)
                }
            }
        }
    }
}

@Composable
private fun ResultItem(result: BatchRunner.JobResult) {
    val color = when (result.status) {
        BatchRunner.Status.OK -> MaterialTheme.colorScheme.primary
        BatchRunner.Status.GREW -> MaterialTheme.colorScheme.error
        BatchRunner.Status.FAILED -> MaterialTheme.colorScheme.error
        BatchRunner.Status.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        BatchRunner.Status.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = result.source.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = when (result.status) {
                        BatchRunner.Status.OK -> {
                            "${result.source.formattedSize} → ${FileSize.format(result.outputSize)} " +
                                    "(${FileSize.formatRatio(result.ratio ?: 1.0)}) " +
                                    FileSize.formatDuration(result.durationMs)
                        }
                        BatchRunner.Status.GREW -> stringResource(R.string.grew_warning)
                        else -> result.message
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                )
            }

            Text(
                text = result.status.name,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}
