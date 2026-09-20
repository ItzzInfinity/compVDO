package com.compvdo.app.util

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
     * Write the log to `Download/compvdo-log-<timestamp>.txt`.
     *
     * Share alone was not enough: the user pressed what they read as a download
     * button and expected a file on disk. MediaStore's Downloads collection is
     * used rather than a raw path so this needs no storage permission on any
     * supported API level.
     *
     * @return the file's display name on success, or null on failure.
     */
    fun saveToDownloads(context: Context): String? {
        val text = asText()
        if (text.isBlank()) return null

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "compvdo-log-$stamp.txt"

        return try {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                context.contentResolver.insert(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values,
                )?.also { created ->
                    context.contentResolver.openOutputStream(created)?.use {
                        it.write(text.toByteArray())
                    }
                    context.contentResolver.update(
                        created,
                        ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                        null, null,
                    )
                }
            } else {
                // API 28: legacy public Downloads directory.
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                dir.mkdirs()
                java.io.File(dir, name).writeText(text)
                Uri.fromFile(java.io.File(dir, name))
            }
            if (uri == null) null else name
        } catch (e: Exception) {
            add(Level.ERR, "could not save the log: ${e.message}")
            null
        }
    }

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
