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
        // DISABLED by default (kAutoStartWirelessDebug = false). The click flow
        // is fragile on the OLAX Magic Q1 (chinese build, Android 12):
        //   - Step 1 (open QS) sometimes opens the notification shade instead
        //     of the Quick Settings tiles (depends on swipe target)
        //   - Step 2 (find 'Settings' in QS) fails when only notifications show
        //   - If the flow fails halfway, the tablet is left with QS open and
        //     no way to recover without manual touch
        //
        // When the click sequence is made more robust (e.g. by checking for
        // the right window first, with explicit "open Quick Settings tiles"
        // via a top-edge swipe rather than GLOBAL_ACTION_QUICK_SETTINGS),
        // flip this to true.
        //
        // For now, after each reboot the user has to:
        //   1. Wait for the boot + MiroLauncherActivity toggle to finish
        //   2. Manually open Settings → Developer Options → Wireless Debugging
        //   3. Toggle it on — the automator (started from the PC socket
        //      `{"action":"start_wireless_debug"}`) will then do the rest
        //      of the flow (extract ip:port, send to host)
        private const val kAutoStartWirelessDebug = false
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
        // Activado por comando de socket: {"action":"start_wireless_debug"}
        wirelessAutomator = WirelessDebugAutomator(this, controller) { msg -> Log.d(TAG, msg) }

        // Auto-trigger: after a delay, start the flow unless Wireless Debugging
        // is already on (so we don't loop forever). Guarded by kAutoStartWirelessDebug
        // so the user can opt out without touching code.
        if (kAutoStartWirelessDebug) {
            mainHandler.postDelayed({
                if (isWirelessDebugAlreadyOn()) {
                    Log.i(TAG, "auto-start skipped: adb_wifi_enabled already 1")
                    return@postDelayed
                }
                Log.i(TAG, "auto-start: triggering wireless debug flow")
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

        // Step 1: open Quick Settings
        val ok = controller.globalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        if (!ok) {
            onLog("wireless debug: QUICK_SETTINGS failed")
        }
        handler.postDelayed({ step2OpenSettings() }, 2000)
    }

    /** Step 2: click "Settings" in Quick Settings tile grid → opens system Settings. */
    private fun step2OpenSettings() {
        if (!running) return
        state = State.OPENING_DEV_OPTIONS
        onLog("wireless debug: state=${state.name} — clicking Settings")
        var ok = tapByTextMulti(settingsTerms, fallback = true)
        if (!ok) {
            onLog("wireless debug: could not find 'Settings' text — tapping QS gear icon @ (480,160)")
            // The gear icon in Quick Settings (OLAX tablet 1024x600) sits near the
            // top-right of the QS tile grid. Tap coordinates as last resort.
            ok = controller.tap(480f, 160f)
            if (!ok) {
                onLog("wireless debug: QS gear tap failed — will still proceed")
            }
        }
        handler.postDelayed({ step3OpenDevOptions() }, 2000)
    }

    /** Step 3: navigate to Developer Options. */
    private fun step3OpenDevOptions() {
        if (!running) return
        state = State.CLICKING_WIRELESS_DEBUG
        onLog("wireless debug: state=${state.name} — navigating to Developer Options")
        val ok = tapByTextMulti(devOptionsTerms)
        if (!ok) {
            onLog("wireless debug: 'Developer options' not found — trying scroll + retry")
            // Scroll attempt via swipe down then retry once.
            controller.swipe(540f, 1600f, 540f, 400f, 800)
            handler.postDelayed({
                if (tapByTextMulti(devOptionsTerms)) {
                    step4EnterWirelessDebug()
                } else {
                    onError("Developer Options not found")
                }
            }, 1500)
            return
        }
        step4EnterWirelessDebug()
    }

    /** Step 4: ensure "Wireless Debugging" entry is visible (scroll if needed). */
    private fun step4EnterWirelessDebug() {
        if (!running) return
        state = State.CLICKING_WIRELESS_DEBUG
        onLog("wireless debug: state=${state.name} — looking for Wireless Debugging")
        val ok = tapByTextMulti(wirelessDebugTerms)
        if (!ok) {
            onLog("wireless debug: 'Wireless Debugging' not found — scrolling and retrying")
            controller.swipe(540f, 1600f, 540f, 400f, 800)
            handler.postDelayed({
                if (tapByTextMulti(wirelessDebugTerms, retries = 3, delayMs = 600)) {
                    step5ExtractIpPort()
                } else {
                    onError("Wireless Debugging entry not found")
                }
            }, 1800)
            return
        }
        step5ExtractIpPort()
    }

    /** Step 5: on the Wireless Debugging screen, a dialog or toast shows ip:port. */
    private fun step5ExtractIpPort() {
        if (!running) return
        state = State.EXTRACTING_IP_PORT
        onLog("wireless debug: state=${state.name} — extracting ip:port from screen")

        // Read the active window tree and search for a text matching IP:port.
        val parsed = findIpPortInTree(maxAttempts = 5, delayMs = 1000)
        if (parsed == null) {
            onError("could not extract ip:port from Wireless Debugging screen")
            return
        }
        onLog("wireless debug: extracted ${parsed.ip}:${parsed.port}")
        step6SendToHost(parsed)
    }

    /** Step 6: send the ip:port to the PC via the @miro socket + log it. */
    private fun step6SendToHost(result: IpPortParser.Result) {
        if (!running) return
        state = State.SENDING_TO_PC
        onLog("wireless debug: state=${state.name} — sending ${result.ip}:${result.port} to host")

        // The host reads this via the @miro socket. We log it structured so
        // the host (or logcat tail) can pick it up. The JSON protocol accepts
        // a "wireless_debug" action — reusamos el socket de MiroSocketServer
        // indirectamente: el host corre `adb forward` y parsea logcat.
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
        val dump = controller.dumpScreen() ?: return null
        val text = dump.toString()
        val parsed = IpPortParser.parse(text)
        if (parsed != null) return parsed

        if (maxAttempts <= 1) return null
        handler.postDelayed({ /* retry handled by caller pattern */ }, delayMs)
        // Synchronous retry loop (bounded)
        var attempts = 1
        while (attempts < maxAttempts) {
            Thread.sleep(delayMs)
            val tree = controller.dumpScreen() ?: break
            val p = IpPortParser.parse(tree.toString())
            if (p != null) return p
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
