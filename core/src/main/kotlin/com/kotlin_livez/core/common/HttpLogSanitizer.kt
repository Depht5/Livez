package com.kotlin_livez.core.common

import java.net.URI

/**
 * 日志脱敏器：在打印请求信息前抹掉敏感字段（cookie/token/签名等）。
 * 对应 Dart `HttpLogSanitizer`（lib/src/common/http_log_sanitizer.dart）。
 */
object HttpLogSanitizer {

    private val sensitiveTextPattern = Regex(
        """(password|secret|token|session|csrf|signature|a[_-]?bogus|ttwid|sid[_-]?guard)\s*[:=]\s*([^;,\s&]+)""",
        RegexOption.IGNORE_CASE,
    )

    private val credentialLinePattern = Regex(
        """(cookie|authorization)\s*[:=]\s*[^\r\n]+""",
        RegexOption.IGNORE_CASE,
    )

    fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase().replace(Regex("""[-_\s]"""), "")
        return normalized.contains("cookie") ||
            normalized.contains("authorization") ||
            normalized.contains("password") ||
            normalized.contains("secret") ||
            normalized.contains("token") ||
            normalized.contains("session") ||
            normalized.contains("csrf") ||
            normalized.contains("signature") ||
            normalized == "ttwid" ||
            normalized == "abogus" ||
            normalized == "sidguard"
    }

    @Suppress("UNCHECKED_CAST")
    fun redact(value: Any?, key: String? = null): Any? {
        if (key != null && isSensitiveKey(key)) {
            return "<redacted>"
        }
        return when (value) {
            is Map<*, *> -> {
                (value as Map<Any, Any>).entries.associate { (k, v) ->
                    k to redact(v, key = k.toString())
                }
            }
            is Iterable<*> -> value.map { redact(it) }.toList()
            is String -> redactText(value)
            else -> value
        }
    }

    fun redactUri(uri: String): String {
        return try {
            val parsed = URI(uri)
            val query = parsed.rawQuery
            if (query == null) {
                uri
            } else {
                val redacted = query.split("&").joinToString("&") { pair ->
                    val idx = pair.indexOf('=')
                    val key = if (idx >= 0) pair.substring(0, idx) else pair
                    if (isSensitiveKey(key)) "$key=<redacted>" else pair
                }
                URI(parsed.scheme, parsed.authority, parsed.path, redacted, parsed.fragment).toString()
            }
        } catch (_: Exception) {
            uri
        }
    }

    fun redactText(value: String?, requestUri: String? = null): String {
        var result = value ?: ""
        if (requestUri != null) {
            result = result.replace(requestUri, redactUri(requestUri))
        }
        result = credentialLinePattern.replace(result) { match ->
            "${match.groupValues[1]}=<redacted>"
        }
        return sensitiveTextPattern.replace(result) { match ->
            "${match.groupValues[1]}=<redacted>"
        }
    }
}
