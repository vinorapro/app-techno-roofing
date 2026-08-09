package com.techno.roofing

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives FCM callbacks. The two delivery paths behave differently, and both are
 * needed for push to work in every app state:
 *
 *  - **Background or killed, `notification` payload**: the FCM SDK posts the tray
 *    notification itself using the manifest meta-data, and [onMessageReceived] is
 *    never called. Nothing in this class runs.
 *  - **Foreground, or any `data`-only payload**: [onMessageReceived] is called and
 *    nothing is shown unless we post it, which is what [showNotification] does.
 */
class AppMessagingService : FirebaseMessagingService() {

    /**
     * Called on first token generation and on every refresh — reinstall, restored
     * backup, cleared app data, or periodic rotation. Send this to the ERP backend
     * from here if you want to target individual devices.
     *
     * The log is debug-only: a token is effectively a send credential for this device
     * and does not belong in release logs. Refresh handling itself is unaffected.
     */
    override fun onNewToken(token: String) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "FCM registration token refreshed: $token")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val notification = message.notification
        val title = notification?.title
            ?: message.data[DATA_TITLE]
            ?: getString(R.string.app_name)
        // Nothing worth showing without a body; a data-only payload meant purely for
        // the web app would be handled here instead.
        val body = notification?.body ?: message.data[DATA_BODY] ?: return

        showNotification(
            id = message.messageId?.hashCode() ?: System.currentTimeMillis().toInt(),
            title = title,
            body = body,
            url = message.data[DATA_URL]
        )
    }

    // The notify() call below is guarded by canPostNotifications() on the first line,
    // but lint cannot follow that check across a helper function.
    @SuppressLint("MissingPermission")
    private fun showNotification(id: Int, title: String, body: String, url: String?) {
        // Silently dropped on Android 13+ if the user denied notifications, so there
        // is no point building anything.
        if (!canPostNotifications()) {
            Log.d(TAG, "Notification suppressed: POST_NOTIFICATIONS not granted")
            return
        }
        ensureNotificationChannel(this)

        val launch = Intent(this, MainActivity::class.java).apply {
            // Reuses the running MainActivity rather than stacking a second WebView.
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (url != null) putExtra(EXTRA_PUSH_URL, url)
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            id,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(
            this,
            getString(R.string.notification_channel_id)
        )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            // Pre-O devices have no channels, so priority is what governs there. Kept in
            // step with IMPORTANCE_DEFAULT so behaviour matches across API levels.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        NotificationManagerCompat.from(this).notify(id, notification)
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "TechnoRoofingFcm"

        /** Optional in-app destination carried by a notification tap. */
        const val EXTRA_PUSH_URL = "com.techno.roofing.extra.PUSH_URL"

        /** Data-payload keys, used when a send has no `notification` block. */
        private const val DATA_TITLE = "title"
        private const val DATA_BODY = "body"

        /**
         * Data-payload key naming an in-app destination.
         *
         * Public because the background path never runs this service: the FCM SDK posts
         * the tray notification and launches MainActivity with the data payload's own
         * keys as intent extras, so MainActivity has to read this key directly.
         */
        const val DATA_URL = "url"

        /**
         * The channel must exist before anything posts to it, including the tray
         * notifications the FCM SDK builds while the app is backgrounded — which is
         * why the manifest meta-data names this same id. Re-creating a channel is a
         * no-op, so calling this repeatedly is safe.
         *
         * IMPORTANCE_DEFAULT suits routine business notifications: they post with sound
         * but do not interrupt with a heads-up banner. Importance is only read at
         * creation — afterwards the system honours whatever the user has set in Android
         * settings, so this value cannot be changed for existing installs.
         */
        fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                context.getString(R.string.notification_channel_id),
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_description)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}
