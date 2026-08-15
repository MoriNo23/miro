package com.miro.a11y

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Core logic for miro. Holds the high-level actions the service can perform
 * and the command dispatch used by the embedded control socket.
 *
 * All actions require the service to be bound (user enabled it once in
 * Settings > Accessibility). No root, no WRITE_SECURE_SETTINGS needed.
 */
class MiroController(private val service: MiroAccessibilityService) {

    companion object {
        private const val TAG = "miro.controller"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // ---- Gestures --------------------------------------------------------

    /** Tap at absolute coordinates (px in current window). */
    fun tap(x: Float, y: Float, durationMs: Long = 50): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatch(path, durationMs)
    }

    /** Swipe from (x1,y1) to (x2,y2). */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300): Boolean {
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        return dispatch(path, durationMs)
    }

    private fun dispatch(path: Path, durationMs: Long): Boolean {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return service.dispatchGesture(gesture, null, null)
    }

    // ---- Global actions --------------------------------------------------

    fun globalAction(action: Int): Boolean = service.performGlobalAction(action)

    fun home() = globalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    fun back() = globalAction(AccessibilityService.GLOBAL_ACTION_BACK)
    fun recents() = globalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
    fun notifications() = globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
    fun quickSettings() = globalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
    fun lockScreen() = globalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)

    // ---- Screen reading --------------------------------------------------

    /**
     * Dump the active window tree as JSON. Returns null if no root.
     * Each node: {text, desc, class, clickable, bounds:[l,t,r,b], children:[...]}
     */
    fun dumpScreen(): JSONObject? {
        val root = service.rootInActiveWindow ?: return null
        return nodeToJson(root)
    }

    private fun nodeToJson(node: AccessibilityNodeInfo): JSONObject {
        val o = JSONObject()
        o.put("text", node.text?.toString() ?: "")
        o.put("desc", node.contentDescription?.toString() ?: "")
        o.put("class", node.className?.toString() ?: "")
        o.put("clickable", node.isClickable)
        o.put("scrollable", node.isScrollable)
        o.put("editable", node.isEditable)
        val b = Rect()
        node.getBoundsInScreen(b)
        o.put("bounds", JSONArray(listOf(b.left, b.top, b.right, b.bottom)))
        val kids = JSONArray()
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { kids.put(nodeToJson(it)) }
        }
        o.put("children", kids)
        return o
    }

    /** Find first node whose text or description contains [query]. */
    fun findNode(query: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        return searchNode(root, query.lowercase())
    }

    private fun searchNode(node: AccessibilityNodeInfo, q: String): AccessibilityNodeInfo? {
        val t = node.text?.toString()?.lowercase() ?: ""
        val d = node.contentDescription?.toString()?.lowercase() ?: ""
        if (t.contains(q) || d.contains(q)) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val found = searchNode(c, q)
            if (found != null) return found
        }
        return null
    }

    /** Tap the first node whose text/desc contains [query]. */
    fun tapByText(query: String): Boolean {
        val node = findNode(query) ?: return false
        val b = Rect()
        node.getBoundsInScreen(b)
        val cx = ((b.left + b.right) / 2).toFloat()
        val cy = ((b.top + b.bottom) / 2).toFloat()
        return tap(cx, cy)
    }

    // ---- Command dispatch (used by socket server) -----------------------

    fun handleCommand(cmd: JSONObject): JSONObject {
        val result = JSONObject()
        try {
            when (cmd.optString("action")) {
                "tap" -> result.put("ok", tap(cmd.getDouble("x").toFloat(), cmd.getDouble("y").toFloat()))
                "swipe" -> result.put("ok", swipe(
                    cmd.getDouble("x1").toFloat(), cmd.getDouble("y1").toFloat(),
                    cmd.getDouble("x2").toFloat(), cmd.getDouble("y2").toFloat()))
                "home" -> result.put("ok", home())
                "back" -> result.put("ok", back())
                "recents" -> result.put("ok", recents())
                "notifications" -> result.put("ok", notifications())
                "quick_settings" -> result.put("ok", quickSettings())
                "lock" -> result.put("ok", lockScreen())
                "tap_text" -> result.put("ok", tapByText(cmd.getString("text")))
                "dump" -> {
                    val dump = dumpScreen()
                    if (dump != null) result.put("tree", dump) else result.put("error", "no root window")
                }
                "find" -> {
                    val node = findNode(cmd.getString("text"))
                    if (node != null) {
                        val b = Rect(); node.getBoundsInScreen(b)
                        result.put("bounds", JSONArray(listOf(b.left, b.top, b.right, b.bottom)))
                    } else result.put("error", "not found")
                }
                else -> result.put("error", "unknown action: ${cmd.optString("action")}")
            }
        } catch (e: Exception) {
            result.put("error", e.message)
        }
        return result
    }
}
