package dev.livefork.core.common

/**
 * 日志级别，对应 Dart logger 的 Level。
 */
enum class LogLevel { debug, info, warning, error }

/**
 * 请求日志模式。
 * 对应 Dart `RequestLogType`（lib/src/common/core_log.dart）。
 */
enum class RequestLogType {
    /** 输出所有请求信息 */
    all,

    /** 简洁输出：仅 URL 与状态码 */
    short,

    /** 不输出请求信息 */
    none,
}

/**
 * 核心日志。
 * 对应 Dart `CoreLog`（lib/src/common/core_log.dart）。
 *
 * 类似于 Dart 通过 `onPrintLog` 交给宿主（app）打印，Kotlin 端同样暴露 `onPrintLog` 钩子；
 * 当未设置时回退到标准输出（JVM/core 内部使用；Android 端由 app 层接入 Timber/log 实现）。
 */
object CoreLog {

    /** 是否启用日志 */
    var enableLog: Boolean = true

    /** 请求日志模式 */
    var requestLogType: RequestLogType = RequestLogType.all

    /** 宿主打印钩子（对应 Dart onPrintLog），设置后由宿主接管 */
    var onPrintLog: ((LogLevel, String) -> Unit)? = null

    fun d(message: String) {
        if (!enableLog) return
        emit(LogLevel.debug, message)
    }

    fun i(message: String) {
        if (!enableLog) return
        emit(LogLevel.info, message)
    }

    fun w(message: String) {
        if (!enableLog) return
        emit(LogLevel.warning, message)
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (!enableLog) return
        emit(LogLevel.error, message + (throwable?.let { "\n${it.stackTraceToString()}" } ?: ""))
    }

    /** 打印任意异常（对应 Dart CoreLog.error(e)） */
    fun error(error: Any?) {
        if (!enableLog) return
        emit(LogLevel.error, error.toString())
    }

    private fun emit(level: LogLevel, message: String) {
        val line = "${java.time.Instant.now()}\n$message"
        val hook = onPrintLog
        if (hook != null) {
            hook(level, line)
        } else {
            println("[${level.name.uppercase()}] $line")
        }
    }
}
