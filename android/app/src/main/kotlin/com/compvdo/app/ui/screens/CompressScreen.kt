package com.compvdo.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.compvdo.app.R
import com.compvdo.app.compression.BatchRunner
import com.compvdo.app.compression.TrashRequest
import com.compvdo.app.data.AudioSetting
import com.compvdo.app.data.CompressionMode
import com.compvdo.app.data.VideoInfo
import com.compvdo.app.ui.components.ProgressCard
import com.compvdo.app.util.AppLog
import com.compvdo.app.util.FileSize
import com.compvdo.app.util.VideoPlayback

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
    audio: AudioSetting = AudioSetting.DEFAULT,
    onNavigateBack: () -> Unit,
    viewModel: CompressViewModel = viewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // 3.12 — POST_NOTIFICATIONS was declared in the manifest and never asked
    // for, so on API 33+ the foreground notification simply never appeared.
    // Ask here, at the one moment the reason is obvious (a batch is about to
    // start), rather than at launch. The answer gates the notification and
    // nothing else: a refusal must never stop the compression.
    var notificationAsked by rememberSaveable { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        AppLog.info(
            if (granted) "notification permission granted"
            else "notification permission refused — compressing without progress in the shade"
        )
        notificationAsked = true
    }

    LaunchedEffect(Unit) {
        val needed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        if (needed) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationAsked = true
        }
    }

    // Start compression once the permission question has been settled, whatever
    // the answer was.
    LaunchedEffect(videos, notificationAsked) {
        if (notificationAsked && !uiState.isRunning && uiState.results.isEmpty()) {
            viewModel.startBatch(context, videos, mode, deleteOriginal, audio)
        }
    }

    // The platform runs its own confirmation for a trash/delete request; this
    // launcher carries its answer back.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onConsentResult(TrashRequest.consentGranted(result.resultCode))
    }

    LaunchedEffect(uiState.pendingConsent) {
        uiState.pendingConsent?.let { sender ->
            consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
            viewModel.consentLaunched()
        }
    }

    if (uiState.askToDelete) {
        DeleteOriginalsDialog(
            targets = uiState.deletable,
            onConfirm = { viewModel.confirmDelete(context) },
            onDismiss = { viewModel.dismissDeletePrompt() },
        )
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

                        if (uiState.deleteMessage.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = uiState.deleteMessage,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }

                        // 3b.6 — offer, never assume. Only verified results that
                        // actually shrank are ever offered (R7.2, R8.4).
                        if (uiState.deletable.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { viewModel.offerDeleteNow() }) {
                                Text(
                                    if (TrashRequest.isRecoverable())
                                        "Move ${uiState.deletable.size} original(s) to trash"
                                    else
                                        "Delete ${uiState.deletable.size} original(s)"
                                )
                            }
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

/**
 * R2.2 / dev_guide.md §12 — confirm with specifics before anything is removed.
 *
 * Names the files, states the count and the total size, says plainly whether
 * this is recoverable on *this* device, and focuses the safe button. There was
 * previously no confirmation at all.
 */
@Composable
private fun DeleteOriginalsDialog(
    targets: List<VideoInfo>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shown = targets.take(12)
    val totalSize = targets.sumOf { it.size }
    val recoverable = TrashRequest.isRecoverable()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (recoverable) "Move the originals to the trash?"
                else "Delete the originals permanently?"
            )
        },
        text = {
            Column {
                Text(
                    "${targets.size} original file(s), ${FileSize.format(totalSize)}. " +
                        "Each one compressed successfully, passed verification, and came " +
                        "out smaller."
                )
                Spacer(Modifier.height(12.dp))
                shown.forEach { video ->
                    Text(
                        "•  ${video.displayName}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (targets.size > shown.size) {
                    Text(
                        "…and ${targets.size - shown.size} more",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = if (recoverable) {
                        "They go to the system trash and can be restored from your " +
                            "gallery for about 30 days."
                    } else {
                        "This version of Android has no media trash. Once removed, " +
                            "these files cannot be recovered."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (recoverable) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (recoverable) "Move to trash" else "Delete permanently")
            }
        },
        dismissButton = {
            // The safe choice is the emphasised one.
            Button(onClick = onDismiss) { Text("Keep originals") }
        },
    )
}

@Composable
private fun ResultItem(result: BatchRunner.JobResult) {
    val context = LocalContext.current
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

            // 3b.5 — compare the two without leaving the app: both open in
            // whichever player the user already trusts.
            if (result.outputUri != null) {
                IconButton(
                    onClick = {
                        VideoPlayback.open(
                            context, result.outputUri, "video/*", "Play the compressed file",
                        )
                    },
                ) {
                    Icon(
                        Icons.Default.PlayCircleOutline,
                        contentDescription = "Play the compressed file",
                    )
                }
            }
            IconButton(
                onClick = {
                    VideoPlayback.open(
                        context, result.source.uri, result.source.mimeType, "Play the original",
                    )
                },
            ) {
                Icon(
                    Icons.Outlined.PlayCircleOutline,
                    contentDescription = "Play the original",
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
