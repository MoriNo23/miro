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
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * RecentTasksNotifier — persistent notification with two actions:
 *   - "Abrir recientes": opens the Recents task switcher (or, on OLAX
 *     where the recents screen is broken, the RecentsActivity directly).
 *   - "Cerrar recientes": fires the RecentTasksCleaner which taps
 *     "Cerrar todo" or swipes cards off the recents screen.
 *
 * Architecture (fixed 2026-09-01):
 *   - The PendingIntent is a broadcast to a STATIC action with the
 *     package set on the intent. FLAG_IMMUTABLE is required on
 *     target SDK 31+.
 *   - The BroadcastReceiver is a separate top-level class (RecentsActionReceiver)
 *     so it can be safely registered without race conditions when the
 *     service re-binds.
 *   - The receiver is registered in the manifest with
 *     android:exported="false" so it can only fire from inside this
 *     package.
 *
 * The previous version (pre-1.4.13) registered the receiver dynamically
 * in show() every time the service re-bound. This caused two bugs:
 *   1. If the service was already registered, the second
 *      registerReceiver() call threw IllegalArgumentException.
 *   2. If the service was destroyed, the dynamic receiver was also
 *      destroyed, so the notification action fired into a void.
 */
class RecentTasksNotifier(
    private val context: Context,
    private val onKillAllTapped: () -> Unit
) {
    companion object {
        private const val TAG = "miro"
        private const val CHANNEL_ID = "miro_recents"
        private const val CHANNEL_NAME = "miro - tareas recientes"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_KILL_ALL = "com.miro.a11y.KILL_ALL_RECENT"
        const val ACTION_OPEN_RECENTS = "com.miro.a11y.OPEN_RECENTS"
    }

    /**
     * Build the PendingIntent for the notification action button.
     *
     * Pre-1.4.21 design used `PendingIntent.getBroadcast` pointing at
     * `RecentsActionReceiver`. This is a notification trampoline on
     * Android 12+: the system silently blocks the transition from the
     * receiver into the AccessibilityService. The fix is to point the
     * PendingIntent directly at the (exported) `RecentsActionActivity`
     * — an Activity is a valid final destination, not a trampoline.
     *
     * Pre-1.4.22: "Abrir recientes" pointed at the empty
     * `RecentsActionActivity` which only fired a GLOBAL_ACTION_RECENTS
     * (broken on OLAX). v1.4.22 makes "Abrir recientes" open our own
     * `RecentsOverviewActivity` fullscreen with a real list of
     * running apps. "Cerrar recientes" still routes through
     * `RecentsActionActivity` to keep the static-callback path simple.
     */
    fun buildActionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val targetClass = if (action == ACTION_OPEN_RECENTS) {
            "com.miro.a11y.RecentsOverviewActivity"
        } else {
            "com.miro.a11y.RecentsActionActivity"
        }
        val intent = Intent(action)
            .setClassName(context, targetClass)
            .setPackage(context.packageName)
        return PendingIntent.getActivity(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

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

        val notif: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("miro")
            .setContentText("Administrar recientes")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Action 1: open the Recents screen. On OLAX this is the only
            // way the user can see the recents because the physical RECENTS
            // button is mapped to a screenshot animation by ESLauncher.
            .addAction(
                android.R.drawable.ic_menu_view,
                "Abrir recientes",
                buildActionPendingIntent(ACTION_OPEN_RECENTS, 1002)
            )
            // Action 2: close all recent apps.
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cerrar recientes",
                buildActionPendingIntent(ACTION_KILL_ALL, 1003)
            )
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notif)
        Log.i(TAG, "recents notif: shown with 2 actions (open + close)")
    }

    fun hide() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.cancel(NOTIFICATION_ID)
        Log.i(TAG, "recents notif: hidden")
    }
}

/**
 * RecentsActionReceiver — receives the two notification actions.
 *
 * Declared in AndroidManifest.xml with android:exported="false" so only
 * the system (which can broadcast to non-exported receivers) can fire it.
 *
 * For ACTION_OPEN_RECENTS: starts the MiroAccessibilityService flow that
 * opens the Recents screen via a foreground Intent (necessary because
 * GLOBAL_ACTION_RECENTS is broken on OLAX).
 *
 * For ACTION_KILL_ALL: forwards to the RecentTasksCleaner via a static
 * callback set in the AccessibilityService onServiceConnected.
 */
class RecentsActionReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "miro"
        @Volatile var onKillAllCallback: (() -> Unit)? = null
        @Volatile var onOpenRecentsCallback: (() -> Unit)? = null
    }

    override fun onReceive(ctx: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "recents notif: received action=$action")
        when (action) {
            RecentTasksNotifier.ACTION_KILL_ALL -> {
                onKillAllCallback?.invoke() ?: Log.w(TAG, "recents notif: kill-all callback not set")
            }
            RecentTasksNotifier.ACTION_OPEN_RECENTS -> {
                onOpenRecentsCallback?.invoke() ?: Log.w(TAG, "recents notif: open-recents callback not set")
            }
        }
    }
}
