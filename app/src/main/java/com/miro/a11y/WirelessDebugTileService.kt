package com.miro.a11y

import android.content.ComponentName
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * WirelessDebugTileService — Quick Settings tile for toggling the
 * MiroAccessibilityService's auto-start Wireless Debugging flow.
 *
 * Why this exists: when Mori is using the tablet manually and doesn't
 * want the a11y service to fire the QS-tile wireless debug flow on
 * its own (e.g. during demos, screen recording, or just for a quiet
 * day), they can disable the auto-start from the Quick Settings
 * panel without uninstalling the app.
 *
 * The toggle is read by MiroAccessibilityService on every
 * onServiceConnected(); the flag persists in the same companion
 * object field (kAutoStartWirelessDebug).
 *
 * Requirements:
 *   - Android 7.0+ (TileService base, API 24)
 *   - The tile must be added to QS by the user (we can't do it
 *     automatically because tiles need user confirmation in
 *     SystemUI). Long-press on the QS panel, drag it to the active
 *     area.
 *
 * State:
 *   - STATE_ACTIVE: auto-start is enabled (default). The tile icon
 *     is in the "active" state.
 *   - STATE_INACTIVE: auto-start is disabled. The tile icon is in
 *     the "inactive" state.
 *
 * IMPORTANT: the MiroAccessibilityService uses the in-process
 * kAutoStartWirelessDebug companion-object field. This TileService
 * runs in the same APK, so the field is shared. No IPC, no
 * SharedPreferences, no service binding.
 */
class WirelessDebugTileService : TileService() {

    companion object {
        private const val TAG = "miro"

        /**
         * Static helper to update the tile UI from anywhere in the
         * app (we call it from the MiroAccessibilityService when the
         * user opens the QS panel, so the tile icon reflects the
         * current state).
         *
         * Note: requesting listening state is a no-op if the tile is
         * not currently visible to the user (i.e. not added to QS
         * or QS is collapsed).
         */
        fun refreshTile(applicationContext: android.content.Context) {
            try {
                requestListeningState(
                    applicationContext,
                    ComponentName(applicationContext, WirelessDebugTileService::class.java)
                )
            } catch (e: Exception) {
                Log.w(TAG, "tile: failed to refresh: ${e.message}")
            }
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        // Flip the flag and update the tile.
        MiroAccessibilityService.kAutoStartWirelessDebug = !MiroAccessibilityService.kAutoStartWirelessDebug
        Log.i(TAG, "tile: user toggled auto-start = ${MiroAccessibilityService.kAutoStartWirelessDebug}")
        updateTile()
        // Bounce the tile to give visual feedback.
        try {
            val tile = qsTile
            if (tile != null) {
                tile.updateTile()
            }
        } catch (e: Exception) {
            // Tile may not be available if user removed it; ignore.
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val enabled = MiroAccessibilityService.kAutoStartWirelessDebug
        tile.label = if (enabled) "Auto-WirelessDebug ON" else "Auto-WirelessDebug OFF"
        tile.contentDescription = tile.label
        tile.state = if (enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        // Use Android's built-in icons to avoid having to ship our own.
        // ic_lock_idle_alarm is a generic "active" indicator; ic_lock_idle_lock
        // is the "off" indicator. They're not perfect but they convey state.
        tile.icon = if (enabled) {
            Icon.createWithResource(this, android.R.drawable.ic_media_play)
        } else {
            Icon.createWithResource(this, android.R.drawable.ic_media_pause)
        }
        tile.updateTile()
    }
}
