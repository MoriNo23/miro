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
        const val SOCKET_NAME = "miro"
        private const val TAG = "miro.socket"
    }

    @Volatile private var running = true
    private var server: LocalServerSocket? = null

    override fun run() {
        try {
            server = LocalServerSocket(SOCKET_NAME)
            onLog("miro socket listening on @$SOCKET_NAME")
            Log.i(TAG, "listening on @$SOCKET_NAME")
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
        } finally {
            try { server?.close() } catch (_: Exception) {}
        }
    }

    private fun handleClient(socket: LocalSocket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.inputStream, StandardCharsets.UTF_8))
            val out: OutputStream = socket.outputStream
            var line: String?
            while (running && reader.readLine().also { line = it } != null) {
                val raw = line ?: continue
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
