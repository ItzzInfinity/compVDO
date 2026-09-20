package com.compvdo.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.compvdo.app.util.AppLog
import kotlinx.coroutines.launch

/**
 * The log viewer — roadmap 3b.3.
 *
 * Owns:   the Log tab.
 * Reads:  AppLog's in-memory buffer.
 * Writes: nothing. Copy goes to the clipboard, Send to a share sheet.
 * Runs:   nothing.
 *
 * Modelled on ytdlnis's log view: monospace, follows the tail, and above all
 * *exportable* — a log the user cannot get out of the app is not much use when
 * they want to send it to someone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    onNavigateBack: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val entries by AppLog.entries.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by remember { mutableStateOf(true) }

    // Follow the tail while the user has not scrolled away.
    LaunchedEffect(entries.size, follow) {
        if (follow && entries.isNotEmpty()) {
            listState.scrollToItem(entries.lastIndex)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log") },
                navigationIcon = {
                    if (onNavigateBack != null) {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            follow = true
                            scope.launch {
                                if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
                            }
                        },
                    ) {
                        Icon(Icons.Default.VerticalAlignBottom, contentDescription = "Scroll to end")
                    }
                    IconButton(
                        enabled = entries.isNotEmpty(),
                        onClick = { copyLog(context) },
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy")
                    }
                    IconButton(
                        enabled = entries.isNotEmpty(),
                        onClick = { context.startActivity(AppLog.shareIntent(context)) },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Send")
                    }
                    IconButton(
                        enabled = entries.isNotEmpty(),
                        onClick = { AppLog.clear() },
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear")
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Nothing logged yet.\nCompress something and the details appear here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(entries) { entry ->
                Text(
                    text = entry.format(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = when (entry.level) {
                        AppLog.Level.ERR -> MaterialTheme.colorScheme.error
                        AppLog.Level.WARN -> MaterialTheme.colorScheme.tertiary
                        AppLog.Level.TX, AppLog.Level.RX -> MaterialTheme.colorScheme.primary
                        AppLog.Level.INFO -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(vertical = 1.dp),
                )
            }
        }
    }
}

private fun copyLog(context: Context) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("compVDO log", AppLog.asText()))
    // Android 13+ shows its own copy confirmation; a second toast would double up.
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
    }
}
