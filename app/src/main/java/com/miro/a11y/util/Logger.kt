package com.miro.a11y.util

import android.util.Log
import com.miro.a11y.BuildConfig

object Logger {
    fun d(msg: String) { if (BuildConfig.DEBUG) Log.d("miro", msg) }
    fun i(msg: String) { Log.i("miro", msg) }
    fun w(msg: String) { Log.w("miro", msg) }
    fun e(msg: String, t: Throwable? = null) { Log.e("miro", msg, t) }
}
