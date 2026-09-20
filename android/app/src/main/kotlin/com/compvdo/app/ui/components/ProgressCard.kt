package com.compvdo.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.compvdo.app.compression.BatchRunner
import com.compvdo.app.util.FileSize

/**
 * Progress card showing per-file and overall batch progress (R12.3).
 */
@Composable
fun ProgressCard(
    currentFileName: String,
    fileProgress: Int,
    overallProgress: Float,
    completedCount: Int,
    totalCount: Int,
    results: List<BatchRunner.JobResult>,
    modifier: Modifier = Modifier,
) {
    val animatedFileProgress by animateFloatAsState(
        targetValue = fileProgress / 100f,
        label = "fileProgress",
    )
    val animatedOverallProgress by animateFloatAsState(
        targetValue = overallProgress,
        label = "overallProgress",
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Current file
            Text(
                text = currentFileName,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
            )

            // Per-file progress
            Text(
                text = "File: $fileProgress%",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { animatedFileProgress },
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()

            // Overall batch progress
            Text(
                text = "Overall: $completedCount / $totalCount",
                style = MaterialTheme.typography.bodyMedium,
            )
            LinearProgressIndicator(
                progress = { animatedOverallProgress },
                modifier = Modifier.fillMaxWidth(),
            )

            // Results summary
            if (results.isNotEmpty()) {
                HorizontalDivider()

                val totalSaved = results.filter { it.status == BatchRunner.Status.OK }
                    .sumOf { it.source.size - (it.outputSize) }
                val totalOriginal = results.sumOf { it.source.size }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Saved: ${FileSize.format(totalSaved)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    val failed = results.count { it.status == BatchRunner.Status.FAILED }
                    val grew = results.count { it.status == BatchRunner.Status.GREW }
                    if (failed > 0 || grew > 0) {
                        Text(
                            text = buildString {
                                if (failed > 0) append("$failed failed")
                                if (grew > 0) {
                                    if (isNotEmpty()) append(", ")
                                    append("$grew grew")
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}
