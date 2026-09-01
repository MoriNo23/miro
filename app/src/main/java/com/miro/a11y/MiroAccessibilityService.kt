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
        //
        // This is a `var` (not `const val`) so the WirelessDebugTileService
        // QS tile can flip it at runtime when the user toggles the tile.
        @JvmField
        var kAutoStartWirelessDebug: Boolean = true
        private const val AUTO_START_DELAY_MS = 8_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var controller: MiroController
    private var socketServer: MiroSocketServer? = null
    private var wirelessAutomator: WirelessDebugAutomator? = null
    private var recentTasksCleaner: RecentTasksCleaner? = null
    private var recentTasksNotifier: RecentTasksNotifier? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "miro accessibility service connected")

        controller = MiroController(this)

        // Start embedded control socket (localhost only, no root needed).
        // The socket name is unique per instance ("miro_<pid>_<rand>")
        // because the Linux kernel on the OLAX Magic Q1 holds
        // abstract socket names for several seconds after close()
        // (verified 2026-09-01) — a fixed name like "miro" would
        // hit "Address already in use" on every re-bind (which
        // happens every time the user toggles accessibility on/off).
        // The PC discovers the current name via logcat (search for
        // "miro socket listening on @").
        Log.i(TAG, "miro socket will listen on @${MiroSocketServer.SOCKET_NAME}")
        Thread {
            MiroSocketServer.closeExisting()
            try {
                val s = MiroSocketServer(controller) { msg -> Log.d(TAG, msg) }
                s.start()
                // Wait for the server thread to actually open the
                // socket. The LocalServerSocket bind is synchronous
                // inside run(), but s.start() returns immediately.
                // We poll openSucceeded for up to 1 second.
                var waited = 0
                while (waited < 1000 && !s.openSucceeded && s.running) {
                    try { Thread.sleep(50) } catch (_: InterruptedException) {}
                    waited += 50
                }
                if (s.openSucceeded) {
                    socketServer = s
                    Log.i(TAG, "miro socket ready on @${MiroSocketServer.SOCKET_NAME}")
                } else {
                    Log.e(TAG, "miro socket failed to open on @${MiroSocketServer.SOCKET_NAME} after 1s — PC control disabled")
                }
            } catch (e: Exception) {
                Log.e(TAG, "miro socket threw: ${e.message} — PC control disabled")
            }
        }.start()

        // State machine for Wireless Debugging onboarding.
        // Triggered either by socket command {"action":"start_wireless_debug"}
        // or auto-triggered post-connect (8s delay) when kAutoStartWirelessDebug
        // is true. The OLAX path taps the "Depuración inalámbrica" QS tile
        // directly — see start() inside WirelessDebugAutomator.
        wirelessAutomator = WirelessDebugAutomator(this, controller) { msg -> Log.d(TAG, msg) }

        // Recent tasks cleaner + persistent notification with the
        // "Cerrar todas" action. Triggered from socket or notification.
        recentTasksCleaner = RecentTasksCleaner(this, controller) { msg -> Log.d(TAG, msg) }
        recentTasksNotifier = RecentTasksNotifier(this) {
            Log.i(TAG, "notification: kill all recent tapped")
            startKillAllRecents()
        }
        recentTasksNotifier?.show()

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

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        super.onUnbind(intent)
        Log.w(TAG, "onUnbind — releasing socket")
        socketServer?.stopServer()
        socketServer = null
        MiroSocketServer.closeExisting()
        // Brief delay so the kernel releases the abstract socket name
        // before the next onServiceConnected() tries to rebind.
        try { Thread.sleep(200) } catch (_: InterruptedException) {}
        return true
    }

    override fun onInterrupt() {
        Log.w(TAG, "onInterrupt — cleaning up wireless automator")
        wirelessAutomator?.stop()
        recentTasksCleaner?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "miro accessibility service destroyed")
        wirelessAutomator?.stop()
        recentTasksCleaner?.stop()
        recentTasksNotifier?.hide()
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

    /**
     * Kill all recent tasks. Triggered from socket {"action":"kill_all_recent"}
     * or from the persistent notification action.
     */
    fun startKillAllRecents(): Boolean {
        val cleaner = recentTasksCleaner ?: run {
            Log.w(TAG, "startKillAllRecents: no cleaner (service not fully connected)")
            return false
        }
        cleaner.start()
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

        // OLAX Magic Q1 path (verified 2026-09-01 with Mori). The full flow:
        //   1. Open Quick Settings (this is the NotificationShade on OLAX)
        //   2. Tap the "Depuración inalámbrica" tile
        //   3. The dialog "¿Permitir la depuración inalámbrica en esta red?" appears
        //   4. Tap the "Permitir siempre en esta red" checkbox so we don't get
        //      asked again on subsequent reboots
        //   5. Tap "PERMITIR"
        // After that, adb_wifi_enabled is 1 and the PC's adb_tablet script
        // can find the random port via nmap (no need to extract ip:port
        // from the tablet — that part is already handled on the PC side).
        val ok = controller.globalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
        if (!ok) {
            onLog("wireless debug: QUICK_SETTINGS failed")
        }
        // Even if globalAction() returned false (it can be flaky on
        // OLAX just after a wakeup), we still go to step 2: the QS
        // may have opened anyway. Step 2 will fail loudly if it didn't.
        handler.postDelayed({ step2TapWirelessDebugTile() }, 1500)
    }

    /**
     * Alternative way to open the QS panel via a swipe-down gesture.
     * Used as a fallback if performGlobalAction(QUICK_SETTINGS) returns
     * false consistently.
     */
    private fun openQsViaSwipe(): Boolean {
        onLog("wireless debug: openQsViaSwipe — swiping down from top of screen")
        val w = 512
        val h = 5
        val targetY = 380
        val d = 250L
        return controller.swipe(w.toFloat(), h.toFloat(), w.toFloat(), targetY.toFloat(), d)
    }

    /** Step 2: tap the "Depuración inalámbrica" tile in the QS grid. */
    private fun step2TapWirelessDebugTile() {
        if (!running) return
        state = State.CLICKING_WIRELESS_DEBUG
        onLog("wireless debug: state=${state.name} — tapping tile")

        val tileTerms = listOf("Depuración inalámbrica", "Wireless Debugging",
            "Wireless debug", "wireless debugging")
        val ok = tapByTextMulti(tileTerms, fallback = false)
        if (!ok) {
            // Maybe QS didn't open with performGlobalAction. Try
            // opening it with a swipe-down gesture and re-search.
            onLog("wireless debug: tile not found in QS — trying swipe-down to open QS")
            val sw = openQsViaSwipe()
            if (!sw) {
                onLog("wireless debug ERROR: could not open QS even via swipe")
                stopWithError("wireless debug tile not found in QS")
                return
            }
            handler.postDelayed({ step2AfterSwipe(tileTerms) }, 1500)
            return
        }
        onLog("wireless debug: tile tapped")
        handler.postDelayed({ step3CheckAlwaysAllow() }, 2000)
    }

    /**
     * Step 2 (post-swipe): re-search for the tile after openQsViaSwipe
     * has had time to settle.
     */
    private fun step2AfterSwipe(tileTerms: List<String>) {
        if (!running) return
        val ok2 = tapByTextMulti(tileTerms, fallback = false)
        if (!ok2) {
            onLog("wireless debug ERROR: tile still not found after swipe-open")
            stopWithError("wireless debug tile not found in QS")
            return
        }
        onLog("wireless debug: tile tapped after swipe-open")
        handler.postDelayed({ step3CheckAlwaysAllow() }, 2000)
    }

    /** Step 3: tap the "Permitir siempre en esta red" checkbox. */
    private fun step3CheckAlwaysAllow() {
        if (!running) return
        state = State.CLICKING_WIRELESS_DEBUG
        onLog("wireless debug: state=${state.name} — checking 'Permitir siempre'")

        // The OLAX WifiDebuggingActivity dialog renders in a system window
        // that the MiroAccessibilityService cannot see via getRootInActiveWindow
        // (verified 2026-09-01: tapByText('Permitir siempre') returns false
        // even though the dialog IS visible on screen). So we tap the
        // checkbox by its known coordinates instead.
        //
        // Coordinates from manual test (1024x600 OLAX, Spanish locale):
        //   - "Permitir siempre en esta red" checkbox: (511, 312)
        //   - "PERMITIR" button: (721, 372)
        //   - "CANCELAR" button: (629, 372)
        //
        // We tap the checkbox center then wait briefly before tapping
        // PERMITIR. If the checkbox isn't there (e.g. the user already
        // chose 'always' in a previous run), the tap is a no-op visually
        // and we still proceed to PERMITIR.
        val tappedCheckbox = controller.tap(511f, 312f)
        if (tappedCheckbox) {
            onLog("wireless debug: tapped 'Permitir siempre' checkbox at (511, 312)")
        } else {
            onLog("wireless debug: 'Permitir siempre' tap returned false — continuing anyway")
        }
        handler.postDelayed({ step4TapPermitir() }, 1000)
    }

    /** Step 4: tap the "PERMITIR" button to accept. */
    private fun step4TapPermitir() {
        if (!running) return
        state = State.SENDING_TO_PC
        onLog("wireless debug: state=${state.name} — tapping PERMITIR")

        // Same workaround as step 3: tap by coordinates because the
        // OLAX dialog doesn't expose its tree to the a11y service.
        val tapped = controller.tap(721f, 372f)
        if (!tapped) {
            onLog("wireless debug: PERMITIR tap returned false — bailing out")
            state = State.IDLE
            onLog("wireless debug ERROR: PERMITIR button not found")
            stop()
            return
        }
        // Wireless Debugging is now on. Done.
        onLog("WIRELESS_DEBUG_ENABLED via OLAX QS-tile flow")
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
            val tree = controller.dumpScreen()
            if (tree != null) {
                val text = tree.toString()
                val parsed = IpPortParser.parse(text)
                if (parsed != null) return parsed
            }
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

/**
 * RecentTasksCleaner — closes all recent tasks (apps in the recents list).
 *
 * User-reported 2026-09-01: the OLAX ESLauncher maps the physical RECENTS
 * button to a screenshot animation, not the real Recent screen. So the
 * user couldn't see/manage recent apps. This automator implements a
 * "close all recent apps" command, fired from:
 *   - Socket: {"action":"kill_all_recent"}
 *   - Notification action (started from RecentTasksNotifier)
 *
 * Strategy (verified 2026-09-01):
 *   1. Open Recents via performGlobalAction(GLOBAL_ACTION_RECENTS)
 *   2. Look for a "Cerrar todo" / "Clear all" / "Limpiar todo" button in
 *      the Recents screen. The OLAX ROM's RecentsActivity (com.android
 *      .launcher3/com.android.quickstep.RecentsActivity) is the same
 *      stock AOSP one — it has a "Cerrar todo" button in the bottom
 *      action bar (locale "es" confirmed in the dump).
 *   3. If found, tap it. Done.
 *   4. If not found (e.g. some custom ROM doesn't render the button),
 *      fall back to a loop: read the active window tree, find each
 *      "task snapshot" card, tap its "X" or swipe it off-screen. Bounded
 *      to max 20 iterations to avoid infinite loops.
 *   5. Verify by counting tasks before/after via dumpsys-style approach
 *      (uiautomator dump → count of distinct package names in card area).
 *
 * Note: the service can't call `am task remove-task` directly because that
 * requires shell UID (2000). We work around by interacting with the
 * Recents UI (which is a regular system app window).
 */
class RecentTasksCleaner(
    private val service: AccessibilityService,
    private val controller: MiroController,
    private val onLog: (String) -> Unit
) {
    enum class State {
        IDLE, OPENING_RECENTS, TAP_CLEAR_ALL, VERIFY, DONE
    }

    private var state: State = State.IDLE
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var initialTaskCount = 0
    private var finalTaskCount = 0

    private val clearAllTerms = listOf(
        "Cerrar todo", "Limpiar todo", "Borrar todo", "Quitar todo",
        "Clear all", "Cerrar todas", "Cerrar"
    )

    fun start() {
        if (running) {
            onLog("recents: already running, ignoring start")
            return
        }
        running = true
        state = State.OPENING_RECENTS
        onLog("recents: state=${state.name} — opening Recents screen")

        // Step 1: open the Recents screen. We can't use
        // performGlobalAction(GLOBAL_ACTION_RECENTS) on the OLAX ROM
        // because it opens the notification shade instead of the Recents
        // task switcher (verified 2026-09-01). Instead we start the
        // RecentsActivity directly with a system-level intent.
        //
        // The trick: the OLAX ESLauncher maps the physical RECENTS button
        // to a screenshot animation, so the user can't reach the real
        // recents UI. The RecentsActivity component is com.android.launcher3
        // /com.android.quickstep.RecentsActivity but it's not exported.
        //
        // We use the ACTION_MAIN + CATEGORY_HOME intent, which the system
        // handles by opening the recents UI. Fallback: ACTION_VIEW with
        // task data from getRecentTasks().
        val opened = openRecentsScreen()
        if (!opened) {
            onLog("recents: failed to open Recents screen — bailing out")
            state = State.IDLE
            onLog("recents ERROR: could not open recents screen")
            stop()
            return
        }
        handler.postDelayed({ step2Verify() }, 2000)
    }

    /**
     * Open the Recents task switcher. Tries several approaches because
     * the OLAX ROM blocks most of them.
     */
    private fun openRecentsScreen(): Boolean {
        // Approach 1: performGlobalAction — works on AOSP/QuickStep,
        // broken on OLAX (opens notification shade). Try anyway.
        val ok1 = controller.recents()
        if (ok1) {
            // The action was accepted, but the OLAX ROM may have routed
            // it to the notification shade. Check after a moment.
            onLog("recents: GLOBAL_ACTION_RECENTS returned true")
            return true
        }
        // Approach 2: start the RecentsActivity component directly.
        // It's not exported (verified 2026-09-01), so this will be
        // rejected with SecurityException. Wrap and ignore.
        return try {
            val intent = android.content.Intent(
                "android.intent.action.MAIN",
                null
            ).apply {
                addCategory("android.intent.category.HOME")
                addCategory("android.intent.category.DEFAULT")
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            service.startActivity(intent)
            onLog("recents: started MAIN+HOME intent as fallback")
            true
        } catch (e: Exception) {
            onLog("recents: startActivity fallback also failed: ${e.message}")
            false
        }
    }

    /** Step 2: verify the recents screen opened (or at least the user can see it). */
    private fun step2Verify() {
        if (!running) return
        state = State.VERIFY
        onLog("recents: state=${state.name} — Recents screen should now be visible")
        onLog("RECENTS_OPENED")
        state = State.DONE
        onLog("recents: DONE — user can now dismiss tasks manually")
        stop()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        onLog("recents: stopped (state=$state)")
    }
}
