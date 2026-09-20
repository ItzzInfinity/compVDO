package com.compvdo.app.service

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.compvdo.app.R
import com.compvdo.app.util.AppLog

/**
 * Foreground service that keeps the encode alive when the screen locks.
 *
 * Owns:   the foreground lifetime of a batch and its progress notification.
 * Reads:  nothing.
 * Writes: nothing.
 * Runs:   nothing — the compression itself runs in the ViewModel's coroutine.
 *
 * The notification is driven from [Companion.updateProgress], which posts to
 * the *same* notification id the service put itself in the foreground with.
 * That is deliberate: `notify()` with a known id updates the foreground
 * notification in place, so the ViewModel never needs a binder, a handle on the
 * service instance, or a round trip through `startService`. The previous
 * instance method `updateProgress()` was unreachable for exactly that reason
 * and was never called from anywhere (dev_guide.md §17).
 *
 * Every entry point here degrades rather than throws. A refused
 * POST_NOTIFICATIONS grant (API 33+) or a
 * `ForegroundServiceStartNotAllowedException` (API 31+, service started while
 * the app sat in the background) must cost the user their notification, never
 * their compression.
 */
class CompressionService : Service() {

    companion object {
        const val CHANNEL_ID = "compvdo_compression"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.compvdo.app.START_COMPRESSION"
        const val ACTION_STOP = "com.compvdo.app.STOP_COMPRESSION"

        /** Log the "no notification permission" note once per process, not per file. */
        @Volatile
        private var warnedAboutPermission = false

        /** Last percentage actually posted, so we do not notify 500 times a minute. */
        @Volatile
        private var lastPostedProgress = -1

        @Volatile
        private var lastPostedText = ""

        /**
         * True when the platform will actually show what we post.
         *
         * On API 33+ POST_NOTIFICATIONS is a runtime permission; below that it
         * is implicit. `areNotificationsEnabled()` additionally catches the
         * user having switched the channel off by hand.
         */
        fun notificationsAllowed(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) return false
            }
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

        fun start(context: Context) {
            resetProgress()
            ensureChannel(context)
            val intent = Intent(context, CompressionService::class.java).apply {
                action = ACTION_START
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // API 31+: starting a foreground service from the background is
                // refused outright. The encode can still run; it just loses the
                // protection of foreground priority.
                AppLog.warn(
                    "foreground service not started (${e.javaClass.simpleName}): " +
                        "compressing anyway, but the encode may be throttled in the background"
                )
            }
        }

        fun stop(context: Context) {
            resetProgress()
            val intent = Intent(context, CompressionService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                AppLog.warn("could not stop the foreground service: ${e.message}")
            }
        }

        fun resetProgress() {
            lastPostedProgress = -1
            lastPostedText = ""
        }

        /**
         * Update the foreground notification's text and progress bar.
         *
         * Safe to call from any thread and at any rate: identical updates are
         * dropped, and a missing permission is a no-op with a single warning.
         */
        fun updateProgress(context: Context, text: String, progress: Int) {
            val clamped = progress.coerceIn(0, 100)
            if (clamped == lastPostedProgress && text == lastPostedText) return

            if (!notificationsAllowed(context)) {
                if (!warnedAboutPermission) {
                    warnedAboutPermission = true
                    AppLog.warn(
                        "notifications are not permitted — compressing without a " +
                            "progress notification"
                    )
                }
                return
            }

            lastPostedProgress = clamped
            lastPostedText = text

            ensureChannel(context)
            try {
                NotificationManagerCompat.from(context)
                    .notify(NOTIFICATION_ID, buildNotification(context, text, clamped))
            } catch (e: SecurityException) {
                // Permission revoked between the check and the post.
                AppLog.warn("notification refused: ${e.message}")
            }
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
            }
            manager.createNotificationChannel(channel)
        }

        private fun buildNotification(
            context: Context,
            text: String,
            progress: Int,
        ): Notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setProgress(100, progress, false)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setSilent(true)
                .build()
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startInForeground()
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * `startForeground` throws on API 31+ when the start came from the
     * background, and on API 34+ when the declared type is not allowed in the
     * current state. Neither is a reason to take the process down.
     */
    private fun startInForeground() {
        val notification = buildNotification(this, "Preparing…", 0)
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            val notAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is ForegroundServiceStartNotAllowedException
            AppLog.warn(
                if (notAllowed) {
                    "foreground start refused by the system (app was in the background); " +
                        "the encode continues without foreground priority"
                } else {
                    "foreground start failed: ${e.javaClass.simpleName}: ${e.message}"
                }
            )
            stopSelf()
        }
    }
}
