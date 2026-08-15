package com.miro.a11y

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Auto-rebinds the AccessibilityService after reboot / screen on / user present.
 *
 * Problem: on this tablet (OLAX Magic Q1, Android 12, Allwinner build),
 * Android does NOT re-bind accessibility services automatically after boot
 * even though they appear in the "Enabled services" list. The only reliable
 * way to force a re-bind is to toggle the secure settings: remove the service
 * from enabled_accessibility_services, set accessibility_enabled=0, wait,
 * then re-add the service and set accessibility_enabled=1.
 *
 * This receiver listens to ALL possible boot/wake broadcasts because this
 * tablet may not deliver BOOT_COMPLETED to non-system apps:
 *   - BOOT_COMPLETED          (standard, may not fire on stopped apps)
 *   - LOCKED_BOOT_COMPLETED   (fires before user unlock — direct-boot)
 *   - QUICKBOOT_POWERON       (Allwinner/mediatek quick boot)
 *   - USER_PRESENT            (user unlocks the device)
 *   - SCREEN_ON              (screen turns on — butter-thief pattern)
 *
 * Requires WRITE_SECURE_SETTINGS, granted via ADB at install:
 *   adb shell pm grant com.miro.a11y android.permission.WRITE_SECURE_SETTINGS
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
        val action = intent.action
        Log.i(TAG, "received broadcast: $action — forcing accessibility service re-bind")

        try {
            val resolver = context.contentResolver

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

            Log.i(TAG, "re-bind triggered via $action")
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot write secure settings — WRITE_SECURE_SETTINGS not granted. " +
                    "Run: adb shell pm grant com.miro.a11y android.permission.WRITE_SECURE_SETTINGS")
        } catch (e: Exception) {
            Log.e(TAG, "re-bind failed: ${e.message}")
        }
    }
}
