package dev.livefork.app.requests

/**
 * HTTP 错误 —— 对应 Dart `HttpError`（lib/requests/http_error.dart）。
 * statusCode 文案冻结保留。
 */
class HttpError(
    override val message: String,
    val statusCode: Int = 0,
) : Exception(message) {

    override fun toString(): String =
        if (statusCode != 0) statusCodeToString(statusCode) else message

    fun statusCodeToString(statusCode: Int): String =
        when (statusCode) {
            400 -> "错误的请求(400)"
            401 -> "无权限访问资源(401)"
            403 -> "无权限访问资源(403)"
            404 -> "服务器找不到请求的资源(404)"
            500 -> "服务器出现错误(500)"
            502 -> "服务器出现错误(502)"
            503 -> "服务器出现错误(503)"
            else -> "连接服务器失败，请稍后再试($statusCode)"
        }
}