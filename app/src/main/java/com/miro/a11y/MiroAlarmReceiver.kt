package com.miro.a11y

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Re-enables the accessibility service after boot.
 *
 * On this OLAX/Allwinner ROM the list in ENABLED_ACCESSIBILITY_SERVICES
 * survives reboot, but the system forces ACCESSIBILITY_ENABLED=0 on every
 * boot. The only thing needed after reboot is to flip that flag back to 1.
 *
 * WHY AlarmManager.setAlarmClock(): it is the single mechanism Android
 * guarantees to fire after reboot even under Doze and even on ROMs that
 * block BOOT_COMPLETED delivery to user apps. setAlarmClock() alarms are
 * placed on the system's "alarm clock" whitelist and are delivered by the
 * system AlarmManager itself (not via a boot broadcast the ROM can filter).
 *
 * The receiver re-arms a fresh alarm each time it fires, so it keeps working
 * across reboots without any user interaction.
 */
class MiroAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "miro.alarm"
        private const val REQUEST_CODE = 4242
        private const val INTERVAL_MS = 15 * 60 * 1000L // 15 min safety re-check

        fun schedule(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, MiroAlarmReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Fire ~30s from now (first run after manual launch / post-boot),
            // then every 15 min. setAlarmClock survives Doze + reboot.
            val first = System.currentTimeMillis() + 30_000L
            val info = AlarmManager.AlarmClockInfo(first, pi)
            am.setAlarmClock(info, pi)
            Log.i(TAG, "alarm scheduled (first=$first)")
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "ALARM FIRED — re-enabling accessibility")
        try {
            // The service list persists across reboot; only the enabled flag
            // is forced to 0 by this ROM. Flip it back to 1.
            Settings.Secure.putInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            Log.i(TAG, "accessibility_enabled set to 1")
        } catch (e: SecurityException) {
            Log.w(TAG, "cannot write secure settings — WRITE_SECURE_SETTINGS not granted")
        } catch (e: Exception) {
            Log.e(TAG, "re-enable failed: ${e.message}")
        } finally {
            // Re-arm for next cycle (covers future reboots).
            schedule(context)
        }
    }
}
