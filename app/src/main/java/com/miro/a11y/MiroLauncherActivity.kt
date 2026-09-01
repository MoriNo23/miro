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
 *    starts on its own post-reboot on this OLAX/Allwinner ROM). Plain
 *    BroadcastReceivers, JobScheduler, AlarmManager and WorkManager are all
 *    blocked from firing after boot for non-system apps — but the launcher is
 *    started by the system directly, so it works.
 *
 * 2. A11Y RE-ENABLE: after reboot the ROM forces ACCESSIBILITY_ENABLED=0 even
 *    though ENABLED_ACCESSIBILITY_SERVICES still lists miro. We perform the full
 *    toggle (remove miro, disable, wait, re-add miro, enable) which is what
 *    actually forces the service to re-bind, then hand control to the real
 *    launcher (ESLauncher) and hide.
 *
 * The user's experience is unchanged: ESLauncher shows as usual, but miro's
 * accessibility service is live after every reboot with no manual action.
 *
 * Requires WRITE_SECURE_SETTINGS, granted via:
 *   adb shell pm grant com.miro.a11y android.permission.WRITE_SECURE_SETTINGS
 */
open class MiroLauncherActivity : Activity() {

    companion object {
        private const val TAG = "miro"
        private const val SERVICE = "com.miro.a11y/com.miro.a11y.MiroAccessibilityService"
        private const val OTHER_SERVICES =
            "bitpit.launcher/bitpit.launcher.lock_screen.LockScreenService:" +
            "io.github.muntashirakon.AppManager/io.github.muntashirakon.AppManager.accessibility.NoRootAccessibilityService"
        private const val REAL_LAUNCHER_PKG = "com.android.launcher3"
        private const val REAL_LAUNCHER_CLS = "com.android.launcher3.ESLauncher"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "launcher activity started (post-boot or manual)")

        // Perform the full a11y toggle in a background thread, then hand off.
        Thread {
            reenableAccessibility()
            runOnUiThread {
                launchRealLauncher()
                finish()
            }
        }.start()
    }

    private fun reenableAccessibility() {
        try {
            val resolver = contentResolver

            // Step 1: remove miro from enabled services, disable accessibility
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                OTHER_SERVICES
            )
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)

            // Step 2: wait for Android to process the removal
            Thread.sleep(2000)

            // Step 3: re-add miro and re-enable accessibility
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "$OTHER_SERVICES:$SERVICE"
            )
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)

            Log.i(TAG, "re-bind triggered via launcher toggle")
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
