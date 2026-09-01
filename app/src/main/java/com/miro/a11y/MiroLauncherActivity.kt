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

        // v1.4.17: Run the toggle on a background Thread that survives
        // activity destruction. The activity will be killed by OLAX
        // when ESLauncher takes the foreground (and moveToBack is
        // unreliable on the OLAX ROM), so we use a daemon thread
        // that holds the process alive by referencing MiroApplication.
        //
        // The thread:
        //   1. Waits 50ms (to let the activity call its own onResume)
        //   2. Runs the full toggle + ensureServiceInList
        //   3. Waits BIND_GRACE_MS
        //   4. Launches ESLauncher via a system intent
        //   5. Exits
        val thread = Thread({
            try { Thread.sleep(50) } catch (e: InterruptedException) {}
            Log.i(TAG, "toggle thread: starting MiroApplication.runToggleAndHandoff")
            MiroApplication.runToggleAndHandoff()
            Log.i(TAG, "toggle thread: runToggleAndHandoff returned")
        }, "miro-launcher-toggle")
        thread.isDaemon = true
        thread.start()
        // do NOT call finish() or moveToBack() — the OLAX ROM kills
        // the activity either way when ESLauncher becomes the
        // foreground. The toggle thread is what matters.
    }

    private fun hasWriteSecureSettings(): Boolean {
        return checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Move the activity to the background without killing the process.
     * ESLauncher (the real launcher) takes the foreground. The
     * MiroApplication context keeps running the toggle + service.
     */
    private fun moveToBack() {
        try {
            moveTaskToBack(false)
            Log.i(TAG, "moved to back — process stays alive, service stays bound")
        } catch (e: Exception) {
            Log.e(TAG, "moveTaskToBack failed: ${e.message}")
        }
    }

    private fun launchRealLauncher() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                component = ComponentName(REAL_LAUNCHER_PKG, REAL_LAUNCHER_CLS)
            }
            startActivity(intent)
            Log.i(TAG, "launched real launcher (ESLauncher)")
        } catch (e: Exception) {
            Log.e(TAG, "failed to launch real launcher: ${e.message}")
            try {
                val fallback = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(fallback)
            } catch (e2: Exception) {
                Log.e(TAG, "fallback home launch failed: ${e2.message}")
            }
        }
    }
}

