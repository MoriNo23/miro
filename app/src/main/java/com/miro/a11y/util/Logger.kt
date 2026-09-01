package com.miro.a11y.util

import android.util.Log

/**
 * Thin logging wrapper. Debug logs are gated on a static flag set per-build-type
 * via `buildConfigField` — keeps release builds silent without coupling to
 * BuildConfig generation quirks on AGP 8.x (where BuildConfig may be absent
 * in minified/release variants).
 */
object Logger {
    @JvmStatic private var debugEnabled: Boolean = false

    fun setDebug(enabled: Boolean) { debugEnabled = enabled }

    fun d(msg: String) { if (debugEnabled) Log.d("miro", msg) }
    fun i(msg: String) { Log.i("miro", msg) }
    fun w(msg: String) { Log.w("miro", msg) }
    fun e(msg: String, t: Throwable? = null) { Log.e("miro", msg, t) }
}

