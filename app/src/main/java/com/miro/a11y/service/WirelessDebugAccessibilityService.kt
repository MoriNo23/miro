package com.miro.a11y.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.miro.a11y.util.IpPortParser
import com.miro.a11y.util.Logger

/**
 * WirelessDebugAccessibilityService — state machine for automating
 * Wireless Debugging onboarding on the OLAX Magic Q1 (Android 12 / API 31).
 *
 * State machine (high-level):
 *   IDLE → DETECT_WIRELESS_DIALOG → EXTRACT_IP_PORT → SEND_TO_HOST → CONFIRM_TOAST → IDLE
 *
 * Post-boot re-enable is handled by MiroLauncherActivity, which calls
 * enableSelf() via WRITE_SECURE_SETTINGS after the user grants the permission
 * through `adb shell pm grant com.miro.a11y android.permission.WRITE_SECURE_SETTINGS`.
 *
 * TODO: Wire the full state transitions into performGlobalAction / dispatchGesture /
 *       getRootInActiveWindow once the baseline dialog flow is confirmed on-device.
 *       Reference: vault-miro/01-Fundamentos/01-wireless-adb-historia.md
 *       Baseline APK decompile available in apks/miro-baseline.apk (jadx).
 */
class WirelessDebugAccessibilityService : AccessibilityService() {

    // --- State machine ---
    private enum class State {
        IDLE,
        DETECT_WIRELESS_DIALOG,
        EXTRACT_IP_PORT,
        SEND_TO_HOST,
        CONFIRM_TOAST
    }

    private var state: State = State.IDLE

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logger.i("WirelessDebugAccessibilityService connected")
        state = State.IDLE
        // TODO: implement first transition — watch for the wireless debug pairing dialog.
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (state) {
            State.DETECT_WIRELESS_DIALOG -> {
                // TODO: match window title / package for the Wireless Debugging dialog.
                val text = e.text?.joinToString("") { it?.toString().orEmpty() } ?: ""
                val parsed = IpPortParser.parse(text)
                if (parsed != null) {
                    Logger.d("parsed ip:port = ${parsed.ip}:${parsed.port}")
                    // TODO: hand off to SEND_TO_HOST via socket/overlay.
                    state = State.SEND_TO_HOST
                }
            }
            else -> { /* no-op in stub */ }
        }
    }

    override fun onInterrupt() {
        Logger.w("WirelessDebugAccessibilityService interrupted")
    }

    override fun onDestroy() {
        Logger.i("WirelessDebugAccessibilityService destroyed")
        super.onDestroy()
    }
}
