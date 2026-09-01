package com.miro.a11y

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MiroLauncherActivity — HOME launcher wrapper that re-enables the
 * AccessibilityService after every reboot on the OLAX Magic Q1 (no root).
 *
 * Why this exists: the OLAX/Allwinner ROM blocks BOOT_COMPLETED,
 * JobScheduler, AlarmManager and WorkManager for user apps after boot.
 * The ONLY component Android auto-launches post-reboot is a HOME launcher.
 *
 * Flow (v1.4.14 — Theme.NoDisplay):
 *   1. System starts this activity as HOME wrapper.
 *   2. onCreate() schedules the toggle work on a static Application-level
 *      Handler (MiroApplication.toggleHandler) — NOT on the activity's
 *      main thread, because the activity will finish() immediately
 *      and the process must keep running the toggle independently.
 *   3. The toggle runs in the process (not in the activity context).
 *   4. Activity finish()es in onCreate after scheduling the work.
 *   5. Toggle verifies the bind, then launches the real launcher
 *      (ESLauncher) via a process-level intent (no activity context).
 *
 * The Theme.NoDisplay is required to avoid OLAX's "black screen" bug
 * where the compositor shows the activity as a dark surface while
 * the toggle runs (issue #1, handoff 2026-09-01-miro-launcher-pantalla-oscura.md).
 *
 * Requires WRITE_SECURE_SETTINGS (granted once via ADB):
 *   adb shell pm grant com.miro.a11y android.permission.WRITE_SECURE_SETTINGS
 */
class MiroLauncherActivity : Activity() {

    companion object {
        private const val TAG = "miro"
        private const val SERVICE_PKG = "com.miro.a11y"
        private const val SERVICE_CLS = "com.miro.a11y.MiroAccessibilityService"
        private const val SERVICE_CANONICAL = "$SERVICE_PKG/$SERVICE_CLS"
        private const val REAL_LAUNCHER_PKG = "com.android.launcher3"
        private const val REAL_LAUNCHER_CLS = "com.android.launcher3.ESLauncher"

        // Toggle robustness constants
        private const val A11Y_TOGGLE_DELAY_MS = 2000L
        private const val VERIFY_DELAY_MS = 500L
        private const val MAX_RETRIES = 3
        // Time to wait after the a11y toggle is verified before
        // we launch the real launcher. This gives
        // AccessibilityManagerService time to bind the
        // MiroAccessibilityService. Without this grace, the bind
        // race causes the service to never get onServiceConnected
        // (verified 2026-09-01). 5s is enough for OLAX.
        private const val BIND_GRACE_MS = 5000L

        // Static Handler at the Application level so the toggle can run
        // even after MiroLauncherActivity is finished.
        // Initialized in MiroApplication.onCreate().
        @JvmStatic
        var appHandler: Handler? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "launcher activity started (post-boot or manual)")

        // v1.4.14: Theme.NoDisplay requires the activity to be invisible.
        // Schedule the toggle work on the static appHandler (Application-level)
        // so it survives the finish() call below, then finish() immediately.
        val handler = appHandler
        if (handler == null) {
            Log.e(TAG, "MiroApplication not initialized — finish() only, service won't auto-bind")
            finish()
            return
        }

        // Verify WRITE_SECURE_SETTINGS BEFORE attempting the toggle.
        if (!hasWriteSecureSettings()) {
            Log.e(TAG, "WRITE_SECURE_SETTINGS not granted — cannot toggle a11y. " +
                    "User must run: adb shell pm grant com.miro.a11y " +
                    "android.permission.WRITE_SECURE_SETTINGS")
            handler.post { MiroApplication.runToggleAndHandoff() }
            finish()
            return
        }

        // Schedule toggle on Application-level Handler, then finish() NOW.
        // The toggle continues to run in the process even after this
        // activity is destroyed.
        handler.post { MiroApplication.runToggleAndHandoff() }
        finish()
    }

    private fun hasWriteSecureSettings(): Boolean {
        return checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
    }
}

