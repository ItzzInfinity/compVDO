package com.compvdo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.media3.common.util.UnstableApi
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.compvdo.app.data.CompressionMode
import com.compvdo.app.data.PreferencesRepo
import com.compvdo.app.data.VideoInfo
import com.compvdo.app.ui.screens.CompressScreen
import com.compvdo.app.ui.screens.HomeScreen
import com.compvdo.app.ui.screens.SettingsScreen
import com.compvdo.app.ui.theme.CompVdoTheme

/**
 * Single-activity Compose host — the only Activity in the app.
 * Navigation is handled by Compose Navigation.
 */
@UnstableApi
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CompVdoTheme {
                CompVdoNavGraph()
            }
        }
    }
}

@UnstableApi
@Composable
private fun CompVdoNavGraph() {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { PreferencesRepo(context) }

    // Hold selected videos across navigation
    var selectedVideos by remember { mutableStateOf<List<VideoInfo>>(emptyList()) }

    val currentMode by prefs.compressionMode.collectAsState(initial = CompressionMode.DEFAULT)
    val deleteOriginal by prefs.deleteOriginal.collectAsState(initial = false)

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onNavigateToCompress = { videos ->
                    selectedVideos = videos
                    navController.navigate("compress")
                },
                onNavigateToSettings = {
                    navController.navigate("settings")
                },
            )
        }

        composable("compress") {
            CompressScreen(
                videos = selectedVideos,
                mode = currentMode,
                deleteOriginal = deleteOriginal,
                onNavigateBack = {
                    navController.popBackStack()
                },
            )
        }

        composable("settings") {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
            )
        }
    }
}
