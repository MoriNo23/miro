package com.miro.a11y

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * WorkManager worker that re-enables the accessibility service after boot.
 *
 * DIAGNOSIS: On this OLAX/Allwinner ROM, the system DOES deliver
 * BOOT_COMPLETED to apps that have an initialized WorkManager (via AndroidX's
 * RescheduleReceiver — confirmed in logcat: Assistant, Kids Home, Photos Go,
 * YouTube Music, Contacts, and the bitpit launcher all receive it). Plain
 * BroadcastReceivers, JobScheduler persisted jobs, and AlarmManager alarms are
 * all blocked / cleared on reboot, but WorkManager survives.
 *
 * After reboot the ROM keeps ENABLED_ACCESSIBILITY_SERVICES intact but forces
 * ACCESSIBILITY_ENABLED=0. This worker flips that single flag back to 1.
 */
class MiroReenableWorker(context: Context, params: WorkerParameters) :
    Worker(context, params) {

    companion object {
        private const val TAG = "miro.work"
        private const val UNIQUE_PERIODIC = "miro-reenable-periodic"

        /** Schedule a periodic worker (15 min) + an immediate one-time worker. */
        fun schedule(context: Context) {
            val periodic = PeriodicWorkRequestBuilder<MiroReenableWorker>(
                15, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
                periodic
            )
            // Immediate run for the next boot window.
            val immediate = OneTimeWorkRequestBuilder<MiroReenableWorker>().build()
            WorkManager.getInstance(context).enqueue(immediate)
        }
    }

    override fun doWork(): Result {
        return try {
            val resolver: ContentResolver = applicationContext.contentResolver
            // Service list persists across reboot; only the enabled flag is
            // forced to 0 by this ROM. Flip it back.
            Settings.Secure.putInt(
                resolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                1
            )
            android.util.Log.i(TAG, "accessibility_enabled set to 1 via WorkManager")
            Result.success()
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "WRITE_SECURE_SETTINGS not granted: ${e.message}")
            Result.retry()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "re-enable failed: ${e.message}")
            Result.retry()
        }
    }
}
