package com.miro.a11y

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Auto-rebinds the AccessibilityService after reboot.
 *
 * Problem: on this tablet (OLAX Magic Q1, Android 12, Allwinner build),
 * Android does NOT re-bind accessibility services automatically after boot
 * even though they appear in the "Enabled services" list. The only reliable
 * way to force a re-bind is to toggle the secure settings: remove the service
 * from enabled_accessibility_services, set accessibility_enabled=0, wait,
 * then re-add the service and set accessibility_enabled=1.
 *
 * This requires WRITE_SECURE_SETTINGS, which is granted via ADB at install:
 *   adb shell pm grant com.miro.a11y android.permission.WRITE_SECURE_SETTINGS
 *
 * Without that grant, the receiver logs a warning and does nothing (the user
 * would need to toggle the service manually in Settings > Accessibility).
 */
class MiroBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "miro.boot"
        private const val SERVICE = "com.miro.a11y/com.miro.a11y.MiroAccessibilityService"
        private const val OTHER_SERVICES =
            "bitpit.launcher/bitpit.launcher.lock_screen.LockScreenService:" +
            "io.github.muntashirakon.AppManager/io.github.muntashirakon.AppManager.accessibility.NoRootAccessibilityService"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "boot completed — forcing accessibility service re-bind")

        try {
            // Step 1: remove miro from enabled services, disable accessibility
            val withoutMiro = OTHER_SERVICES
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                withoutMiro
            )
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            )

            // Step 2: wait a moment for Android to process the removal
            Thread.sleep(2000)

            // Step 3: re-add miro and re-enable accessibility
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                "$withoutMiro:$SERVICE"
            )
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )

            Log.i(TAG, "re-bind triggered — miro should bind within a few seconds")
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot write secure settings — WRITE_SECURE_SETTINGS not granted. " +
                    "Run: adb shell pm grant com.miro.a11y android.permission.WRITE_SECURE_SETTINGS")
        } catch (e: Exception) {
            Log.e(TAG, "re-bind failed: ${e.message}")
        }
    }
}
