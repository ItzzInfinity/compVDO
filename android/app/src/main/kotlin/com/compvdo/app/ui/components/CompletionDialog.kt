package com.compvdo.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.compvdo.app.compression.BatchRunner
import com.compvdo.app.compression.CompressionQueue
import com.compvdo.app.compression.TrashRequest
import com.compvdo.app.util.FileSize

/**
 * The "that's finished" dialog — roadmap 3b.17.
 *
 * Follows the shape of the file-manager dialog the user pointed at: a title
 * naming what completed, a one-line summary, a scrollable list of the actual
 * results, an optional checkbox for the follow-up action, and a single Done.
 *
 * The checkbox is where the delete offer now lives, replacing a separate
 * confirmation dialog. It is safe to fold them together because the platform
 * still runs its own confirmation on API 29+ — ticking the box does not remove
 * anything by itself. On API 28, where there is no such prompt and no trash,
 * the label says "Delete permanently" and the box is unticked.
 */
@UnstableApi
@Composable
fun CompletionDialog(
    completion: CompressionQueue.Completion,
    deleteMessage: String,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var alsoDelete by remember(completion.batchId) { mutableStateOf(false) }
    val canOffer = completion.deletable.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when {
                    completion.cancelled -> "Compression cancelled"
                    completion.ok.size == completion.results.size ->
                        "${completion.ok.size} video(s) compressed"
                    else -> "Finished with ${completion.failed.size + completion.grew.size} issue(s)"
                },
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column {
                Text(completion.summary, style = MaterialTheme.typography.bodyMedium)
                if (completion.savedBytes > 0) {
                    Text(
                        text = "${FileSize.format(completion.beforeBytes)} → " +
                            FileSize.format(completion.beforeBytes - completion.savedBytes),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()

                // Bounded height: a 200-file batch must not push Done off screen.
                LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                    items(completion.results, key = { it.source.uri.toString() }) { r ->
                        ResultRow(r)
                    }
                }

                HorizontalDivider()

                if (canOffer) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = alsoDelete,
                                onValueChange = { alsoDelete = it },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = alsoDelete, onCheckedChange = { alsoDelete = it })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (TrashRequest.isRecoverable())
                                    "Move ${completion.deletable.size} original(s) to trash"
                                else
                                    "Delete ${completion.deletable.size} original(s) permanently",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                text = if (TrashRequest.isRecoverable())
                                    "Restorable from your gallery for about 30 days"
                                else
                                    "This version of Android has no media trash",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (TrashRequest.isRecoverable())
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                if (deleteMessage.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(deleteMessage, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (alsoDelete && canOffer) onDelete() else onDismiss()
            }) {
                Icon(Icons.Default.Check, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Done")
            }
        },
    )
}

@UnstableApi
@Composable
private fun ResultRow(result: BatchRunner.JobResult) {
    val colour = when (result.status) {
        BatchRunner.Status.OK -> MaterialTheme.colorScheme.primary
        BatchRunner.Status.GREW, BatchRunner.Status.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = result.source.displayName,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = when (result.status) {
                BatchRunner.Status.OK ->
                    "${FileSize.format(result.source.size)} → " +
                        "${FileSize.format(result.outputSize)} " +
                        "(${FileSize.formatRatio(result.ratio ?: 1.0)})"
                BatchRunner.Status.GREW -> "Larger than the original — original kept"
                else -> result.message.ifBlank { result.status.name }
            },
            style = MaterialTheme.typography.bodySmall,
            color = colour,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
