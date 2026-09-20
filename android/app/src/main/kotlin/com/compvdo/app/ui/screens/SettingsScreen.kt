package com.compvdo.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.compvdo.app.BuildConfig
import com.compvdo.app.R
import com.compvdo.app.data.AudioSetting
import com.compvdo.app.data.CompressionMode
import com.compvdo.app.data.ThemeSetting
import com.compvdo.app.data.PreferencesRepo
import kotlinx.coroutines.launch

/**
 * Settings screen.
 *
 * Owns:   the Settings tab.
 * Reads:  the user's preferences.
 * Writes: the user's preferences.
 * Runs:   nothing.
 *
 * **This Column must stay scrollable.** It was a plain `fillMaxSize()` Column,
 * which silently clips anything past one screen rather than scrolling — on
 * device the whole Appearance section sat below the fold and could not be
 * reached at all. Every section added here makes the screen taller, so the
 * scroll is not optional and `weight()` cannot be used inside it (weight needs
 * a bounded height, which a scrolling column does not have).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    /**
     * Null when Settings is a bottom-nav tab, which it now is. A back arrow on
     * a tab points nowhere — there is no screen behind it — and pressing it
     * would pop to whatever happened to be underneath.
     */
    onNavigateBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { PreferencesRepo(context) }

    val currentMode by prefs.compressionMode.collectAsState(initial = CompressionMode.DEFAULT)
    val deleteOriginal by prefs.deleteOriginal.collectAsState(initial = false)
    val audioSetting by prefs.audioSetting.collectAsState(initial = AudioSetting.DEFAULT)
    val themeSetting by prefs.themeSetting.collectAsState(initial = ThemeSetting.DEFAULT)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
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

            // R6.3 — the opt-in audio ladder. KEEP is a true stream copy, so
            // the default costs the user nothing.
            Text(
                text = "Audio",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Audio is copied untouched unless you choose otherwise. " +
                    "The lowest offered is ${com.compvdo.app.data.AUDIO_MIN_KBPS} kbps.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AudioSetting.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = audioSetting == option,
                            onClick = { scope.launch { prefs.setAudioSetting(option) } },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = audioSetting == option,
                        onClick = { scope.launch { prefs.setAudioSetting(option) } },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            option.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider()

            // R12.4 — light/dark, independent of the phone's setting.
            Text(text = "Appearance", style = MaterialTheme.typography.titleMedium)
            ThemeSetting.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = themeSetting == option,
                            onClick = { scope.launch { prefs.setThemeSetting(option) } },
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = themeSetting == option,
                        onClick = { scope.launch { prefs.setThemeSetting(option) } },
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(option.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            option.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider()

            // Not weight(1f): this column scrolls, so it has no bounded height
            // for a weight to divide up.
            Spacer(Modifier.height(24.dp))

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
