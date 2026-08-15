package com.miro.a11y

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log

/**
 * MiroLauncherActivity — dual-purpose:
 *
 * 1. LAUNCHER: declares HOME/MAIN/DEFAULT intent-filter so the system launches
 *    it automatically after every boot (the only user-app component Android
 *    starts on its own post-reboot). On this OLAX/Allwinner ROM, plain
 *    BroadcastReceivers, JobScheduler, AlarmManager and WorkManager are all
 *    blocked from firing after boot for non-system apps — but the launcher is
 *    started by the system directly, so it works.
 *
 * 2. A11Y RE-ENABLE: after reboot the ROM keeps ENABLED_ACCESSIBILITY_SERVICES
 *    intact but forces ACCESSIBILITY_ENABLED=0. We flip that single flag back
 *    to 1, then hand control to the real launcher (ESLauncher) and hide.
 *
 * The user's experience is unchanged: ESLauncher shows as usual, but miro's
 * accessibility service is live after every reboot with no manual action.
 *
 * Requires WRITE_SECURE_SETTINGS, granted via:
 *   adb shell pm grant com.miro.a11y android.permission.WRITE_SECURE_SETTINGS
 */
class MiroLauncherActivity : Activity() {

    companion object {
        private const val TAG = "miro"
        // Real launcher to hand control to after enabling a11y.
        private const val REAL_LAUNCHER = "com.android.launcher3/.ESLauncher"
        private const val REAL_LAUNCHER_PKG = "com.android.launcher3"
        private const val REAL_LAUNCHER_CLS = "com.android.launcher3.ESLauncher"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "launcher activity started (post-boot or manual)")

        // Re-enable accessibility service (ROM forces it to 0 after reboot).
        reenableAccessibility()

        // Safety net: keep WorkManager scheduled in case the launcher path
        // is ever bypassed.
        MiroReenableWorker.schedule(this)

        // Hand control to the real launcher and get out of the way.
        launchRealLauncher()
        finish()
    }

    private fun reenableAccessibility() {
        try {
            val resolver = contentResolver
            // ENABLED_ACCESSIBILITY_SERVICES already persists across reboot;
            // only the enabled flag is forced to 0. Flip it back.
            Settings.Secure.putInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            Log.i(TAG, "accessibility_enabled set to 1 via launcher")
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot write secure settings — WRITE_SECURE_SETTINGS not granted")
        } catch (e: Exception) {
            Log.e(TAG, "re-enable failed: ${e.message}")
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
            Log.i(TAG, "launched real launcher: $REAL_LAUNCHER")
        } catch (e: Exception) {
            Log.e(TAG, "failed to launch real launcher: ${e.message}")
            // Fallback: generic home intent (system will resolve to default)
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
