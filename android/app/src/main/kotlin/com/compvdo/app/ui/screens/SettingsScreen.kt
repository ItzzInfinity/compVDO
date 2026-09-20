package com.compvdo.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.compvdo.app.BuildConfig
import com.compvdo.app.R
import com.compvdo.app.data.CompressionMode
import com.compvdo.app.data.PreferencesRepo
import kotlinx.coroutines.launch

/**
 * Settings screen — compression mode, delete-original toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesRepo(context) }

    val currentMode by prefs.compressionMode.collectAsState(initial = CompressionMode.DEFAULT)
    val deleteOriginal by prefs.deleteOriginal.collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Compression mode
            Text(
                text = stringResource(R.string.compression_mode),
                style = MaterialTheme.typography.titleLarge,
            )

            CompressionMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = currentMode == mode,
                        onClick = {
                            scope.launch { prefs.setCompressionMode(mode) }
                        },
                    )
                    Column(
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = mode.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider()

            // Delete original toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.delete_original),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(R.string.delete_original_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = deleteOriginal,
                    onCheckedChange = { checked ->
                        scope.launch { prefs.setDeleteOriginal(checked) }
                    },
                )
            }

            HorizontalDivider()

            Spacer(Modifier.weight(1f))

            // Version info
            Text(
                text = stringResource(
                    R.string.about_version,
                    BuildConfig.VERSION_NAME,
                    BuildConfig.BUILD_NUMBER,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
