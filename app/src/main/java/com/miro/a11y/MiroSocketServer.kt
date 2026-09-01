package com.miro.a11y

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Embedded control socket for miro.
 *
 * Listens on a local abstract socket named "miro". The host PC connects via
 * `adb forward tcp:1234 localabstract:miro` then sends JSON commands, one per
 * line, and reads JSON responses.
 *
 * No root required — abstract local sockets are accessible to any app on the
 * device and forwarded over ADB.
 *
 * Protocol (one JSON object per line, newline-terminated):
 *   Request:  {"action":"tap","x":100.0,"y":200.0}
 *   Response: {"ok":true}  |  {"error":"..."}  |  {"tree":{...}}
 *
 * Actions: tap, swipe, home, back, recents, notifications, quick_settings,
 *          lock, tap_text, dump, find
 */
class MiroSocketServer(
    private val controller: MiroController,
    private val onLog: (String) -> Unit = {}
) : Thread() {

    companion object {
        private const val TAG = "miro.socket"

        // Singleton: the most recent server instance. Used to close
        // any prior server before creating a new one.
        @Volatile
        private var lastInstance: MiroSocketServer? = null

        // The socket name is unique per instance. We previously used
        // a fixed name "miro" but the Linux kernel on the OLAX
        // holds abstract socket names for several seconds after
        // close(), so every re-bind (after accessibility toggle)
        // hit "Address already in use". A unique per-instance name
        // sidesteps the kernel's release delay entirely.
        //
        // The PC must forward to whichever name the service is
        // currently using. We expose it via the Logcat tag "miro"
        // and the AccessibilityService logs the chosen name on
        // onServiceConnected.
        val SOCKET_NAME: String
            get() = currentName

        @Volatile
        private var currentName: String = "miro_" + android.os.Process.myPid() + "_" + (System.nanoTime() and 0xFFFF)

        /**
         * Close any previously created MiroSocketServer that may still
         * hold a socket. Safe to call multiple times. No grace period
         * is needed because the new instance uses a unique name.
         */
        fun closeExisting() {
            lastInstance?.let { server ->
                try {
                    server.stopServer()
                } catch (_: Exception) {}
            }
            lastInstance = null
            // Pick a new unique name for the next bind.
            currentName = "miro_" + android.os.Process.myPid() + "_" + (System.nanoTime() and 0xFFFF)
        }

        /** Name the service currently has bound (or will bind next). */
        fun currentSocketName(): String = currentName
    }

    init {
        lastInstance = this
    }

    @Volatile var running = true
    private var server: LocalServerSocket? = null
    @Volatile var openSucceeded: Boolean = false

    override fun run() {
        try {
            server = LocalServerSocket(SOCKET_NAME)
            onLog("miro socket listening on @$SOCKET_NAME")
            Log.i(TAG, "listening on @$SOCKET_NAME")
            openSucceeded = true
            while (running) {
                val client: LocalSocket = try {
                    server!!.accept()
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "accept error: ${e.message}")
                    continue
                }
                handleClient(client)
            }
        } catch (e: Exception) {
            Log.e(TAG, "server error: ${e.message}")
            openSucceeded = false
        } finally {
            try { server?.close() } catch (_: Exception) {}
        }
    }

    private fun handleClient(socket: LocalSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.UTF_8))
            val out: OutputStream = socket.outputStream
            while (running) {
                val raw = reader.readLine() ?: break
                if (raw.isBlank()) continue
                val response = try {
                    val cmd = JSONObject(raw)
                    controller.handleCommand(cmd)
                } catch (e: Exception) {
                    JSONObject().put("error", "bad json: ${e.message}")
                }
                val payload = response.toString() + "\n"
                out.write(payload.toByteArray(StandardCharsets.UTF_8))
                out.flush()
            }
        } catch (e: Exception) {
            Log.d(TAG, "client disconnected: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    fun stopServer() {
        running = false
        try { server?.close() } catch (_: Exception) {}
    }
}
