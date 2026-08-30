package com.kotlin_livez.app.app

import com.kotlin_livez.app.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * 应用日志 —— 对应 Dart `Log`（lib/app/log.dart）的简化版：
 * 输出到 Logcat 并维护 debug 日志列表（调试面板用）。
 * Dart 的 LogFileWriter（写文件）与 DebugLogModel 面板后续按需移植。
 */
object Log {

    private const val TAG = "Livez"
    private const val MAX_DEBUG_LOGS = 500

    /** 调试日志列表（对应 Dart debugLogs，debug 面板显示）。 */
    private val _debugLogs = MutableStateFlow<List<DebugLogModel>>(emptyList())
    val debugLogs: StateFlow<List<DebugLogModel>> = _debugLogs

    private fun addDebugLog(content: String, level: String) {
        if (!BuildConfig.DEBUG) return
        val contentWithMark = if (content.contains("请求响应")) {
            content.split("\n").joinToString("\n💡 ")
        } else {
            content
        }
        val item = DebugLogModel(System.currentTimeMillis(), contentWithMark, level)
        _debugLogs.update { current ->
            buildList(capacity = minOf(MAX_DEBUG_LOGS, current.size + 1)) {
                add(item)
                addAll(current.take(MAX_DEBUG_LOGS - 1))
            }
        }
    }

    fun d(message: String, writeFile: Boolean = true) {
        addDebugLog(message, "D")
        android.util.Log.d(TAG, message)
    }

    fun i(message: String, writeFile: Boolean = true) {
        addDebugLog(message, "I")
        android.util.Log.i(TAG, message)
    }

    fun e(message: String, throwable: Throwable? = null, writeFile: Boolean = true) {
        addDebugLog("$message\r\n\r\n${throwable?.stackTraceToString() ?: ""}", "E")
        android.util.Log.e(TAG, message, throwable)
    }

    fun w(message: String, writeFile: Boolean = true) {
        addDebugLog(message, "W")
        android.util.Log.w(TAG, message)
    }

    fun logPrint(obj: Any?, writeFile: Boolean = true) {
        addDebugLog(obj?.toString().orEmpty(), "P")
        if (BuildConfig.DEBUG) {
            android.util.Log.d(TAG, obj?.toString().orEmpty())
        }
    }
}

/** 调试日志条目（对应 Dart DebugLogModel）。 */
data class DebugLogModel(
    /** epoch millis */
    val datetime: Long,
    val content: String,
    /** "D"/"I"/"W"/"E"/"P" */
    val level: String,
)
