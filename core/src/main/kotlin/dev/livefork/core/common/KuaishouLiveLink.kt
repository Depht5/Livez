package dev.livefork.core.common

import java.net.URI
import java.net.URLDecoder

/**
 * 快手直播链接解析工具。
 * 对应 Dart `KuaishouLiveLink`（lib/src/common/kuaishou_live_link.dart）。
 * 用 `java.net.URI` 实现，行为对齐 Dart：只允许 http/https、无 userInfo；
 * 已知主机为 v.kuaishou.com / live.kuaishou.com / m.chenzhongtech.com（含子域）。
 */
object KuaishouLiveLink {

    /** 房间 ID 允许的字符：字母、数字、下划线、连字符。 */
    private val roomIdPattern = Regex("^[A-Za-z0-9_-]+$")

    /** 构造直播间公开页面地址；房间 ID 非法时返回 null。 */
    fun publicRoomUri(roomId: String): URI? {
        val normalized = roomId.trim()
        if (!roomIdPattern.matches(normalized)) {
            return null
        }
        return try {
            URI("https", "live.kuaishou.com", "/u/$normalized", null)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 尝试将任意文本解析为快手 http(s) 链接。
     * 无协议前缀时补 `https://` 再校验（对应 Dart `parseHttpUrl`）。
     */
    fun parseHttpUrl(value: String): URI? {
        val text = value.trim()
        var uri = tryParse(text)
        if (uri == null || uri.host.isNullOrEmpty()) {
            val normalized = tryParse("https://$text")
            if (normalized == null || !isKnownHost(normalized)) {
                return null
            }
            uri = normalized
        }
        return if (isHttpUri(uri) && isKnownHost(uri)) uri else null
    }

    /** 是否 http/https 链接且无用户信息（对应 Dart `isHttpUri`）。 */
    fun isHttpUri(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        return uri.rawUserInfo.isNullOrEmpty() &&
            !uri.host.isNullOrEmpty() &&
            (scheme == "http" || scheme == "https")
    }

    /** 是否为快手短链（v.kuaishou.com）。 */
    fun isShortLink(uri: URI): Boolean =
        isHttpUri(uri) && uri.host?.lowercase() == "v.kuaishou.com"

    /** 短链或能解析出房间 ID 的链接视为可信跳转目标。 */
    fun isTrustedRedirectTarget(uri: URI): Boolean =
        isShortLink(uri) || roomIdFromUri(uri) != null

    /**
     * 从直播间链接中解析房间 ID：
     * - live.kuaishou.com/u/{roomId}
     * - m.chenzhongtech.com/fw/live/{roomId}（含 *.m.chenzhongtech.com 子域）
     * 解析结果需匹配房间 ID 字符集，否则返回 null。
     */
    fun roomIdFromUri(uri: URI): String? {
        if (!isHttpUri(uri)) {
            return null
        }
        val segments = pathSegments(uri)
        val host = uri.host?.lowercase() ?: ""
        val isLiveHost = host == "live.kuaishou.com"
        val isMobileLiveHost =
            host == "m.chenzhongtech.com" || host.endsWith(".m.chenzhongtech.com")
        val roomId = when {
            isLiveHost && segments.size == 2 && segments[0] == "u" -> segments[1]
            isMobileLiveHost && segments.size == 3 &&
                segments[0] == "fw" && segments[1] == "live" -> segments[2]
            else -> ""
        }
        return if (roomIdPattern.matches(roomId)) roomId else null
    }

    private fun tryParse(text: String): URI? =
        try {
            URI(text)
        } catch (_: Exception) {
            null
        }

    private fun isKnownHost(uri: URI): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return host == "v.kuaishou.com" ||
            host == "live.kuaishou.com" ||
            host == "m.chenzhongtech.com" ||
            host.endsWith(".m.chenzhongtech.com")
    }

    /** 解码后的路径段（对应 Dart `Uri.pathSegments`，段会做百分号解码）。 */
    private fun pathSegments(uri: URI): List<String> {
        val path = uri.rawPath ?: return emptyList()
        return path.split("/")
            .filter { it.isNotEmpty() }
            .map { decodePathSegment(it) }
    }

    private fun decodePathSegment(segment: String): String =
        try {
            // Dart pathSegments 解码时保留 '+'，与 URLDecoder 会把 '+' 转空格的行为不同，先转义
            URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            segment
        }
}