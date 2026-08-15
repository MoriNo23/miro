package com.miro.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * miro — AccessibilityService for the OLAX Magic Q1 tablet.
 *
 * Capabilities available on this device (Android 12 / API 31):
 *   - performGlobalAction: BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS,
 *     POWER_DIALOG, TOGGLE_SPLIT_SCREEN, LOCK_SCREEN, TAKE_SCREENSHOT
 *   - dispatchGesture: simular taps y gestos táctiles
 *   - getRootInActiveWindow: leer el árbol de ventanas y contenido
 *   - ScreenshotResult: capturas de pantalla reales
 *
 * Limitaciones de esta build (user, sin root):
 *   - No WRITE_SECURE_SETTINGS → no puede habilitar servicios de a11y programáticamente
 *   - No puede toggle ADB WiFi (requiere permisos de sistema)
 *   - Auto-arranca en boot tras primer unlock del usuario
 */
class MiroAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "miro"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "miro accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val event = event ?: return
        val pkg = event.packageName?.toString() ?: "unknown"
        val type = AccessibilityEvent.eventTypeToString(event.eventType)
        Log.d(TAG, "event: type=$type pkg=$pkg class=${event.className}")
    }

    override fun onInterrupt() {
        Log.w(TAG, "miro accessibility service interrupted")
    }

    /**
     * Simula un tap en coordenadas (x, y) usando dispatchGesture.
     */
    fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gesture: GestureDescription?) {
                Log.d(TAG, "tap completed at ($x, $y)")
            }

            override fun onCancelled(gesture: GestureDescription?) {
                Log.w(TAG, "tap cancelled at ($x, $y)")
            }
        }, null)
    }

    /**
     * Ejecuta una acción global (BACK, HOME, RECENTS, etc.)
     */
    fun globalAction(action: Int): Boolean {
        return performGlobalAction(action)
    }
}
