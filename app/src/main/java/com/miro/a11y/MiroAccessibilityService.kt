package com.miro.a11y

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.miro.a11y.util.IpPortParser
import org.json.JSONObject

/**
 * miro — AccessibilityService for the OLAX Magic Q1 tablet.
 *
 * Capabilities available on this device (Android 12 / API 31):
 *   - performGlobalAction: BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, ...
 *   - dispatchGesture: simular taps y gestos táctiles (via MiroController.tap)
 *   - getRootInActiveWindow: leer el árbol de ventanas y contenido
 *
 * Limitaciones (user, sin root):
 *   - No WRITE_SECURE_SETTINGS → no puede habilitar servicios de a11y
 *   - No puede toggle ADB WiFi por sí mismo (requiere permisos de sistema)
 *   - Auto-arranca en boot tras el primer unlock del usuario
 *
 * Control: socket local abstracto "@miro" (adb forward tcp:PORT localabstract:miro).
 * Ver MiroSocketServer — protocolo JSON de un objeto por línea.
 *
 * Issue 1 (audit 2026-09-01): el state machine de Wireless Debugging está
 * integrado AQUÍ (no como service duplicado) vía WirelessDebugAutomator.
 */
class MiroAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "miro"
        // Auto-trigger the Wireless Debugging flow after the service connects.
        // OLAX Magic Q1 path (verified 2026-09-01 via uiautomator dump): the
        // Quick Settings tile grid contains a "Depuración inalámbrica" tile
        // that opens the Wireless Debugging screen directly. The automator
        // taps that tile and reads the ip:port — no need to navigate via
        // Settings → Developer Options → Wireless Debugging.
        //
        // The flow runs 8s after onServiceConnected() so the user can see
        // the QS open and (if needed) intervene. It is a no-op if
        // adb_wifi_enabled is already 1.
        private const val kAutoStartWirelessDebug = true
        private const val AUTO_START_DELAY_MS = 8_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var controller: MiroController
    private var socketServer: MiroSocketServer? = null
    private var wirelessAutomator: WirelessDebugAutomator? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "miro accessibility service connected")

        controller = MiroController(this)

        // Start embedded control socket (localhost only, no root needed)
        if (socketServer == null) {
            socketServer = MiroSocketServer(controller) { msg -> Log.d(TAG, msg) }
            socketServer?.start()
        }

        // State machine for Wireless Debugging onboarding.
        // Triggered either by socket command {"action":"start_wireless_debug"}
        // or auto-triggered post-connect (8s delay) when kAutoStartWirelessDebug
        // is true. The OLAX path taps the "Depuración inalámbrica" QS tile
        // directly — see start() inside WirelessDebugAutomator.
        wirelessAutomator = WirelessDebugAutomator(this, controller) { msg -> Log.d(TAG, msg) }

        if (kAutoStartWirelessDebug) {
            mainHandler.postDelayed({
                if (isWirelessDebugAlreadyOn()) {
                    Log.i(TAG, "auto-start skipped: adb_wifi_enabled already 1")
                    return@postDelayed
                }
                Log.i(TAG, "auto-start: triggering OLAX QS-tile wireless debug flow")
                startWirelessDebug()
            }, AUTO_START_DELAY_MS)
        }
    }

    private fun isWirelessDebugAlreadyOn(): Boolean {
        return try {
            android.provider.Settings.Global.getInt(
                contentResolver, "adb_wifi_enabled"
            ) == 1
        } catch (e: Exception) {
            false
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
        Log.w(TAG, "onInterrupt — cleaning up wireless automator")
        wirelessAutomator?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "miro accessibility service destroyed")
        wirelessAutomator?.stop()
        socketServer?.stopServer()
    }

    /**
     * Start (or restart) the Wireless Debugging onboarding flow.
     * Triggered from the socket command handler via MiroController.
     * Returns true if the automator was initialized and started.
     */
    fun startWirelessDebug(): Boolean {
        val automator = wirelessAutomator ?: run {
            Log.w(TAG, "startWirelessDebug: no automator (service not fully connected)")
            return false
        }
        automator.start()
        return true
    }
}

/**
 * WirelessDebugAutomator — state machine implementado DIRECTAMENTE sobre
 * AccessibilityService + MiroController. Reemplaza al stub eliminado
 * (service/WirelessDebugAccessibilityService.kt).
 *
 * Flow real (audit 2026-09-01, handoff 2026-09-01-fusion-wireless tarea 4):
 *   IDLE → OPENING_DEV_OPTIONS → CLICKING_WIRELESS_DEBUG → EXTRACTING_IP_PORT → SENDING_TO_PC → DONE
 *
 * Usa:
 *   - performGlobalAction() vía controller.globalAction() para QUICK_SETTINGS
 *   - dispatchGesture() vía controller.tap() / tapByText() para clicks reales
 *   - getRootInActiveWindow() vía controller.dumpScreen() / findNode() para extraer IP:port
 *   - socket @miro vía onLog + un JSONObject de respuesta para handoff al host PC
 *
 * NOTA: el primer click abre Quick Settings; la segunda navega a Settings →
 * Developer Options → Wireless Debugging. La ROM OLAX expone "Wireless Debugging"
 * como opcion con texto estable. Si el texto difiere, el state machine reintenta
 * con variantes ("Ajustes", "Settings", "Developer options", "Opciones de desarrollador").
 */
class WirelessDebugAutomator(
    private val service: AccessibilityService,
    private val controller: MiroController,
    private val onLog: (String) -> Unit
) {

    enum class State {
        IDLE, OPENING_DEV_OPTIONS, CLICKING_WIRELESS_DEBUG, EXTRACTING_IP_PORT, SENDING_TO_PC, DONE
    }

    private var state: State = State.IDLE
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    /** Search terms used for tapByText — covers locale + English. */
    private val settingsTerms = listOf("Settings", "Ajustes", "settings")
    private val devOptionsTerms = listOf("Developer options", "Opciones de desarrollador",
        "Developer", "opciones de desarrollador")
    private val wirelessDebugTerms = listOf("Wireless Debugging", "Depuración inalámbrica",
        "Wireless debug", "wireless debugging", "Depuración inalámbrica")

    fun start() {
        if (running) {
            onLog("wireless debug: already running, ignoring start")
            return
        }
        running = true
        state = State.OPENING_DEV_OPTIONS
        onLog("wireless debug: state=${state.name}")

        // OLAX Magic Q1 path (verified 2026-09-01): the Quick Settings tile
        // grid already contains a "Depuración inalámbrica" tile that opens
        // the Wireless Debugging screen directly. No need to navigate via
        // Settings → Developer Options → Wireless Debugging.
        //
        // The stock Android path (Settings → Dev Options → ...) doesn't
        // work reliably because the OLAX ROM's NotificationShade is a
        // tile-only view (no Settings gear in the standard position, and
        // the global action may not open the full Settings app), so the
        // old step2OpenSettings() / step3OpenDevOptions() flow got stuck.
        //
        // Steps:
        //   1. Open the Quick Settings / NotificationShade
        //   2. Tap the "Depuración inalámbrica" tile directly
        //   3. Read the Wireless Debugging screen, extract ip:port
        //   4. Send to PC
        val ok = controller.globalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        if (!ok) {
            onLog("wireless debug: QUICK_SETTINGS failed")
        }
        handler.postDelayed({ step2TapWirelessDebugTile() }, 1500)
    }

    /** Step 2 (OLAX path): tap the "Depuración inalámbrica" tile in QS. */
    private fun step2TapWirelessDebugTile() {
        if (!running) return
        state = State.CLICKING_WIRELESS_DEBUG
        onLog("wireless debug: state=${state.name} — tapping Wireless Debug tile in QS")

        // Match the localized tile label. The dump shows it as
        // "Depuración inalámbrica" (text) with content-desc same.
        val tileTerms = listOf("Depuración inalámbrica", "Wireless Debugging",
            "Wireless debug", "wireless debugging")
        var ok = tapByTextMulti(tileTerms, fallback = true)
        if (!ok) {
            onLog("wireless debug: tile not found in QS — bailing out")
            stopWithError("wireless debug tile not found in QS")
            return
        }
        handler.postDelayed({ step3ExtractIpPort() }, 2500)
    }

    /** Step 3 (OLAX path): the Wireless Debugging screen is up — read ip:port. */
    private fun step3ExtractIpPort() {
        if (!running) return
        state = State.EXTRACTING_IP_PORT
        onLog("wireless debug: state=${state.name} — extracting ip:port from screen")

        val parsed = findIpPortInTree(maxAttempts = 5, delayMs = 1000)
        if (parsed == null) {
            // If the tile only TOGGLED Wireless Debugging on/off, we may be
            // back on the QS now. Re-open QS and try again with the
            // assumption that we need to tap the tile text on the
            // "Wireless Debugging" detail page (some ROMs show ip:port
            // only when the toggle has been on for a few seconds).
            onLog("wireless debug: ip:port not in tree — re-trying after re-open")
            handler.postDelayed({
                if (!running) return@postDelayed
                val ok = controller.globalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
                if (!ok) onLog("wireless debug: re-open QS failed")
                handler.postDelayed({
                    if (!running) return@postDelayed
                    val tileTerms = listOf("Depuración inalámbrica", "Wireless Debugging")
                    tapByTextMulti(tileTerms, fallback = true)
                    handler.postDelayed({
                        val retry = findIpPortInTree(maxAttempts = 5, delayMs = 1000)
                        if (retry == null) {
                            stopWithError("could not extract ip:port after 2 attempts")
                        } else {
                            step4SendToHost(retry)
                        }
                    }, 2500)
                }, 1500)
            }, 1500)
            return
        }
        step4SendToHost(parsed)
    }

    /** Step 4: send the ip:port to the host via logcat. */
    private fun step4SendToHost(result: IpPortParser.Result) {
        if (!running) return
        state = State.SENDING_TO_PC
        onLog("wireless debug: state=${state.name} — sending ${result.ip}:${result.port} to host")
        onLog("WIFI_DEBUG_RESULT ${result.ip}:${result.port} port=${result.port}")
        state = State.DONE
        onLog("wireless debug: DONE")
        stop()
    }

    /**
     * Search the accessibility window tree for text matching IP:port pattern.
     * Re-reads the tree every [delayMs] up to [maxAttempts].
     */
    private fun findIpPortInTree(maxAttempts: Int, delayMs: Long): IpPortParser.Result? {
        var attempts = 0
        while (attempts < maxAttempts) {
            val tree = controller.dumpScreen() ?: run {
                Thread.sleep(delayMs)
                attempts++
                continue
            }
            val text = tree.toString()
            val parsed = IpPortParser.parse(text)
            if (parsed != null) return parsed
            Thread.sleep(delayMs)
            attempts++
        }
        return null
    }

    /** Tap the first node whose text/desc contains any of [terms]. */
    private fun tapByTextMulti(terms: List<String>, fallback: Boolean = false, retries: Int = 1, delayMs: Long = 0): Boolean {
        var attempt = 0
        while (attempt <= retries) {
            for (term in terms) {
                if (controller.tapByText(term)) {
                    return true
                }
            }
            if (fallback && attempt == 0) {
                // Fallback: tap center of screen (generic "OK" button position)
                controller.tap(540f, 1000f)
            }
            if (attempt < retries) {
                if (delayMs > 0) Thread.sleep(delayMs)
            }
            attempt++
        }
        return false
    }

    private fun onError(msg: String) {
        onLog("wireless debug ERROR: $msg")
        state = State.IDLE
        stop()
    }

    private fun stopWithError(msg: String) {
        onLog("wireless debug ERROR: $msg")
        state = State.IDLE
        stop()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        onLog("wireless debug: stopped (state=$state)")
    }
}

/**
 * Bridge: allow the socket command handler to trigger the wireless debug flow.
 * MiroSocketServer no conoce WirelessDebugAutomator directamente, pero
 * MiroController puede exponer un callback. Aquí usamos un simple método
 * en el service que el controller llama si recibe la acción "start_wireless_debug".
 */
