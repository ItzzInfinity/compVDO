package com.compvdo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.compvdo.app.data.AudioSetting
import com.compvdo.app.compression.CompressionQueue
import com.compvdo.app.data.CompressionMode
import com.compvdo.app.data.PreferencesRepo
import com.compvdo.app.data.ThemeSetting
import com.compvdo.app.data.VideoInfo
import com.compvdo.app.ui.components.CompletionDialog
import com.compvdo.app.ui.screens.CompressScreen
import com.compvdo.app.ui.screens.HomeScreen
import com.compvdo.app.ui.screens.LogScreen
import com.compvdo.app.ui.screens.SettingsScreen
import com.compvdo.app.ui.theme.CompVdoTheme

/**
 * Single-activity Compose host — the only Activity in the app.
 *
 * Owns:   the window, the navigation graph and the bottom navigation bar.
 * Reads:  the user's preferences, for the defaults it hands to Home.
 * Writes: nothing.
 * Runs:   nothing.
 */
@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            // Read the preference here, above the theme, so switching it
            // recomposes the whole tree and the change is immediate.
            val prefs = remember { PreferencesRepo(applicationContext) }
            val theme by prefs.themeSetting.collectAsState(initial = ThemeSetting.DEFAULT)

            CompVdoTheme(
                darkTheme = when (theme) {
                    ThemeSetting.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                    ThemeSetting.LIGHT -> false
                    ThemeSetting.DARK -> true
                },
            ) {
                CompVdoRoot()
            }
        }
    }
}

/**
 * The three tabs (roadmap 3b.2), modelled on ytdlnis.
 *
 * Everything that used to live on the single opening screen is now the Home
 * tab; Log and Settings are siblings rather than somewhere you navigate *away*
 * to and lose your selection.
 */
private enum class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    HOME("home", "Home", Icons.Default.Home),
    LOG("log", "Log", Icons.AutoMirrored.Filled.ListAlt),
    SETTINGS("settings", "Settings", Icons.Default.Settings),
}

@UnstableApi
@Composable
private fun CompVdoRoot() {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { PreferencesRepo(context) }

    val storedMode by prefs.compressionMode.collectAsState(initial = CompressionMode.DEFAULT)
    val storedDelete by prefs.deleteOriginal.collectAsState(initial = false)
    val storedAudio by prefs.audioSetting.collectAsState(initial = AudioSetting.DEFAULT)

    val backStackEntry by navController.currentBackStackEntryAsState()

    val queue by CompressionQueue.state.collectAsState()
    val completion by CompressionQueue.completion.collectAsState()
    val deleteMessage by CompressionQueue.deleteMessage.collectAsState()
    val pendingConsent by CompressionQueue.pendingConsent.collectAsState()

    // The platform runs its own confirmation for a trash request; this carries
    // the answer back. It lives here, above the nav graph, so it survives the
    // user being on any tab when a batch finishes.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        CompressionQueue.onConsentResult(
            com.compvdo.app.compression.TrashRequest.consentGranted(result.resultCode)
        )
    }
    LaunchedEffect(pendingConsent) {
        pendingConsent?.let { sender ->
            consentLauncher.launch(IntentSenderRequest.Builder(sender).build())
            CompressionQueue.consentLaunched()
        }
    }

    completion?.let { done ->
        CompletionDialog(
            completion = done,
            deleteMessage = deleteMessage,
            onDelete = { CompressionQueue.requestDelete(context) },
            onDismiss = {
                CompressionQueue.dismissCompletion()
                CompressionQueue.clearDeleteMessage()
            },
        )
    }

    Scaffold(
        bottomBar = {
            // The bar used to be hidden during a batch, to stop the user
            // navigating away and killing the job. The job now outlives the
            // screen, so the bar stays and a banner reports the work instead.
            Column {
                if (queue.isRunning) {
                    QueueBanner(
                        state = queue,
                        onOpen = { navController.navigate("compress") },
                    )
                }
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        val selected = backStackEntry?.destination?.hierarchy
                            ?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(tab.route) {
                                    // Switching tabs must not stack duplicates,
                                    // and must keep each tab's own state.
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Tab.HOME.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Tab.HOME.route) {
                HomeScreen(
                    // Compression starts on Home and keeps running wherever the
                    // user goes; this only opens the detail view.
                    onOpenQueue = { navController.navigate("compress") },
                    defaultMode = storedMode,
                    defaultDeleteOriginals = storedDelete,
                    defaultAudio = storedAudio,
                )
            }

            composable(Tab.LOG.route) {
                LogScreen()
            }

            composable(Tab.SETTINGS.route) {
                SettingsScreen()      // a tab, so no back arrow
            }

            composable("compress") {
                CompressScreen(onNavigateBack = { navController.popBackStack() })
            }
        }
    }
}

/**
 * A thin strip above the navigation bar reporting work that is still running.
 *
 * With the encode no longer pinned to one screen, this is the only thing
 * telling the user it is still going while they browse. Tapping it opens the
 * detail view.
 */
@UnstableApi
@Composable
private fun QueueBanner(
    state: CompressionQueue.State,
    onOpen: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = buildString {
                    append(state.currentFileName.ifBlank { "Compressing" })
                    append("  ")
                    // Clamped: without it the last file reads "N+1 / N".
                    append((state.completedInBatch + 1).coerceAtMost(state.totalInBatch))
                    append("/")
                    append(state.totalInBatch)
                    if (state.queuedVideoCount > 0) {
                        append("  ·  ${state.queuedVideoCount} more queued")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.padding(top = 4.dp))
            LinearProgressIndicator(
                progress = { state.overallProgress },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
