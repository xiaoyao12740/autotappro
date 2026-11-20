package com.aitap.autotappro.core

import android.util.Log
import com.aitap.autotappro.BuildConfig

/**
 * 分级日志门面。
 *
 * - 诊断级 [i]/[d]/[w] 仅在 debug 构建输出，release 静默，避免正式包在 logcat 暴露内部坐标与节奏；
 * - 错误级 [e] 始终保留，用于异常回执与失败兜底上屏。
 */
object L {

    fun i(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.i(tag, msg)
    }

    fun d(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.d(tag, msg)
    }

    fun w(tag: String, msg: String) {
        if (BuildConfig.DEBUG) Log.w(tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable) {
        if (BuildConfig.DEBUG) Log.w(tag, msg, t)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
    }
}
