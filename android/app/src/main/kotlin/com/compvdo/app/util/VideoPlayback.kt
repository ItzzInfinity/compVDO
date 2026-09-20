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
     * **Do not add `FLAG_GRANT_READ_URI_PERMISSION` to a `content://media/…`
     * URI.** We reach MediaStore through the `READ_MEDIA_VIDEO` permission, not
     * through a grant we hold, so there is no grant to pass on — and asking to
     * forward one we do not have makes `startActivity` throw
     * `SecurityException: UID nnnnn does not have permission to
     * content://media/external_primary/video/media/…`, naming our own uid,
     * which reads misleadingly like the file being inaccessible to us.
     * Observed on device 2026-09-20.
     *
     * The flag is still right for a SAF `content://com.android.externalstorage…`
     * URI, where we really do hold a grant, so it is added only for those.
     */
    fun open(context: Context, uri: Uri, mimeType: String = "video/*", title: String = "Play with") {
        val isMediaStore = uri.authority == "media"

        fun intentFor(withGrant: Boolean) = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType.ifBlank { "video/*" })
            if (withGrant) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        fun launch(withGrant: Boolean) {
            context.startActivity(
                Intent.createChooser(intentFor(withGrant), title).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }

        try {
            launch(withGrant = !isMediaStore)
            AppLog.info("opened ${uri.lastPathSegment ?: uri} in an external player")
        } catch (e: ActivityNotFoundException) {
            AppLog.warn("no video player installed to open ${uri.lastPathSegment}")
            Toast.makeText(context, "No video player installed", Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            // A grant we could not forward. Retry plainly: any real player holds
            // the media permission itself.
            try {
                launch(withGrant = false)
                AppLog.info("opened ${uri.lastPathSegment ?: uri} (without a uri grant)")
            } catch (e2: Exception) {
                AppLog.err("not permitted to open ${uri.lastPathSegment}: ${e2.message}")
                Toast.makeText(context, "Cannot open this file", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
