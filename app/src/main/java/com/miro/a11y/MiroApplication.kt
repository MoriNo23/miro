package com.miro.a11y

import android.app.Application
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * MiroApplication — Application class for com.miro.a11y.
 *
 * Holds the constants used by MiroLauncherActivity for the toggle
 * pattern, plus a static ensureServiceInList() that the activity
 * calls before every toggle attempt.
 *
 * (v1.4.14-18 attempted to run the toggle from the Application context
 *  on a background Thread, but the AccessibilityManagerService on
 *  OLAX doesn't bind the service unless the toggle runs while
 *  MiroLauncherActivity is the active HOME launcher. So the toggle
 *  moved BACK to the activity's mainHandler in v1.4.19. This class
 *  is kept only for constants and the static helper.)
 */
class MiroApplication : Application() {

    companion object {
        const val TAG = "miro"
        const val SERVICE_CANONICAL = "com.miro.a11y/com.miro.a11y.MiroAccessibilityService"

        // Toggle robustness constants (must match MiroLauncherActivity)
        const val A11Y_TOGGLE_DELAY_MS = 2000L
        const val VERIFY_DELAY_MS = 500L
        const val MAX_RETRIES = 3
        const val BIND_GRACE_MS = 5000L
        const val REAL_LAUNCHER_PKG = "com.android.launcher3"
        const val REAL_LAUNCHER_CLS = "com.android.launcher3.ESLauncher"

        /**
         * Re-add our accessibility service to ENABLED_ACCESSIBILITY_SERVICES
         * if it is missing. Called by MiroLauncherActivity before every
         * toggle attempt (defensive against OLAX's tendency to drop entries).
         */
        @JvmStatic
        fun ensureServiceInListStatic(cr: ContentResolver) {
            val current = Settings.Secure.getString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val list = current.split(":").filter { it.isNotBlank() }
            // Filter out any duplicates of our service (in any format)
            val filtered = list.filter { !it.contains("com.miro.a11y") }
            val newList = if (filtered.isEmpty()) {
                listOf(SERVICE_CANONICAL)
            } else {
                listOf(SERVICE_CANONICAL) + filtered
            }
            val newValue = newList.joinToString(":")
            if (newValue != current) {
                Settings.Secure.putString(
                    cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newValue
                )
                Log.i(TAG, "ensureServiceInList: '$current' → '$newValue'")
            } else {
                Log.i(TAG, "ensureServiceInList: no change, already in list")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "MiroApplication: onCreate — process started")
    }
}

