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
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "launcher activity started (post-boot or manual)")

        Thread {
            reenableAccessibility()
            runOnUiThread {
                launchRealLauncher()
                finish()
            }
        }.start()
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
    private fun reenableAccessibility() {
        var attempt = 0
        while (attempt < MAX_RETRIES) {
            attempt++
            if (attemptToggle(attempt)) {
                Log.i(TAG, "a11y toggle verified on attempt $attempt")
                return
            }
            Log.w(TAG, "a11y toggle attempt $attempt/$MAX_RETRIES failed — retrying")
            Thread.sleep(1000)
        }
        Log.e(TAG, "a11y toggle failed after $MAX_RETRIES attempts — manual fix needed")
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
