package com.compvdo.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.compvdo.app.compression.BatchRunner
import com.compvdo.app.compression.CompressionQueue
import com.compvdo.app.ui.components.ProgressCard
import com.compvdo.app.util.FileSize
import com.compvdo.app.util.VideoPlayback

/**
 * The live view of the compression queue.
 *
 * Owns:   nothing. It renders `CompressionQueue` and sends it Cancel.
 * Reads:  the queue's state flow.
 * Writes: nothing.
 * Runs:   nothing.
 *
 * This screen used to *own* the batch, in a ViewModel scoped to its navigation
 * entry — so leaving it cancelled the encode, and the app hid the bottom
 * navigation bar to stop that happening. The work now lives in
 * `CompressionQueue` for the life of the process; this is a window onto it,
 * and closing the window changes nothing.
 *
 * Implements R12.1 (never blocks) and R12.3 (per-file + overall progress).
 */
@UnstableApi
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressScreen(
    onNavigateBack: () -> Unit,
) {
    val queue by CompressionQueue.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (queue.isRunning) "Compressing" else "Queue") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (queue.isRunning) {
                        IconButton(onClick = { CompressionQueue.cancelCurrent() }) {
                            Icon(Icons.Default.Cancel, contentDescription = "Cancel")
                        }
                    }
                },
            )
        },
    ) { padding ->
        // One scroll for everything: the progress card, the pending list and
        // the per-file results, all as items so none can squeeze the others
        // out on a short screen.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (queue.isRunning) {
                item(key = "progress") {
                    ProgressCard(
                        currentFileName = queue.currentFileName,
                        fileProgress = queue.fileProgress,
                        overallProgress = queue.overallProgress,
                        completedCount = queue.completedInBatch,
                        totalCount = queue.totalInBatch,
                        results = queue.results,
                    )
                }
            }

            if (queue.pending.isNotEmpty()) {
                item(key = "pending-header") {
                    Text(
                        text = "Waiting — ${queue.queuedVideoCount} file(s) in " +
                            "${queue.pending.size} batch(es)",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(queue.pending, key = { it.id }) { batch ->
                    ListItem(
                        headlineContent = {
                            Text("${batch.videos.size} file(s) · ${batch.mode.label}")
                        },
                        supportingContent = {
                            Text(
                                FileSize.format(batch.totalBytes) +
                                    "  ·  audio ${batch.audio.label}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = { CompressionQueue.removePending(batch.id) }) {
                                Text("Remove")
                            }
                        },
                    )
                }
            }

            if (queue.results.isNotEmpty()) {
                item(key = "results-header") {
                    Text(
                        text = "Done in this batch",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                items(queue.results, key = { it.source.uri.toString() }) { result ->
                    ResultItem(result)
                }
            }

            if (!queue.isRunning && queue.pending.isEmpty() && queue.results.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(48.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "Nothing in the queue.\nPick some videos on the Home tab.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@UnstableApi
@Composable
private fun ResultItem(result: BatchRunner.JobResult) {
    val context = LocalContext.current
    val colour = when (result.status) {
        BatchRunner.Status.OK -> MaterialTheme.colorScheme.primary
        BatchRunner.Status.GREW, BatchRunner.Status.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
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
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when (result.status) {
                        BatchRunner.Status.OK ->
                            "${result.source.formattedSize} → " +
                                "${FileSize.format(result.outputSize)} " +
                                "(${FileSize.formatRatio(result.ratio ?: 1.0)})  " +
                                FileSize.formatDuration(result.durationMs)
                        BatchRunner.Status.GREW -> "Larger than the original — original kept"
                        else -> result.message.ifBlank { result.status.name }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colour,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Compare the two without leaving the app (3b.5).
            result.outputUri?.let { out ->
                IconButton(onClick = {
                    VideoPlayback.open(context, out, "video/*", "Play the compressed file")
                }) {
                    Icon(
                        Icons.Default.PlayCircleOutline,
                        contentDescription = "Play the compressed file",
                    )
                }
            }
            IconButton(onClick = {
                VideoPlayback.open(
                    context, result.source.uri, result.source.mimeType, "Play the original",
                )
            }) {
                Icon(
                    Icons.Outlined.PlayCircleOutline,
                    contentDescription = "Play the original",
                )
            }
        }
    }
}
