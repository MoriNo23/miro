package com.miro.a11y

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Empty launcher activity. Exists only to take the app out of the "stopped"
 * state after install. Android won't bind an AccessibilityService from a
 * stopped app. This activity launches once (user taps the icon or we launch
 * it via ADB), Android marks the app as launched, and the service can bind.
 */
class MiroLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("miro", "launcher activity — app taken out of stopped state")
        finish()
    }
}
