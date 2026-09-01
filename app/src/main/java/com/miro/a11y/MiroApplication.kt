package com.miro.a11y

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * MiroApplication — Application class for com.miro.a11y.
 *
 * Why this exists (v1.4.14):
 *   MiroLauncherActivity uses Theme.NoDisplay so the user doesn't see
 *   a black screen during the a11y toggle. But NoDisplay requires
 *   finish() to be called before the activity becomes visible. The
 *   problem: if the toggle runs inside the activity and the activity
 *   finishes, the process kills the toggle too.
 *
 *   Solution: the toggle runs in the Application context (which
 *   survives the activity). MiroLauncherActivity.onCreate just
 *   schedules the work on MiroApplication.toggleHandler and finishes.
 *
 * Lifecycle:
 *   1. System starts MiroLauncherActivity as HOME.
 *   2. MiroApplication.onCreate() ran earlier (when the process was
 *      created by Android) and initialized toggleHandler.
 *   3. MiroLauncherActivity.onCreate posts runToggleAndHandoff to
 *      toggleHandler and calls finish().
 *   4. runToggleAndHandoff runs the toggle, waits BIND_GRACE_MS, then
 *      launches ESLauncher via the application context.
 *
 * Toggle pattern (carried over from MiroLauncherActivity v1.4.13):
 *   - ACCESSIBILITY_ENABLED 0 → 1 with 2s wait in between
 *   - 3 attempts with 1s backoff
 *   - post-toggle BIND_GRACE_MS to let AccessibilityManagerService
 *     bind the service
 *   - launch ESLauncher via Intent.ACTION_MAIN + CATEGORY_HOME
 */
class MiroApplication : Application() {

    companion object {
        private const val TAG = "miro"
        private const val SERVICE_CANONICAL = "com.miro.a11y/com.miro.a11y.MiroAccessibilityService"

        // Toggle robustness constants (must match MiroLauncherActivity)
        private const val A11Y_TOGGLE_DELAY_MS = 2000L
        private const val VERIFY_DELAY_MS = 500L
        private const val MAX_RETRIES = 3
        private const val BIND_GRACE_MS = 5000L
        private const val REAL_LAUNCHER_PKG = "com.android.launcher3"
        private const val REAL_LAUNCHER_CLS = "com.android.launcher3.ESLauncher"

        @Volatile
        private var toggleHandler: Handler? = null

        @Volatile
        private var appInstance: MiroApplication? = null

        /**
         * Called by MiroLauncherActivity.onCreate to schedule the toggle.
         * Must be called from the main thread.
         */
        @JvmStatic
        fun ensureHandlerInitialized(app: MiroApplication) {
            if (toggleHandler == null) {
                toggleHandler = Handler(Looper.getMainLooper())
                Log.i(TAG, "MiroApplication: toggleHandler initialized")
            }
        }

        /**
         * Run the full toggle sequence + handoff to ESLauncher.
         * Called on the main thread via toggleHandler.post().
         */
        @JvmStatic
        fun runToggleAndHandoff() {
            val app = appInstance ?: run {
                Log.e(TAG, "runToggleAndHandoff: appInstance is null")
                return
            }
            val h = toggleHandler ?: run {
                Log.e(TAG, "runToggleAndHandoff: handler is null")
                return
            }
            Log.i(TAG, "runToggleAndHandoff: starting toggle sequence")
            val ok = app.runToggleSequence(attempt = 1)
            if (ok) {
                Log.i(TAG, "runToggleAndHandoff: toggle verified, waiting BIND_GRACE_MS=$BIND_GRACE_MS ms")
                h.postDelayed({
                    Log.i(TAG, "runToggleAndHandoff: launching real launcher (ESLauncher)")
                    app.launchRealLauncher()
                }, BIND_GRACE_MS)
            } else {
                Log.e(TAG, "runToggleAndHandoff: toggle failed, launching real launcher anyway")
                app.launchRealLauncher()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        appInstance = this
        ensureHandlerInitialized(this)
        // Also wire MiroLauncherActivity's static appHandler reference
        MiroLauncherActivity.appHandler = toggleHandler
        Log.i(TAG, "MiroApplication: onCreate — process started")
    }

    /**
     * Toggle de accesibilidad con 3 reintentos + verificación post-escritura.
     * Same pattern as MiroLauncherActivity v1.4.13, but operating on the
     * Application context (not the Activity context).
     */
    private fun runToggleSequence(attempt: Int): Boolean {
        if (attempt > MAX_RETRIES) {
            Log.e(TAG, "a11y toggle failed after $MAX_RETRIES attempts — manual fix needed")
            return false
        }
        Log.i(TAG, "toggle attempt $attempt starting")
        val ok = attemptToggle(attempt)
        if (ok) {
            Log.i(TAG, "a11y toggle verified on attempt $attempt")
            return true
        }
        Log.w(TAG, "a11y toggle attempt $attempt/$MAX_RETRIES failed — retrying in 1.5s")
        toggleHandler?.postDelayed({ runToggleSequence(attempt + 1) }, 1500L)
        return false
    }

    private fun attemptToggle(attempt: Int): Boolean {
        val cr = contentResolver

        // v1.4.14: Before toggling ACCESSIBILITY_ENABLED, re-ensure
        // that our service is in ENABLED_ACCESSIBILITY_SERVICES.
        // This is a defensive fix for the case where the system
        // removed our service from the list (e.g. after force-stop,
        // reinstall, or a write from another app). The OLAX ROM is
        // particularly aggressive about this.
        try {
            ensureServiceInList()
        } catch (e: SecurityException) {
            Log.e(TAG, "[$attempt] ensureServiceInList failed: ${e.message}")
            return false
        }

        // Strategy: do NOT remove the service from the list. Just
        // toggle ACCESSIBILITY_ENABLED 0 → 1. This forces the
        // AccessibilityManagerService to:
        //   1. Unbind the service (flag = 0)
        //   2. Re-discover enabled services (still has our entry)
        //   3. Re-bind the service (flag = 1)

        try {
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        } catch (e: SecurityException) {
            Log.e(TAG, "[$attempt] putInt flag=0 failed: ${e.message}")
            return false
        }
        try { Thread.sleep(VERIFY_DELAY_MS) } catch (e: InterruptedException) { return false }
        if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, -1) != 0) {
            Log.w(TAG, "[$attempt] flag 0 verification failed")
            return false
        }

        try { Thread.sleep(A11Y_TOGGLE_DELAY_MS) } catch (e: InterruptedException) { return false }

        try {
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } catch (e: SecurityException) {
            Log.e(TAG, "[$attempt] putInt flag=1 failed: ${e.message}")
            return false
        }
        try { Thread.sleep(VERIFY_DELAY_MS) } catch (e: InterruptedException) { return false }
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
            Log.i(TAG, "launched real launcher (ESLauncher)")
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

    /**
     * Re-add our accessibility service to ENABLED_ACCESSIBILITY_SERVICES
     * if it is missing. This handles the OLAX ROM's tendency to silently
     * drop the entry from the list on force-stop, reinstall, or other
     * system events.
     *
     * The pattern (handoff 2026-09-01):
     *   1. Read the current list
     *   2. Split by ":"
     *   3. Filter to remove any duplicates of our service (any format)
     *   4. If our canonical form is not present, add it at the front
     *   5. Write back the joined list
     *   6. Preserve any other services (AppManager, etc.) — never
     *      hardcode their presence/absence
     */
    private fun ensureServiceInList() {
        val cr = contentResolver
        val current = Settings.Secure.getString(
            cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val list = current.split(":").filter { it.isNotBlank() }
        // Filter out any duplicates of our service (in any format)
        val filtered = list.filter { !it.contains("com.miro.a11y") }
        val newList = if (filtered.isEmpty()) {
            listOf(SERVICE_CANONICAL)
        } else {
            listOf(SERVICE_CANONICAL) + filtered
        }
        val newValue = newList.joinToString(":")
        if (newValue != current) {
            Settings.Secure.putString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newValue
            )
            Log.i(TAG, "ensureServiceInList: '$current' → '$newValue'")
        } else {
            Log.i(TAG, "ensureServiceInList: no change, already in list")
        }
    }
}
