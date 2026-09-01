package com.miro.a11y

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * MiroLauncherActivity — HOME launcher wrapper that re-enables the
 * AccessibilityService after every reboot on the OLAX Magic Q1 (no root).
 *
 * Why this exists: the OLAX/Allwinner ROM blocks BOOT_COMPLETED,
 * JobScheduler, AlarmManager and WorkManager for user apps after boot.
 * The ONLY component Android auto-launches post-reboot is a HOME launcher.
 *
 * Flow (v1.4.18 — hybrid):
 *   1. System starts this activity as HOME wrapper.
 *   2. onCreate() checks WRITE_SECURE_SETTINGS. If missing, log + finish.
 *   3. onCreate() schedules the toggle on a daemon Thread that calls
 *      MiroApplication.runToggleAndHandoff().
 *   4. The thread does:
 *      a) ensureServiceInList (re-adds MiroAccessibilityService to list)
 *      b) Toggle ACCESSIBILITY_ENABLED 0→1 (3 retries)
 *      c) Wait BIND_GRACE_MS = 5s
 *      d) Launch ESLauncher via Application context
 *   5. The activity does NOT call finish() — letting the activity die
 *      too early prevents the system from binding the service.
 *   6. The activity is held in "resumed" state by ESLauncher taking
 *      foreground after step 4d.
 *
 * Why not MiroApplication.runToggleAndHandoff from Application.onCreate?
 *   The Application.onCreate runs in main thread. The toggle needs
 *   ~3s of Thread.sleep, which would block the main thread and cause
 *   ANR. A daemon Thread is the right approach.
 *
 * Why not Theme.NoDisplay?
 *   Tested in v1.4.14: OLAX kills the process when NoDisplay activity
 *   is finished. The toggle Thread never gets to run.
 *
 * Why not moveToBack?
 *   Tested in v1.4.15/16: OLAX also kills the process on moveToBack
 *   because the HOME intent fires "displayed" event. The activity
 *   gets a "pause timeout" warning and the system considers the
 *   wrapper dead.
 *
 * Lesson learned: OLAX's "activity timeout" behavior means the activity
 * MUST stay in "resumed" state until ESLauncher takes foreground. The
 * toggle Thread is the unit of work; the activity is just the trigger.
 *
 * Requires WRITE_SECURE_SETTINGS (granted once via ADB):
 *   adb shell pm grant com.miro.a11y android.permission.WRITE_SECURE_SETTINGS
 */
class MiroLauncherActivity : Activity() {

    companion object {
        private const val TAG = "miro"
        private const val REAL_LAUNCHER_PKG = "com.android.launcher3"
        private const val REAL_LAUNCHER_CLS = "com.android.launcher3.ESLauncher"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "launcher activity started (post-boot or manual)")

        // Verify WRITE_SECURE_SETTINGS BEFORE attempting the toggle.
        if (!hasWriteSecureSettings()) {
            Log.e(TAG, "WRITE_SECURE_SETTINGS not granted — cannot toggle a11y. " +
                    "User must run: adb shell pm grant com.miro.a11y " +
                    "android.permission.WRITE_SECURE_SETTINGS")
            // Finish immediately to avoid the "screen oscura" issue.
            // The service won't auto-bind, but the user is not stuck.
            launchRealLauncher()
            finish()
            return
        }

        // v1.4.18: Run the toggle on a daemon Thread so it survives
        // any activity lifecycle issues. The activity does NOT finish()
        // or moveToBack() — we want the activity to stay alive until
        // ESLauncher takes the foreground, because OLAX's activity
        // timeout behavior otherwise kills the process before the
        // AccessibilityManagerService can bind our service.
        val thread = Thread({
            try { Thread.sleep(100) } catch (e: InterruptedException) {}
            Log.i(TAG, "toggle thread: starting MiroApplication.runToggleAndHandoff")
            MiroApplication.runToggleAndHandoff()
            Log.i(TAG, "toggle thread: runToggleAndHandoff returned")
        }, "miro-launcher-toggle")
        thread.isDaemon = true
        thread.start()
    }

    private fun hasWriteSecureSettings(): Boolean {
        return checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
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
