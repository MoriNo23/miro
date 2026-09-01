package com.miro.a11y

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
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
                // SECOND TOGGLE to force the bind (OLAX quirk: the first
                // toggle verifies in settings but AccessibilityManagerService
                // does not always wake up to bind the service. Verified
                // 2026-09-01: logcat showed 'a11y toggle verified' but
                // NO 'miro accessibility service connected' — the bind was
                // never triggered. Forcing a second disable+enable cycle
                // after the first verify made the bind reliable in testing.
                mainHandler.postDelayed({
                    val ok2 = attemptToggle(attempt + 100)  // use unique attempt number
                    if (ok2) {
                        Log.i(TAG, "a11y second toggle verified — service should be bound now")
                    } else {
                        Log.w(TAG, "a11y second toggle failed — service may not bind")
                    }
                    // Wait 5s to let AccessibilityManagerService bind our
                    // MiroAccessibilityService. Without this grace, the
                    // service bind races with the activity tear-down and
                    // MiroAccessibilityService never receives
                    // onServiceConnected (verified 2026-09-01: logcat showed
                    // 'a11y toggle verified' but no 'service connected').
                    mainHandler.postDelayed({
                        runOnUiThread {
                            launchRealLauncher()
                            // DO NOT finish() — that would kill the process
                            // and unbind the AccessibilityService. Use
                            // moveTaskToBack + finishAffinity so the
                            // activity is removed from the visible task but
                            // the process (and the service) stay alive.
                            moveToBack()
                        }
                    }, BIND_GRACE_MS)
                }, 1500L)
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

    /** Single toggle attempt. Returns true if all 3 writes verified correctly. */
    private fun attemptToggle(attempt: Int): Boolean {
        val cr = contentResolver

        // Read current list dynamically (issue 4 — no OTHER_SERVICES constant).
        // Strip any entry that resolves to com.miro.a11y/.MiroAccessibilityService
        // regardless of whether it's stored in the short form (com.miro.a11y/cls)
        // or full form (com.miro.a11y/com.miro.a11y.MiroAccessibilityService).
        // This prevents duplicate entries on the next toggle when the user
        // added the service manually with a different format.
        val current = Settings.Secure.getString(
            cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val filtered = current.split(":")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { entry ->
                val pkg = entry.substringBefore("/")
                pkg != SERVICE_PKG
            }
            .joinToString(":")

        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, filtered)
        Thread.sleep(VERIFY_DELAY_MS)

        // Step 2: Disable accessibility
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        Thread.sleep(VERIFY_DELAY_MS)
        if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, -1) != 0) {
            Log.w(TAG, "[$attempt] flag 0 verification failed")
            return false
        }

        // Step 3: Wait for the system to process the disable
        Thread.sleep(A11Y_TOGGLE_DELAY_MS)

        // Step 4: Re-add our service in canonical form
        val newList = if (filtered.isEmpty()) SERVICE_CANONICAL else "$filtered:$SERVICE_CANONICAL"
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newList)
        Thread.sleep(VERIFY_DELAY_MS)
        // Verify by package, not by full string (in case Android normalizes the format)
        val after = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val ourEntries = after.split(":").map { it.trim() }.filter {
            it.substringBefore("/") == SERVICE_PKG
        }
        if (ourEntries.isEmpty()) {
            Log.w(TAG, "[$attempt] re-add verification failed (no entry for $SERVICE_PKG)")
            return false
        }

        // Step 5: Enable accessibility
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
