package com.kotlin_livez.core.common

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 直播间链接解析结果 —— 对应 Dart `LiveParsedUrl`
 * （`simple_live_core/lib/src/common/live_url_parser.dart`）。
 *
 * 与 Dart 的差异：Dart 用 `null` 表示失败，Kotlin 用 [Rejected] 携带失败原因，
 * 解析页据此给出准确文案（Dart 只有一句「无法解析此链接」，短链失败/平台下架都无法区分）。
 */
sealed interface LiveUrlOutcome {

    /** 已确定站点与房间号。 */
    data class Room(val siteId: String, val roomId: String) : LiveUrlOutcome

    /** 短链：必须跟随重定向后再解析（见 [LiveLinkResolver]）。 */
    data class ShortLink(val siteId: String, val url: String) : LiveUrlOutcome

    /** 无法解析；[reason] 决定提示文案，[host] 仅用于日志定位。 */
    data class Rejected(
        val reason: LiveUrlRejectReason,
        val host: String? = null,
    ) : LiveUrlOutcome
}

/** 链接解析失败原因（UI 文案映射见 app 侧 `ParseController.messageOf`）。 */
enum class LiveUrlRejectReason {
    /** 输入为空。 */
    EMPTY,

    /** 不是 http(s) 链接，或根本不是 URL。 */
    NOT_HTTP,

    /** 域名不在支持列表内。 */
    UNKNOWN_HOST,

    /** 域名对，但路径里没有房间号（如 `www.douyin.com/video/123`）。 */
    MISSING_ROOM_ID,

    /** 取到了房间号，但字符集/纯数字校验不通过。 */
    INVALID_ROOM_ID,

    /** 识别到平台，但当前版本已移除该平台（快手）。 */
    PLATFORM_UNSUPPORTED,

    /** 短链无法恢复（网络失败、跳转环、或嵌套层级超限）。 */
    SHORT_LINK_FAILED,
}

/**
 * 直播间链接解析：按 host + path 段提取站点与房间号，纯函数、无网络。
 *
 * 移植自 Dart `LiveUrlParser`，刻意保留三条严格性（它们各自对应一个历史 bug）：
 * 1. 房间号作为**完整 path 段**校验，而不是在原始字符串上正则截取 —— `abc.def` 不会被截成 `abc`；
 * 2. 房间号字符集 `^[A-Za-z0-9._-]+$`，斗鱼 `rid` 与抖音 `reflow` 额外要求纯数字；
 * 3. host 一律小写匹配（[HttpUrl] 已归一化），非 http(s) 直接拒绝。
 *
 * 相对 Dart 的两处**有意改进**（均有单测）：
 * - `live.bilibili.com/blanc/<id>`（B 站分享卡片常见形态）取第二段；
 * - [extractHttpUrl] 会剥掉 URL 尾部成对/不成对的标点与右括号（微信、QQ 分享文案常见）。
 */
object LiveUrlParser {

    /** 站点 id —— 与 app `Constant.k*` 保持一致（core 不依赖 app，故在此重复声明）。 */
    const val SITE_BILIBILI = "bilibili"
    const val SITE_DOUYU = "douyu"
    const val SITE_HUYA = "huya"
    const val SITE_DOUYIN = "douyin"

    private val ROOM_ID_PATTERN = Regex("^[A-Za-z0-9._-]+$")
    private val NUMERIC_ID_PATTERN = Regex("^[0-9]+$")
    private val SCHEME_PATTERN = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")

    /** 无协议头时用来判断「像不像域名」，避免把「随便一段话」误报成非法协议。 */
    private val HOST_LIKE_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9.-]*\\.[A-Za-z]{2,}([/?:#]|$)")

    /** 分享文案里的首个链接；中文标点作为边界（与 Dart 一致）。 */
    private val HTTP_URL_PATTERN =
        Regex("https?://[^\\s<>\"'，。！？、；：]+", RegexOption.IGNORE_CASE)

    /** URL 尾部可能混入的标点/右括号（房间号不含这些字符，剥掉不损失合法性）。 */
    private const val TRAILING_JUNK = "，。！？、；：,;:!?)]}>）】》”’\"'"

    /**
     * 从分享文案中提取第一个 http(s) 链接（对应 Dart `ParseController.extractHttpUrl`）。
     *
     * 未匹配到返回空串，由调用方决定回退（原项目回退成「拿整段文本去解析」）。
     */
    fun extractHttpUrl(text: String): String {
        val matched = HTTP_URL_PATTERN.find(text)?.value ?: return ""
        return matched.trimEnd { it in TRAILING_JUNK }
    }

    /** 解析直播间链接；永远返回结果对象，失败原因见 [LiveUrlRejectReason]。 */
    fun parse(value: String): LiveUrlOutcome {
        val text = value.trim()
        if (text.isEmpty()) return LiveUrlOutcome.Rejected(LiveUrlRejectReason.EMPTY)

        val scheme = SCHEME_PATTERN.find(text)?.value?.removeSuffix("://")?.lowercase()
        if (scheme != null && scheme != "http" && scheme != "https") {
            return LiveUrlOutcome.Rejected(LiveUrlRejectReason.NOT_HTTP)
        }
        if (scheme == null && !HOST_LIKE_PATTERN.containsMatchIn(text)) {
            return LiveUrlOutcome.Rejected(LiveUrlRejectReason.NOT_HTTP)
        }

        val url = (if (scheme == null) "https://$text" else text).toHttpUrlOrNull()
            ?: return LiveUrlOutcome.Rejected(LiveUrlRejectReason.NOT_HTTP)
        val host = url.host
        val segments = url.pathSegments.filter { it.isNotEmpty() }

        // 短链：必须先跟随重定向，无法直接提取房间号
        when (host) {
            "b23.tv" -> return LiveUrlOutcome.ShortLink(SITE_BILIBILI, url.toString())
            "v.douyin.com" -> return LiveUrlOutcome.ShortLink(SITE_DOUYIN, url.toString())
            "v.kuaishou.com" -> return LiveUrlOutcome.Rejected(
                LiveUrlRejectReason.PLATFORM_UNSUPPORTED,
                host,
            )
        }

        // 快手（含短链）已按产品需求整体移除，识别到即提示「暂不支持该平台」
        if (host == "live.kuaishou.com" ||
            host == "m.chenzhongtech.com" ||
            host.endsWith(".m.chenzhongtech.com")
        ) {
            return LiveUrlOutcome.Rejected(LiveUrlRejectReason.PLATFORM_UNSUPPORTED, host)
        }

        if (host == "live.bilibili.com") {
            val roomId = if (segments.firstOrNull() == "blanc" && segments.size >= 2) {
                segments[1]
            } else {
                segments.firstOrNull()
            } ?: return LiveUrlOutcome.Rejected(LiveUrlRejectReason.MISSING_ROOM_ID, host)
            return room(SITE_BILIBILI, roomId, host)
        }

        // 斗鱼 topic 页面的房间号在 rid 参数里
        if (host == "douyu.com" || host.endsWith(".douyu.com")) {
            if (segments.firstOrNull() == "topic") {
                val rid = url.queryParameter("rid").orEmpty()
                if (rid.isEmpty()) {
                    return LiveUrlOutcome.Rejected(LiveUrlRejectReason.MISSING_ROOM_ID, host)
                }
                return numericRoom(SITE_DOUYU, rid, host)
            }
            val roomId = segments.firstOrNull()
                ?: return LiveUrlOutcome.Rejected(LiveUrlRejectReason.MISSING_ROOM_ID, host)
            return room(SITE_DOUYU, roomId, host)
        }

        if (host == "huya.com" || host.endsWith(".huya.com")) {
            val roomId = segments.firstOrNull()
                ?: return LiveUrlOutcome.Rejected(LiveUrlRejectReason.MISSING_ROOM_ID, host)
            return room(SITE_HUYA, roomId, host)
        }

        if (host == "live.douyin.com") {
            val roomId = segments.firstOrNull()
                ?: return LiveUrlOutcome.Rejected(LiveUrlRejectReason.MISSING_ROOM_ID, host)
            return room(SITE_DOUYIN, roomId, host)
        }

        // www.douyin.com/live/<webRid> 与 www.douyin.com/follow/live/<webRid>
        if (host == "douyin.com" || host.endsWith(".douyin.com")) {
            val webRid = when {
                segments.size == 2 && segments[0] == "live" -> segments[1]
                segments.size == 3 && segments[0] == "follow" && segments[1] == "live" ->
                    segments[2]
                else -> null
            } ?: return LiveUrlOutcome.Rejected(LiveUrlRejectReason.MISSING_ROOM_ID, host)
            return room(SITE_DOUYIN, webRid, host)
        }

        // webcast.amemv.com/.../reflow/<roomId>
        if (host == "amemv.com" || host.endsWith(".amemv.com")) {
            val reflowIndex = segments.indexOf("reflow")
            if (reflowIndex < 0 || reflowIndex + 1 >= segments.size) {
                return LiveUrlOutcome.Rejected(LiveUrlRejectReason.MISSING_ROOM_ID, host)
            }
            return numericRoom(SITE_DOUYIN, segments[reflowIndex + 1], host)
        }

        return LiveUrlOutcome.Rejected(LiveUrlRejectReason.UNKNOWN_HOST, host)
    }

    private fun room(siteId: String, roomId: String, host: String): LiveUrlOutcome =
        if (ROOM_ID_PATTERN.matches(roomId)) {
            LiveUrlOutcome.Room(siteId, roomId)
        } else {
            LiveUrlOutcome.Rejected(LiveUrlRejectReason.INVALID_ROOM_ID, host)
        }

    private fun numericRoom(siteId: String, roomId: String, host: String): LiveUrlOutcome =
        if (NUMERIC_ID_PATTERN.matches(roomId)) {
            LiveUrlOutcome.Room(siteId, roomId)
        } else {
            LiveUrlOutcome.Rejected(LiveUrlRejectReason.INVALID_ROOM_ID, host)
        }
}
