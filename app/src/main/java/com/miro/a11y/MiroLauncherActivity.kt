package com.miro.a11y

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
 * Flow:
 *   1. System starts this activity (HOME intent).
 *   2. reenableAccessibility() performs a full a11y toggle WITH 3 retries
 *      and post-write verification, then launches the real launcher
 *      (ESLauncher) and finishes.
 *
 * Requires WRITE_SECURE_SETTINGS (granted once via ADB):
 *   adb shell pm grant com.miro.a11y android.permission.WRITE_SECURE_SETTINGS
 */
class MiroLauncherActivity : Activity() {

    companion object {
        private const val TAG = "miro"
        // Canonical form: short class name. The system may also accept/store
        // the full form "com.miro.a11y/com.miro.a11y.MiroAccessibilityService"
        // but it's the same service. Use the package as the identity for
        // filtering so we don't get duplicate entries (one per format) when
        // the user adds the service manually before our toggle runs.
        private const val SERVICE_PKG = "com.miro.a11y"
        private const val SERVICE_CLS = "com.miro.a11y.MiroAccessibilityService"
        // Canonical string written into ENABLED_ACCESSIBILITY_SERVICES.
        private const val SERVICE_CANONICAL = "$SERVICE_PKG/$SERVICE_CLS"
        private const val REAL_LAUNCHER_PKG = "com.android.launcher3"
        private const val REAL_LAUNCHER_CLS = "com.android.launcher3.ESLauncher"

        // Toggle robustness constants (audit 2026-09-01)
        private const val A11Y_TOGGLE_DELAY_MS = 2000L
        private const val VERIFY_DELAY_MS = 500L
        private const val MAX_RETRIES = 3
        // Time to wait after the a11y toggle is verified before
        // we move MiroLauncherActivity to the background. This
        // gives AccessibilityManagerService time to bind the
        // MiroAccessibilityService. Without this grace, the bind
        // race causes the service to never get onServiceConnected
        // (verified 2026-09-01). 5s is enough for OLAX.
        private const val BIND_GRACE_MS = 5000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "launcher activity started (post-boot or manual)")

        // Phase 1 fix (2026-09-01): verify WRITE_SECURE_SETTINGS BEFORE
        // attempting the toggle. If the permission is missing (e.g. after
        // a reinstall), the toggle would crash with SecurityException
        // and leave the tablet with a black screen (the activity stays
        // visible because we never reach launchRealLauncher()).
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

        // Run the toggle work on the MAIN thread using Handler.postDelayed.
        // Why not a background Thread? On the OLAX ROM, when MiroLauncherActivity
        // is invoked via 'am start' (manual) or as a HOME wrapper, the process
        // gets killed almost immediately if the activity is not the top
        // resumed activity. A background Thread{} gets killed with the process.
        //
        // By using the main thread, the work is tied to the activity
        // lifecycle and runs as long as the activity is alive. We also
        // explicitly request that the activity stays in the foreground
        // for the duration of the toggle by NOT calling finish() until
        // the toggle is verified.
        //
        // The toggle has ~3s of inter-write sleeps; the user sees Miro
        // for that brief period before ESLauncher takes over.
        startToggleSequence(attempt = 1)
    }

    private fun startToggleSequence(attempt: Int) {
        Log.i(TAG, "toggle attempt $attempt starting")
        if (attempt > MAX_RETRIES) {
            Log.e(TAG, "a11y toggle failed after $MAX_RETRIES attempts — manual fix needed")
            runOnUiThread {
                launchRealLauncher()
                moveToBack()  // do NOT finish() — keep the process alive so the
                              // AccessibilityService stays bound.
            }
            return
        }
        // Schedule the work on the main thread (no Thread{} — survives with
        // the activity).
        mainHandler.post {
            val ok = attemptToggle(attempt)
            if (ok) {
                Log.i(TAG, "a11y toggle verified on attempt $attempt")
                // Wait BIND_GRACE_MS to let AccessibilityManagerService
                // bind our MiroAccessibilityService. Without this grace,
                // the service bind races with the activity tear-down and
                // MiroAccessibilityService never receives
                // onServiceConnected (verified 2026-09-01: logcat showed
                // 'a11y toggle verified' but no 'service connected').
                mainHandler.postDelayed({
                    runOnUiThread {
                        launchRealLauncher()
                        // DO NOT finish() — that would kill the process
                        // and unbind the AccessibilityService. Use
                        // moveTaskToBack so the activity is removed from
                        // the visible task but the process (and the
                        // service) stay alive.
                        moveToBack()
                    }
                }, BIND_GRACE_MS)
            } else {
                Log.w(TAG, "a11y toggle attempt $attempt/$MAX_RETRIES failed — retrying in 1.5s")
                mainHandler.postDelayed({ startToggleSequence(attempt + 1) }, 1500L)
            }
        }
    }

    /**
     * Move the activity to background without killing the process.
     *
     * finish() would cause Android to evict the process from the
     * activity stack, which in turn unbinds the AccessibilityService.
     * By using moveTaskToBack(false) the activity becomes invisible
     * (ESLauncher takes the foreground) but the process keeps living
     * so the service can run the WirelessDebugAutomator / Recents flow.
     */
    private fun moveToBack() {
        try {
            moveTaskToBack(false)
            Log.i(TAG, "moved to back — process stays alive, service stays bound")
        } catch (e: Exception) {
            Log.e(TAG, "moveTaskToBack failed: ${e.message}")
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Returns true if WRITE_SECURE_SETTINGS is granted to this package.
     *
     * This is a runtime check (not a manifest declaration) because the
     * permission can be revoked by the system or lost on reinstall. The
     * declaration is in AndroidManifest.xml with tools:ignore="ProtectedPermissions"
     * (Android would refuse to install the APK with this permission unless
     * it is granted via `pm grant` from a host shell).
     */
    private fun hasWriteSecureSettings(): Boolean {
        return checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Toggle de accesibilidad con 3 reintentos + verificación post-escritura.
     *
     * Patrón (issue 2 / handoff 2026-08-15):
     *   while (attempt < MAX_RETRIES) {
     *       attemptToggle(attempt)  // remove miro → flag 0 (verify) → wait → re-add miro → flag 1 (verify)
     *       if verified: return
     *       else retry after 1s
     *   }
     *
     * Issue 4: NO hardcodeamos otros servicios. Leemos la lista actual,
     * filtramos solo el nuestro, re-escribimos la lista restante.
     */
    private fun reenableAccessibility(): Boolean {
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            attempt++
            if (attemptToggle(attempt)) {
                Log.i(TAG, "a11y toggle verified on attempt $attempt")
                return true
            }
            Log.w(TAG, "a11y toggle attempt $attempt/$MAX_RETRIES failed — retrying")
            Thread.sleep(1000)
        }
        Log.e(TAG, "a11y toggle failed after $MAX_RETRIES attempts — manual fix needed")
        return false
    }

    /** Single toggle attempt. Returns true if all writes verified correctly. */
    private fun attemptToggle(attempt: Int): Boolean {
        val cr = contentResolver

        // Strategy: do NOT remove the service from the list. Just
        // toggle ACCESSIBILITY_ENABLED 0 → 1. This forces the
        // AccessibilityManagerService to:
        //   1. Unbind the service (flag = 0)
        //   2. Re-discover enabled services (still has our entry in
        //      the list, so we are still a candidate)
        //   3. Re-bind the service (flag = 1)
        //
        // The previous implementation removed our entry from the
        // list, then re-added it. That made the system think there
        // was a different list at flag=0 vs flag=1, and the bind
        // never happened on the OLAX ROM (verified 2026-09-01).

        // Step 1: Disable accessibility (this unbinds any service).
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        Thread.sleep(VERIFY_DELAY_MS)
        if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, -1) != 0) {
            Log.w(TAG, "[$attempt] flag 0 verification failed")
            return false
        }

        // Step 2: Wait for the system to process the disable and
        // unbind any previously-bound service. OLAX needs 2s here.
        Thread.sleep(A11Y_TOGGLE_DELAY_MS)

        // Step 3: Re-enable accessibility. This triggers a
        // re-evaluation of the list (which already has our entry
        // since we did not modify it), and the bind should happen.
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Thread.sleep(VERIFY_DELAY_MS)
        if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, -1) != 1) {
            Log.w(TAG, "[$attempt] flag 1 verification failed")
            return false
        }

        return true
    }

    private fun launchRealLauncher() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                component = ComponentName(REAL_LAUNCHER_PKG, REAL_LAUNCHER_CLS)
            }
            startActivity(intent)
            Log.i(TAG, "launched real launcher")
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
