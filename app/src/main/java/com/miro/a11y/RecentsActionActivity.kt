package com.miro.a11y

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * RecentsActionActivity — the destination for the two notification
 * action buttons ("Abrir recientes" and "Cerrar recientes").
 *
 * Why an Activity and not a BroadcastReceiver (the previous design)?
 * Android 12 (target SDK 31) introduced the **Notification Trampoline
 * Restrictions**: a BroadcastReceiver that fires from a notification
 * action is NOT allowed to start activities or services. The system
 * silently drops the transition and the user perceives the button as
 * dead. Verified on the OLAX Magic Q1 (Android 12) on 2026-09-01 —
 * the buttons were not firing because
 * `RecentTasksCleaner.start()` calls `service.startActivity(...)` from
 * inside a broadcast path.
 *
 * The canonical Google-blessed fix is to point the PendingIntent
 * directly at an Activity. This shim activity is the *final*
 * destination of the notification tap (not a trampoline), so it
 * may legitimately start the AccessibilityService's startKillAllRecents
 * or startOpenRecents path.
 *
 * Security: the activity is `exported=true` because Android 12
 * PendingIntent.getActivity for a notification action requires it.
 * We validate the action and the package on every onCreate to make
 * sure external apps can't reach our internal callbacks.
 */
class RecentsActionActivity : Activity() {
    companion object {
        private const val TAG = "miro"
        const val ACTION_KILL_ALL = "com.miro.a11y.KILL_ALL_RECENT"
        const val ACTION_OPEN_RECENTS = "com.miro.a11y.OPEN_RECENTS"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent ?: run { finish(); return }
        val action = intent.action
        val pkg = intent.`package`

        // Security gate: even though exported=true is required, we only
        // respond to our own internal actions coming from this same
        // package. Anything else is ignored and the activity finishes.
        if (pkg != null && pkg != packageName) {
            Log.w(TAG, "recents action: rejected foreign package=$pkg")
            finish()
            return
        }
        if (action == null) {
            Log.w(TAG, "recents action: missing action on intent")
            finish()
            return
        }

        Log.i(TAG, "recents action: received action=$action")

        when (action) {
            ACTION_KILL_ALL -> {
                // Forward to the AccessibilityService via the static
                // callback wired up in MiroAccessibilityService.onServiceConnected.
                RecentsActionReceiver.onKillAllCallback?.invoke()
                    ?: Log.w(TAG, "recents action: kill-all callback not set")
            }
            ACTION_OPEN_RECENTS -> {
                RecentsActionReceiver.onOpenRecentsCallback?.invoke()
                    ?: Log.w(TAG, "recents action: open-recents callback not set")
            }
            else -> Log.w(TAG, "recents action: unknown action=$action")
        }

        // Activity is themed as translucent / no-display so this
        // doesn't actually paint a UI. finish() drops us immediately.
        finish()
    }
}
