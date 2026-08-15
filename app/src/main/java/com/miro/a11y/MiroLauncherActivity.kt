package com.miro.a11y

import android.app.Activity
import android.os.Bundle
import android.provider.Settings
import android.util.Log

/**
 * Launcher activity. Two purposes:
 * 1. Takes the app out of "stopped" state so the AccessibilityService can bind.
 * 2. Forces a11y service re-bind by toggling secure settings (same logic as
 *    MiroBootReceiver — needed because this OLAX/Allwinner tablet does not
 *    re-bind accessibility services automatically after boot).
 *
 * Requires WRITE_SECURE_SETTINGS, granted via:
 *   adb shell pm grant com.miro.a11y android.permission.WRITE_SECURE_SETTINGS
 */
class MiroLauncherActivity : Activity() {

    companion object {
        private const val TAG = "miro"
        private const val SERVICE = "com.miro.a11y/com.miro.a11y.MiroAccessibilityService"
        private const val OTHER_SERVICES =
            "bitpit.launcher/bitpit.launcher.lock_screen.LockScreenService:" +
            "io.github.muntashirakon.AppManager/io.github.muntashirakon.AppManager.accessibility.NoRootAccessibilityService"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "launcher activity — app taken out of stopped state")

        // Force re-bind of the accessibility service via settings toggle
        forceRebind()

        finish()
    }

    private fun forceRebind() {
        try {
            val resolver = contentResolver

            // Step 1: remove miro from enabled services, disable accessibility
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                OTHER_SERVICES
            )
            Settings.Secure.putInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            )

            // Step 2: wait for Android to process the removal
            Thread.sleep(2000)

            // Step 3: re-add miro and re-enable accessibility
            Settings.Secure.putString(
                resolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "$OTHER_SERVICES:$SERVICE"
            )
            Settings.Secure.putInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )

            Log.i(TAG, "re-bind triggered via launcher activity")
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot write secure settings — WRITE_SECURE_SETTINGS not granted. " +
                    "Run: adb shell pm grant com.miro.a11y android.permission.WRITE_SECURE_SETTINGS")
        } catch (e: Exception) {
            Log.e(TAG, "re-bind failed: ${e.message}")
        }
    }
}
