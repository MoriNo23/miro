package com.miro.a11y

import android.accessibilityservice.AccessibilityService
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
 *
 * Limitaciones de esta build (user, sin root):
 *   - No WRITE_SECURE_SETTINGS → no puede habilitar servicios de a11y programáticamente
 *   - No puede toggle ADB WiFi (requiere permisos de sistema)
 *   - Auto-arranca en boot tras primer unlock del usuario
 *
 * Control: un socket local abstracto "@miro" permite que la PC envíe comandos
 * JSON (vía `adb forward tcp:1234 localabstract:miro`). Ver MiroSocketServer.
 */
class MiroAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "miro"
    }

    private lateinit var controller: MiroController
    private var socketServer: MiroSocketServer? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "miro accessibility service connected")

        controller = MiroController(this)

        // Start embedded control socket (localhost only, no root needed)
        if (socketServer == null) {
            socketServer = MiroSocketServer(controller) { msg -> Log.d(TAG, msg) }
            socketServer?.start()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val event = event ?: return
        // Lightweight logging; real logic lives in MiroController / socket commands.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "window: ${event.packageName}/${event.className}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "miro accessibility service interrupted")
    }

    override fun onDestroy() {
        socketServer?.stopServer()
        socketServer = null
        super.onDestroy()
    }
}
