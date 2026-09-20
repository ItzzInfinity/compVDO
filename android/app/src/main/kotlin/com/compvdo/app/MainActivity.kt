package com.compvdo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.compvdo.app.data.CompressionMode
import com.compvdo.app.data.PreferencesRepo
import com.compvdo.app.data.ThemeSetting
import com.compvdo.app.data.VideoInfo
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

    // Selection and the settings chosen in the sheet, carried to CompressScreen.
    var selectedVideos by remember { mutableStateOf<List<VideoInfo>>(emptyList()) }
    var chosenMode by remember { mutableStateOf(CompressionMode.DEFAULT) }
    var chosenDelete by remember { mutableStateOf(false) }
    var chosenAudio by remember { mutableStateOf(AudioSetting.DEFAULT) }

    val storedMode by prefs.compressionMode.collectAsState(initial = CompressionMode.DEFAULT)
    val storedDelete by prefs.deleteOriginal.collectAsState(initial = false)
    val storedAudio by prefs.audioSetting.collectAsState(initial = AudioSetting.DEFAULT)

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.hierarchy?.firstOrNull()?.route

    // The bar is hidden while compressing: that screen is a task, not a place,
    // and letting someone tab away mid-batch invites them to think it stopped.
    val showBar = currentRoute != "compress"

    Scaffold(
        bottomBar = {
            if (showBar) {
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
                    onNavigateToCompress = { videos, mode, deleteOriginals, audio ->
                        selectedVideos = videos
                        chosenMode = mode
                        chosenDelete = deleteOriginals
                        chosenAudio = audio
                        navController.navigate("compress")
                    },
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
                CompressScreen(
                    videos = selectedVideos,
                    mode = chosenMode,
                    deleteOriginal = chosenDelete,
                    audio = chosenAudio,
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
    }
}
