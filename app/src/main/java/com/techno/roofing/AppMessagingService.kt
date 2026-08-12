package com.techno.roofing

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
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
     * The sound-carrying channels, one per alert category.
     *
     * Id, name and sound are declared together so channel creation and the lookup that
     * picks a channel for an incoming message cannot drift apart. These are separate
     * from the general channel named in the manifest meta-data, which stays the
     * fallback for anything that does not name one of these.
     */
    private enum class AlertChannel(
        val id: String,
        val nameRes: Int,
        val descriptionRes: Int,
        val soundRes: Int
    ) {
        NEW_ORDER(
            "new_order",
            R.string.channel_new_order_name,
            R.string.channel_new_order_description,
            R.raw.new_order
        ),
        ORDER_UPDATE(
            "order_update",
            R.string.channel_order_update_name,
            R.string.channel_order_update_description,
            R.raw.order_update
        ),
        STOCK_ALERT(
            "stock_alert",
            R.string.channel_stock_alert_name,
            R.string.channel_stock_alert_description,
            R.raw.restock_lowstock
        );

        companion object {
            /** The channel [id] names, or null when it names none of them. */
            fun forId(id: String?): AlertChannel? = entries.firstOrNull { it.id == id }
        }
    }

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
        // No-op unless MainActivity is alive; a rotation that happens while the app is
        // closed reaches the page through the fetch it does on its next launch instead.
        onTokenRefresh?.invoke(token)
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
            url = message.data[DATA_URL],
            // android_channel_id on a notification payload, or the data key when the
            // send is data-only. Anything unrecognised falls back to the general channel.
            channel = AlertChannel.forId(message.notification?.channelId ?: message.data[DATA_CHANNEL])
        )
    }

    // The notify() call below is guarded by canPostNotifications() on the first line,
    // but lint cannot follow that check across a helper function.
    @SuppressLint("MissingPermission")
    private fun showNotification(
        id: Int,
        title: String,
        body: String,
        url: String?,
        channel: AlertChannel?
    ) {
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

        val builder = NotificationCompat.Builder(
            this,
            channel?.id ?: getString(R.string.notification_channel_id)
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

        // Pre-O has no channels, so the sound has to ride on the notification itself.
        // On O+ this field is ignored and the channel's own sound plays instead.
        if (channel != null) {
            builder.setSound(soundUri(this, channel.soundRes))
        }

        val notification = builder.build()

        NotificationManagerCompat.from(this).notify(id, notification)
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "TechnoRoofingFcm"

        /**
         * Notified on FCM's background thread when a fresh token is issued, so a live
         * MainActivity can forward it to the page without waiting for a relaunch.
         *
         * Static because the service outlives any activity. MainActivity sets it in
         * onCreate and clears it in onDestroy, so no activity is retained past its life.
         */
        @Volatile
        var onTokenRefresh: ((String) -> Unit)? = null

        /** Optional in-app destination carried by a notification tap. */
        const val EXTRA_PUSH_URL = "com.techno.roofing.extra.PUSH_URL"

        /** Data-payload keys, used when a send has no `notification` block. */
        private const val DATA_TITLE = "title"
        private const val DATA_BODY = "body"

        /**
         * Data-payload key naming one of the [AlertChannel] ids. Only needed for
         * data-only sends; a notification payload carries `android_channel_id` instead.
         */
        private const val DATA_CHANNEL = "channel_id"

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
            val manager = context.getSystemService(NotificationManager::class.java)

            val channel = NotificationChannel(
                context.getString(R.string.notification_channel_id),
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_description)
            }
            manager.createNotificationChannel(channel)

            // A channel's sound is read once, at creation. Re-registering an existing
            // channel cannot change it, so each of these is fully configured before it
            // is handed over — and altering a sound later needs a new channel id.
            val soundAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()
            AlertChannel.entries.forEach { alert ->
                manager.createNotificationChannel(
                    NotificationChannel(
                        alert.id,
                        context.getString(alert.nameRes),
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply {
                        description = context.getString(alert.descriptionRes)
                        setSound(soundUri(context, alert.soundRes), soundAttributes)
                    }
                )
            }
        }

        /** Points at one of the bundled `res/raw` sounds. */
        private fun soundUri(context: Context, soundRes: Int): Uri =
            Uri.parse(
                "${ContentResolver.SCHEME_ANDROID_RESOURCE}://${context.packageName}/$soundRes"
            )
    }
}
