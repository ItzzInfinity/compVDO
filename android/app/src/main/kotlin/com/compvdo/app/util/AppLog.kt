package com.compvdo.app.util

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The application's log — implements roadmap 3b.3.
 *
 * Owns:   an in-memory ring buffer of log lines for the whole process.
 * Reads:  nothing.
 * Writes: nothing to disk; export hands text to another app via a share sheet.
 * Runs:   nothing.
 *
 * The vocabulary is deliberately the same five tags the desktop build uses
 * (`dev_guide.md` §11), so a log pasted from a phone reads identically to one
 * from the CLI: TX a command we issued, RX a line back, INFO normal progress,
 * WARN degraded but continuing, ERR the operation failed.
 *
 * Bounded on purpose. A long batch on a large library would otherwise grow this
 * without limit, and an unbounded log in a process that is already holding
 * video buffers is a memory problem waiting for a slow day.
 */
object AppLog {

    /** Keep the most recent lines only. Roughly a full batch of a few hundred files. */
    const val MAX_ENTRIES = 2_000

    enum class Level { TX, RX, INFO, WARN, ERR }

    @Immutable
    data class Entry(
        val timestamp: Long,
        val level: Level,
        val message: String,
    ) {
        fun format(): String =
            "${TIME_FORMAT.format(Date(timestamp))} [${level.name}] $message"
    }

    private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun tx(message: String) = add(Level.TX, message)
    fun rx(message: String) = add(Level.RX, message)
    fun info(message: String) = add(Level.INFO, message)
    fun warn(message: String) = add(Level.WARN, message)
    fun err(message: String) = add(Level.ERR, message)

    fun add(level: Level, message: String) {
        if (message.isBlank()) return
        val entry = Entry(System.currentTimeMillis(), level, message)
        _entries.update { current ->
            val next = current + entry
            if (next.size > MAX_ENTRIES) next.takeLast(MAX_ENTRIES) else next
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    /** The whole log as plain text, oldest first — what copy and share both use. */
    fun asText(): String = _entries.value.joinToString("\n") { it.format() }

    /**
     * Hand the log to another app (mail, chat, a notes app).
     *
     * Deliberately a plain `EXTRA_TEXT` share rather than a file: it needs no
     * FileProvider round-trip, no storage permission, and no temporary file to
     * clean up, and every target that matters accepts text.
     */
    fun shareIntent(context: Context): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "compVDO log")
            putExtra(Intent.EXTRA_TEXT, asText())
        }
        return Intent.createChooser(send, "Send log")
    }
}
