package dev.livefork.core.common

/**
 * 核心错误。
 * 对应 Dart `CoreError`（lib/src/common/core_error.dart）。错误文案为冻结的中文契约，逐字保留。
 */
class CoreError(
    /** 错误信息 */
    override val message: String,
    /** 错误码 */
    val statusCode: Int = 0,
) : RuntimeException(message) {

    override fun toString(): String =
        if (statusCode != 0) statusCodeToString(statusCode) else message

    fun statusCodeToString(statusCode: Int): String = when (statusCode) {
        400 -> "错误的请求(400)"
        401 -> "无权限访问资源(401)"
        403 -> "无权限访问资源(403)"
        404 -> "服务器找不到请求的资源(404)"
        444 -> "抖音访问过于频繁或触发风控限制(444)，请稍后再试，避免连续刷新或重复进入直播间"
        429 -> "请求过于频繁(429)，请稍后重试"
        500 -> "服务器出现错误(500)"
        502 -> "服务器出现错误(502)"
        503 -> "服务器出现错误(503)"
        else -> "连接服务器失败，请稍后再试($statusCode)"
    }
}
