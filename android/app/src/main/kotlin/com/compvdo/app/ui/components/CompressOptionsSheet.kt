package com.compvdo.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.compvdo.app.data.AudioSetting
import com.compvdo.app.data.CompressionMode
import com.compvdo.app.util.FileSize

/**
 * The "default or override?" sheet shown when Compress is pressed — roadmap 3b.4.
 *
 * The request was a small tab asking whether to go with the default settings or
 * to override them, so the common case is one tap: the sheet opens showing what
 * the defaults *are*, and "Start" runs them. Overriding expands the controls
 * in place rather than throwing the user into the Settings screen and losing
 * their selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressOptionsSheet(
    fileCount: Int,
    totalSize: Long,
    estimatedSaving: Long,
    defaultMode: CompressionMode,
    defaultDeleteOriginals: Boolean,
    defaultAudio: AudioSetting,
    onStart: (mode: CompressionMode, deleteOriginals: Boolean, audio: AudioSetting) -> Unit,
    onDismiss: () -> Unit,
) {
    var overriding by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(defaultMode) }
    var deleteOriginals by remember { mutableStateOf(defaultDeleteOriginals) }
    var audio by remember { mutableStateOf(defaultAudio) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Expanded, this sheet carries three quality options, four
                // audio options and a toggle — taller than a phone screen.
                // Without the scroll the Start button is simply unreachable.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Compress $fileCount video(s)", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                text = FileSize.format(totalSize) +
                    if (estimatedSaving > 0)
                        " · about ${FileSize.format(estimatedSaving)} could be saved"
                    else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (estimatedSaving > 0) {
                Text(
                    "That figure is an estimate from bits-per-pixel, not a promise.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(20.dp))

            if (!overriding) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Using your defaults", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Quality: ${defaultMode.label} — ${defaultMode.description}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "Audio: ${defaultAudio.label}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            if (defaultDeleteOriginals)
                                "You will be asked about removing the originals afterwards."
                            else
                                "Originals are kept.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { onStart(defaultMode, defaultDeleteOriginals, defaultAudio) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Start") }
                    OutlinedButton(
                        onClick = { overriding = true },
                        modifier = Modifier.weight(1f),
                    ) { Text("Change settings") }
                }
            } else {
                Text("Quality", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                CompressionMode.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = mode == option,
                                onClick = { mode = option },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == option, onClick = { mode = option })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Text("Audio", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                AudioSetting.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = audio == option, onClick = { audio = option })
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = audio == option, onClick = { audio = option })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(option.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                option.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = deleteOriginals,
                            onClick = { deleteOriginals = !deleteOriginals },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = deleteOriginals,
                        onCheckedChange = { deleteOriginals = it },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text("Offer to remove the originals", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Asked afterwards, and only for files that verified and got smaller.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { onStart(mode, deleteOriginals, audio) },
                        modifier = Modifier.weight(1f),
                    ) { Text("Start") }
                    OutlinedButton(
                        onClick = { overriding = false },
                        modifier = Modifier.weight(1f),
                    ) { Text("Back") }
                }
            }
        }
    }
}
