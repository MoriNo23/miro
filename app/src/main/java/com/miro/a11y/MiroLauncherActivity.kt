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
 * Flow (v1.4.19 — v1.3.5 pattern restored after Mori correction):
 *   1. System starts this activity as HOME wrapper.
 *   2. onCreate() schedules the toggle on the activity's mainHandler
 *      (NOT on a separate Thread, NOT in MiroApplication). The toggle
 *      MUST run while the activity is in 'resumed' state as launcher
 *      — that is the signal to AccessibilityManagerService that
 *      our service should be bound.
 *   3. attemptToggle() runs in the activity's main thread:
 *      a) ensureServiceInList() — re-add MiroAccessibilityService to list
 *      b) Toggle ACCESSIBILITY_ENABLED 0→1 (3 retries)
 *      c) Verify the flag stuck
 *   4. After toggle verified, postDelayed(BIND_GRACE_MS) on mainHandler
 *      to give AccessibilityManagerService time to bind our service.
 *   5. After BIND_GRACE_MS, launch ESLauncher + moveTaskToBack(false).
 *      The activity is moved to background but the process stays
 *      alive so the service keeps running.
 *
 * Why the toggle must run on the activity's mainHandler (Mori 2026-09-01):
 *   "obvio que no lo va a bindear depende de que miro tenga permisos
 *    de launcher lo bindeé y luego cambie a launcher que estaba, asi
 *    es la unica forma que lo binde correctamente"
 *   The AccessibilityManagerService binds the service ONLY when the
 *   package is in the 'resumed' state as a HOME launcher. Running the
 *   toggle in a separate Thread (v1.4.14-18 attempts) makes the
 *   system think the package is not the launcher, and the bind
 *   doesn't happen. Bound services:{} stays empty.
 *
 * Why we accept the "pantalla oscura" (dark screen) for ~8s:
 *   The activity is visible during the toggle + BIND_GRACE_MS. This
 *   is REQUIRED for the bind to happen. The "dark screen" is the
 *   visual signal that the wrapper is working. v1.3.5 worked this
 *   way and the user accepted it.
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

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "launcher activity started (post-boot or manual)")

        // Verify WRITE_SECURE_SETTINGS BEFORE attempting the toggle.
        if (!hasWriteSecureSettings()) {
            Log.e(TAG, "WRITE_SECURE_SETTINGS not granted — cannot toggle a11y. " +
                    "User must run: adb shell pm grant com.miro.a11y " +
                    "android.permission.WRITE_SECURE_SETTINGS")
            // Fall back: launch the real launcher so the user is not stuck
            // on a black screen. The service will stay unbound until the
            // permission is granted and the tablet is rebooted again.
            launchRealLauncher()
            moveToBack()
            return
        }

        // Run the toggle on the activity's mainHandler. The activity
        // must stay in 'resumed' state while the toggle runs so
        // AccessibilityManagerService binds MiroAccessibilityService.
        mainHandler.post {
            val ok = runToggleSequence(attempt = 1)
            if (ok) {
                Log.i(TAG, "a11y toggle verified — waiting ${MiroApplication.BIND_GRACE_MS}ms for bind")
                mainHandler.postDelayed({
                    Log.i(TAG, "BIND_GRACE_MS elapsed — launching real launcher")
                    launchRealLauncher()
                    // moveToBack: activity becomes invisible (ESLauncher
                    // takes foreground) but the process keeps running
                    // so the service stays bound.
                    moveToBack()
                }, MiroApplication.BIND_GRACE_MS)
            } else {
                Log.e(TAG, "a11y toggle failed after all attempts — launching real launcher anyway")
                launchRealLauncher()
                moveToBack()
            }
        }
    }

    /**
     * Toggle de accesibilidad con 3 reintentos + verificación.
     * Runs in the activity's mainHandler (Mori correction 2026-09-01).
     */
    private fun runToggleSequence(attempt: Int): Boolean {
        if (attempt > MiroApplication.MAX_RETRIES) {
            Log.e(TAG, "a11y toggle failed after ${MiroApplication.MAX_RETRIES} attempts")
            return false
        }
        Log.i(TAG, "toggle attempt $attempt starting")
        val ok = attemptToggle(attempt)
        if (ok) {
            Log.i(TAG, "a11y toggle verified on attempt $attempt")
            return true
        }
        Log.w(TAG, "a11y toggle attempt $attempt/${MiroApplication.MAX_RETRIES} failed — retrying in 1.5s")
        // Recursive retry via mainHandler.postDelayed
        val retryHandler = Handler(Looper.getMainLooper())
        retryHandler.postDelayed({ runToggleSequence(attempt + 1) }, 1500L)
        return false
    }

    private fun attemptToggle(attempt: Int): Boolean {
        val cr = contentResolver

        // v1.4.15: Re-ensure MiroAccessibilityService is in the list
        // before every toggle attempt (defensive against OLAX's
        // tendency to silently drop entries).
        try {
            MiroApplication.ensureServiceInListStatic(cr)
        } catch (e: SecurityException) {
            Log.e(TAG, "[$attempt] ensureServiceInList failed: ${e.message}")
            return false
        }

        try {
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        } catch (e: SecurityException) {
            Log.e(TAG, "[$attempt] putInt flag=0 failed: ${e.message}")
            return false
        }
        try { Thread.sleep(MiroApplication.VERIFY_DELAY_MS) } catch (e: InterruptedException) { return false }
        if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, -1) != 0) {
            Log.w(TAG, "[$attempt] flag 0 verification failed")
            return false
        }

        try { Thread.sleep(MiroApplication.A11Y_TOGGLE_DELAY_MS) } catch (e: InterruptedException) { return false }

        try {
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (e: SecurityException) {
            Log.e(TAG, "[$attempt] putInt flag=1 failed: ${e.message}")
            return false
        }
        try { Thread.sleep(MiroApplication.VERIFY_DELAY_MS) } catch (e: InterruptedException) { return false }
        if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, -1) != 1) {
            Log.w(TAG, "[$attempt] flag 1 verification failed")
            return false
        }
        return true
    }

    /**
     * Move the activity to background without killing the process.
     * finish() would evict the process from the activity stack and
     * unbind the AccessibilityService. moveTaskToBack(false) keeps
     * the process alive so the service keeps running.
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

    private fun hasWriteSecureSettings(): Boolean {
        return checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
    }
}
