package com.miro.a11y

import android.accessibilityservice.AccessibilityService
import android.content.ContentResolver
import android.database.ContentObserver
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * RotationWatchdog — disable the "Girar pantalla automáticamente" QS tile
 * every time it gets turned ON by something other than the user.
 *
 * Motivation (2026-09-01, OLAX Magic Q1 / Android 12): the ROM keeps
 * flipping `Settings.System.accelerometer_rotation` back to 1 (auto-rotate
 * ON), which the user finds annoying. They want a guard that, whenever
 * that setting becomes 1, opens the QS panel, finds the Switch by its
 * content-desc, and taps it back to OFF.
 *
 * The watchdog only acts on the `onChange(false)` callback (the second
 * arg of `ContentObserver.onChange(selfChange, uri)`); we ignore
 * notifications that we ourselves triggered (within the cooldown window)
 * to avoid an infinite tap-toggle loop.
 *
 * IMPORTANT: this watchdog does NOT require WRITE_SECURE_SETTINGS.
 * It only needs the MiroAccessibilityService to be bound (so we can
 * perform tap / swipe on the QS). Tapping the Switch on the QS panel
 * is a UI action that Android allows from any accessibility service.
 */
class RotationWatchdog(
    private val service: MiroAccessibilityService,
    private val controller: MiroController,
    private val onLog: (String) -> Unit
) {
    companion object {
        private const val TAG = "miro"
        // After the watchdog taps the Switch OFF, ignore any subsequent
        // accelerometer_rotation=1 changes for COOLDOWN_MS. Without this,
        // Android could re-fire the change callback while the Switch
        // is still settling, and we'd tap it back ON → OFF again.
        private const val COOLDOWN_MS = 5_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val resolver: ContentResolver = service.contentResolver
    private var registered = false
    @Volatile private var lastAutoActionMs: Long = 0L

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            // Newer API: onChange(selfChange, uri) — handled below.
        }

        override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
            handleChange(selfChange, uri)
        }
    }

    private fun handleChange(selfChange: Boolean, uri: android.net.Uri?) {
        if (uri != null && uri != Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION)) {
            // Different setting changed — ignore.
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastAutoActionMs < COOLDOWN_MS) {
            // We just toggled the tile ourselves. Don't react.
            return
        }
        val current = try {
            Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION)
        } catch (e: Exception) {
            Log.w(TAG, "rotation watchdog: could not read accelerometer_rotation: ${e.message}")
            return
        }
        if (current != 1) {
            // Already OFF — nothing to do.
            return
        }
        onLog("rotation watchdog: accelerometer_rotation=1 detected — disabling QS tile")
        lastAutoActionMs = now
        // Open QS, find the Switch, tap it OFF. Run on a delay so the
        // QS panel finishes animating before we try to find the node.
        handler.postDelayed({ disableAutoRotateTile() }, 800L)
    }

    fun register() {
        if (registered) return
        resolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.ACCELEROMETER_ROTATION),
            /* notifyForDescendants = */ false,
            observer
        )
        registered = true
        onLog("rotation watchdog: registered (observes accelerometer_rotation)")
    }

    fun unregister() {
        if (!registered) return
        try {
            resolver.unregisterContentObserver(observer)
        } catch (e: Exception) {
            Log.w(TAG, "rotation watchdog: unregister threw ${e.message}")
        }
        registered = false
        onLog("rotation watchdog: unregistered")
    }

    /**
     * Open the QS panel, find the "Girar pantalla automáticamente" Switch,
     * and tap it. Uses the same OLAX-QS find-by-content-desc path as the
     * wireless debug automator.
     *
     * Strategy:
     *  1. Open QS via performGlobalAction(QUICK_SETTINGS). If the panel
     *     opens in "partial" mode, do an extra swipe-down to expand it
     *     to the full grid (the "Girar pantalla automáticamente" tile
     *     is only visible in the full grid on the OLAX Q1).
     *  2. Wait 1500ms for the panel to settle.
     *  3. Find the Switch by content-desc. Tap the center of its bounds.
     *  4. If not found, give up gracefully (log) and let the next
     *     accelerometer_rotation=1 change retry the flow.
     */
    private fun disableAutoRotateTile() {
        try {
            // Step 1: open QS (may need a second swipe to fully expand).
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            handler.postDelayed({ expandQsIfNeeded() }, 600L)
        } catch (e: Exception) {
            onLog("rotation watchdog ERROR: open QS failed: ${e.message}")
        }
    }

    private fun expandQsIfNeeded() {
        try {
            // Try to swipe down on the QS header to expand from partial
            // to full. The OLAX Q1 has the QS header around y=40..140 in
            // partial mode and y=40..90 in full mode.
            controller.swipe(512f, 80f, 512f, 600f, 250L)
        } catch (_: Exception) {}
        handler.postDelayed({ findAndTapRotationSwitch() }, 1500L)
    }

    private fun findAndTapRotationSwitch() {
        val descs = listOf(
            "Girar pantalla automáticamente",
            "Girar automáticamente",
            "Auto-rotate screen",
            "Auto-rotate",
            "Rotación automática de pantalla"
        )
        for (desc in descs) {
            val node = controller.findNode(desc) ?: continue
            val b = Rect()
            node.getBoundsInScreen(b)
            // Make sure the bounds are sane (not zero, not full-screen).
            if (b.width() < 50 || b.height() < 20) continue
            val cx = ((b.left + b.right) / 2).toFloat()
            val cy = ((b.top + b.bottom) / 2).toFloat()
            val ok = controller.tap(cx, cy)
            onLog("rotation watchdog: tapped '$desc' at ($cx, $cy) → ok=$ok")
            return
        }
        onLog("rotation watchdog ERROR: 'Girar pantalla automáticamente' tile not found in QS")
    }
}
