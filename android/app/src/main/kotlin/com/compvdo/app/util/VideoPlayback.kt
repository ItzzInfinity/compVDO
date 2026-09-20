package com.compvdo.app.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Playing a video — roadmap 3b.5.
 *
 * Owns:   nothing on screen.
 * Reads:  nothing.
 * Writes: nothing.
 * Runs:   whichever video player the user already has.
 *
 * The brief offered a choice between building an in-app viewer and handing off
 * to VLC / MX Player / the stock gallery. This takes the hand-off, for three
 * reasons: the user's existing player already handles codecs, subtitles,
 * gestures and rotation far better than a first attempt would; an in-app
 * ExoPlayer would ship a second decoder path to maintain alongside the
 * Transformer one; and "compare the original against the output" works
 * naturally when both open in the same familiar player.
 *
 * `ACTION_VIEW` with a chooser is deliberate over a bare `ACTION_VIEW`: without
 * it, Android silently routes to whatever default was set once, and the user
 * cannot pick a different player to compare in.
 */
object VideoPlayback {

    /**
     * Open [uri] in an external player.
     *
     * MediaStore content URIs carry their own read grant; the extra
     * `FLAG_GRANT_READ_URI_PERMISSION` covers SAF-derived ones, which do not.
     */
    fun open(context: Context, uri: Uri, mimeType: String = "video/*", title: String = "Play with") {
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "video/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(Intent.createChooser(view, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            AppLog.info("opened ${uri.lastPathSegment ?: uri} in an external player")
        } catch (e: ActivityNotFoundException) {
            AppLog.warn("no video player installed to open ${uri.lastPathSegment}")
            Toast.makeText(context, "No video player installed", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            // Most likely a SAF URI whose grant was not persisted.
            AppLog.err("not permitted to open ${uri.lastPathSegment}: ${e.message}")
            Toast.makeText(context, "Cannot open this file", Toast.LENGTH_SHORT).show()
        }
    }
}
