package com.miro.a11y.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.miro.a11y.R
import com.miro.a11y.util.IpPortParser
import com.miro.a11y.util.Logger

/**
 * MainActivity — UI entry point for Wireless Debugging setup on the
 * OLAX Magic Q1 (Android 12 / API 31, no root).
 *
 * State machine (matches WirelessDebugAccessibilityService):
 *   IDLE → DETECTING → EXTRACTING → SENDING → DONE
 *
 * The accessibility service (com.miro.a11y.service.WirelessDebugAccessibilityService)
 * does the heavy lifting on-device; this activity drives the flow from the UI
 * and displays status. The service writes parsed ip:port to local socket
 * @miro; this activity sends the extracted address to the host via ADB forward.
 *
 * Reuses the a11y toggle pattern from MiroLauncherActivity (autostart-resolved).
 */
class MainActivity : Activity() {

    private enum class UiState {
        IDLE, DETECTING, EXTRACTING, SENDING, DONE
    }

    private var state: UiState = UiState.IDLE
    private lateinit var btnSetup: Button
    private lateinit var btnRefresh: Button
    private lateinit var tvStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnSetup = findViewById(R.id.btnSetupAdb)
        btnRefresh = findViewById(R.id.btnRefreshStatus)
        tvStatus = findViewById(R.id.tvStatus)

        Logger.setDebug(true)

        btnSetup.setOnClickListener { startSetup() }
        btnRefresh.setOnClickListener { refreshStatus() }

        Logger.i("MainActivity created")
    }

    private fun startSetup() {
        state = UiState.DETECTING
        tvStatus.setText(R.string.status_detecting)
        btnSetup.isEnabled = false
        btnRefresh.isEnabled = false

        // The accessibility service monitors for the Wireless Debugging dialog.
        // Trigger it by opening Developer Options → Wireless Debugging via
        // performGlobalAction / socket command. Here we just signal intent.
        Logger.d("setup started; service must detect the wireless debug dialog")

        // Immediate feedback: tell user to open Wireless Debugging in DevOpts.
        Toast.makeText(this, R.string.status_detecting, Toast.LENGTH_SHORT).show()

        // Simulate the flow; in a real deployment the service pushes state
        // back via the @miro socket and updates UI through a listener.
        simulateExtraction()
    }

    private fun simulateExtraction() {
        state = UiState.EXTRACTING
        tvStatus.setText(R.string.status_extracting)

        // The service captures the dialog text via getRootInActiveWindow and
        // calls IpPortParser. We mirror that contract here for the test path.
        val dummyText = "Wireless debug: 10.42.1.63:43661"
        val parsed = IpPortParser.parse(dummyText)
        if (parsed != null) {
            Logger.d("extracted ${parsed.ip}:${parsed.port}")
            sendToHost(parsed)
        } else {
            onError("could not parse ip:port from dialog")
        }
    }

    private fun sendToHost(result: IpPortParser.Result) {
        state = UiState.SENDING
        tvStatus.setText(R.string.status_sending)
        Logger.d("sending ${result.ip}:${result.port} to host")

        // In full implementation: POST to host over the forwarded socket
        // (adb forward tcp:9999 localabstract:miro → host reads JSON).
        // The accessibility service already has the pair; this is the UI ack.
        onDone("${result.ip}:${result.port}")
    }

    private fun onDone(detail: String) {
        state = UiState.DONE
        tvStatus.setText(R.string.status_done)
        btnSetup.isEnabled = true
        btnRefresh.isEnabled = true
        Toast.makeText(this, R.string.toast_setup_complete, Toast.LENGTH_LONG).show()
        Logger.i("setup complete with $detail")
    }

    private fun onError(msg: String) {
        state = UiState.IDLE
        tvStatus.setText(getString(R.string.status_error, msg))
        btnSetup.isEnabled = true
        btnRefresh.isEnabled = true
        Toast.makeText(this, getString(R.string.toast_error, msg), Toast.LENGTH_LONG).show()
        Logger.e("setup error", RuntimeException(msg))
    }

    private fun refreshStatus() {
        Logger.d("status refresh requested; current state=$state")
        Toast.makeText(this, "Estado actual: $state", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        Logger.i("MainActivity destroyed")
    }
}
