package com.miro.a11y

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.work.Configuration
import java.util.concurrent.TimeUnit

/**
 * Persistent JobScheduler that force-rebinds the AccessibilityService after boot.
 *
 * WHY THIS EXISTS:
 * On the OLAX Magic Q1 (Android 12, Allwinner build), Android does NOT deliver
 * BOOT_COMPLETED (or LOCKED_BOOT_COMPLETED, QUICKBOOT_POWERON, USER_PRESENT,
 * SCREEN_ON) to user-installed apps. The BroadcastReceiver approach fails because
 * the broadcast never reaches us.
 *
 * JobScheduler jobs scheduled with setPersisted(true) are managed by the SYSTEM
 * (not by a broadcast to our app). The system_job_scheduler re-enqueues persisted
 * jobs after boot and executes them in a system-owned process context — this does
 * NOT require the app to receive any boot broadcast. This is the standard,
 * supported way to run code after reboot without BOOT_COMPLETED permission
 * (though RECEIVE_BOOT_COMPLETED is still declared for correctness).
 *
 * The job performs the same secure-settings toggle that the launcher activity
 * does: remove miro + others, disable a11y, wait, re-add miro + others, enable.
 * Requires WRITE_SECURE_SETTINGS (granted via ADB at install).
 */
class MiroRebindJobService : android.app.job.JobService() {

    companion object {
        private const val TAG = "miro.job"
        private const val JOB_ID = 1001
        private const val SERVICE = "com.miro.a11y/com.miro.a11y.MiroAccessibilityService"
        private const val OTHER_SERVICES =
            "bitpit.launcher/bitpit.launcher.lock_screen.LockScreenService:" +
            "io.github.muntashirakon.AppManager/io.github.muntashirakon.AppManager.accessibility.NoRootAccessibilityService"

        /** Schedule a persisted periodic + boot job. */
        fun schedule(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val component = ComponentName(context, MiroRebindJobService::class.java)
            val info = JobInfo.Builder(JOB_ID, component)
                // Persist across reboots — system re-enqueues after boot.
                .setPersisted(true)
                // Run shortly after boot (no network, no charging constraints).
                .setMinimumLatency(TimeUnit.SECONDS.toMillis(3))
                .setOverrideDeadline(TimeUnit.SECONDS.toMillis(30))
                // Periodic safety net: re-check every 15 min in case service drops.
                .setPeriodic(TimeUnit.MINUTES.toMillis(15))
                .setRequiresCharging(false)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
                .build()
            val result = js.schedule(info)
            Log.i(TAG, "job scheduled: $result (SUCCESS=${JobScheduler.RESULT_SUCCESS})")
        }

        fun isScheduled(context: Context): Boolean {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            return js.getPendingJob(JOB_ID) != null
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Log.i(TAG, "JOB STARTED — forcing accessibility service re-bind")
        Thread {
            try {
                val resolver = applicationContext.contentResolver

                // Step 1: remove miro, disable accessibility
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    OTHER_SERVICES
                )
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)

                // Step 2: wait for Android to process removal
                Thread.sleep(2000)

                // Step 3: re-add miro, re-enable
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    "$OTHER_SERVICES:$SERVICE"
                )
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)

                Log.i(TAG, "re-bind triggered via job scheduler")
            } catch (e: SecurityException) {
                Log.w(TAG, "cannot write secure settings — WRITE_SECURE_SETTINGS not granted")
            } catch (e: Exception) {
                Log.e(TAG, "re-bind failed: ${e.message}")
            } finally {
                jobFinished(params, false)
            }
        }.start()
        return true // we handle completion in the thread
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // Reschedule if stopped prematurely.
        return true
    }
}
