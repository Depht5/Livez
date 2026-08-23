package dev.livefork.core.common

/**
 * 抖音 Cookie 工具。
 * 对应 Dart `DouyinCookieHelper`（lib/src/common/douyin_cookie_helper.dart）。
 * 冻结中文提示文案逐字保留。
 */
object DouyinCookieHelper {

    fun hasCustomCookie(cookie: String): Boolean = cookie.trim().isNotEmpty()

    fun isOnlyTtwid(cookie: String): Boolean {
        val normalized = cookie.trim().lowercase()
        return normalized.startsWith("ttwid=") && !normalized.contains(";")
    }

    fun hasFullCookie(cookie: String): Boolean {
        val normalized = cookie.trim()
        return normalized.isNotEmpty() && !isOnlyTtwid(normalized)
    }

    /**
     * 从 Cookie 头中提取最小匿名播放 cookie（只保留 ttwid）。
     * 房间与播放接口不需要账号会话，只保留 ttwid 可避免无关账号 cookie 被发送。
     */
    fun extractTtwid(cookie: String): String? {
        var value = extractCookieFromHeaderText(cookie) ?: cookie.trim()
        if (value.lowercase().startsWith("cookie:")) {
            value = value.substring(value.indexOf(":") + 1).trim()
        }
        for (part in value.split(";")) {
            val item = part.trim()
            val separatorIndex = item.indexOf("=")
            if (separatorIndex <= 0) continue
            val key = item.substring(0, separatorIndex).trim().lowercase()
            val cookieValue = item.substring(separatorIndex + 1).trim()
            if (key == "ttwid" && cookieValue.isNotEmpty()) {
                return "ttwid=$cookieValue"
            }
        }
        return null
    }

    fun cookieCompletenessHint(cookie: String): String {
        val normalized = cookie.trim()
        if (normalized.isEmpty()) {
            return "未配置 Cookie"
        }
        if (isOnlyTtwid(normalized)) {
            return "仅检测到 ttwid，播放通常可用，但抖音关注状态可能需要完整 Cookie"
        }
        val lower = normalized.lowercase()
        val hasDouyinIdentity =
            lower.contains("sessionid=") ||
                lower.contains("sid_guard=") ||
                lower.contains("passport_csrf_token=") ||
                lower.contains("passport_csrf_token_default=") ||
                lower.contains("mstoken=") ||
                lower.contains("odin_tt=") ||
                lower.contains("passport_auth_status=") ||
                lower.contains("__ac_nonce=") ||
                lower.contains("__ac_signature=") ||
                lower.contains("ttwid=")
        return if (hasDouyinIdentity) {
            "已检测到非纯 ttwid Cookie，可用于登录态刷新；若仍失败，可能是 Cookie 过期或抖音风控"
        } else {
            "已保存自定义 Cookie，但未识别到典型登录字段；若刷新失败，建议重新从浏览器复制完整 Request Headers"
        }
    }

    fun normalizeInput(input: String): String {
        var cookie = extractCookieFromHeaderText(input) ?: input.trim()
        if (cookie.lowercase().startsWith("cookie:")) {
            cookie = cookie.substring(cookie.indexOf(":") + 1).trim()
        }
        if (!cookie.contains("=")) {
            cookie = "ttwid=$cookie"
        }
        return cookie
    }

    fun extractCookieFromHeaderText(input: String): String? {
        val lines = input.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        for (i in lines.indices) {
            val line = lines[i]
            val lower = line.lowercase()
            if (lower.startsWith("cookie:")) {
                return line.substring(line.indexOf(":") + 1).trim()
            }
            if (lower == "cookie" && i + 1 < lines.size) {
                return lines[i + 1].trim()
            }
        }
        return null
    }
}