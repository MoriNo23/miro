package com.miro.a11y

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * RecentTasksNotifier — persistent notification with a "Cerrar recientes"
 * action. Tapping the action fires the RecentTasksCleaner.
 *
 * Architecture: the notification has a PendingIntent that broadcasts
 * "com.miro.a11y.KILL_ALL_RECENT". A BroadcastReceiver is registered
 * dynamically in the service (this same class) which forwards to the
 * onKillAllTapped callback.
 *
 * No additional Service / Receiver class needed — the receiver is
 * created inline as an anonymous BroadcastReceiver inside this class.
 */
class RecentTasksNotifier(
    private val context: Context,
    private val onKillAllTapped: () -> Unit
) {
    companion object {
        private const val CHANNEL_ID = "miro_recents"
        private const val CHANNEL_NAME = "miro - tareas recientes"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_KILL_ALL = "com.miro.a11y.KILL_ALL_RECENT"
    }

    private var receiver: BroadcastReceiver? = null

    fun show() {
        // Create or update the channel (idempotent on Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Toca 'Cerrar recientes' para cerrar todas las apps recientes"
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)
        }

        // Register a receiver for the action. The receiver is unregistered
        // in hide() so the same action can be re-registered after each
        // service reconnect. We use RECEIVER_NOT_EXPORTED because the
        // sender is ourselves (the system) and we don't want other apps
        // firing this.
        val r = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                onKillAllTapped()
            }
        }
        val filter = IntentFilter(ACTION_KILL_ALL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(r, filter)
        }
        receiver = r

        // PendingIntent that broadcasts the action
        val actionIntent = Intent(ACTION_KILL_ALL).setPackage(context.packageName)
        val pi = PendingIntent.getBroadcast(
            context, 0, actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setContentTitle("miro")
            .setContentText("Cerrar todas las recientes")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cerrar recientes",
                pi
            )
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notif)
    }

    fun hide() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID)
        // Unregister the receiver
        receiver?.let { r ->
            try {
                context.unregisterReceiver(r)
            } catch (e: IllegalArgumentException) {
                // Already unregistered
            }
        }
        receiver = null
    }
}
