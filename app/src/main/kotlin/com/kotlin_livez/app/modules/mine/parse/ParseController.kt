package com.kotlin_livez.app.modules.mine.parse

import com.kotlin_livez.app.app.Log
import com.kotlin_livez.app.app.Site
import com.kotlin_livez.app.app.Sites
import com.kotlin_livez.core.common.LiveLinkResolver
import com.kotlin_livez.core.common.LiveUrlOutcome
import com.kotlin_livez.core.common.LiveUrlRejectReason
import com.kotlin_livez.core.common.OkHttpLiveLinkFetcher
import com.kotlin_livez.core.model.LivePlayQuality
import com.kotlin_livez.core.model.LivePlayUrl
import com.kotlin_livez.core.model.LiveRoomDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 链接解析控制器 —— 对应 Dart `ParseController`
 * （`lib/modules/mine/parse/parse_controller.dart`）。
 *
 * 分工：
 * - 解析本身（纯函数 + 短链恢复）在 core：[com.kotlin_livez.core.common.LiveUrlParser] /
 *   [com.kotlin_livez.core.common.LiveLinkResolver]；
 * - 本类只做站点查表、失败文案映射，以及「获取直链」所需的站点调用；
 * - 弹窗、剪贴板、键盘等 UI 行为留在解析页。
 *
 * 与 Dart 的差异（有意为之）：Dart 的 `parse` 返回 `[roomId, site]`，空列表表示失败，
 * 四种完全不同的失败原因都提示「无法解析此链接」；这里用 [ParseResult.Failure] 携带
 * [LiveUrlRejectReason]，页面可对「平台已下架」「短链失败」给出可行动提示。
 */
class ParseController(
    private val resolver: LiveLinkResolver = defaultResolver,
) {

    /** 解析结果。 */
    sealed interface ParseResult {
        data class Success(val site: Site, val roomId: String) : ParseResult
        data class Failure(
            val reason: LiveUrlRejectReason,
            val message: String,
        ) : ParseResult
    }

    /**
     * 解析一段输入（可以是纯链接，也可以是含链接的分享文案）。
     *
     * 永不抛异常：网络/解析异常统一折算为 [LiveUrlRejectReason.SHORT_LINK_FAILED]。
     */
    suspend fun parse(input: String): ParseResult {
        val outcome = try {
            resolver.resolve(input)
        } catch (e: Exception) {
            Log.logPrint(e)
            return failure(LiveUrlRejectReason.SHORT_LINK_FAILED)
        }
        return when (outcome) {
            is LiveUrlOutcome.Room -> {
                val site = Sites.allSites[outcome.siteId]
                if (site == null) {
                    failure(LiveUrlRejectReason.UNKNOWN_HOST)
                } else {
                    ParseResult.Success(site, outcome.roomId)
                }
            }
            is LiveUrlOutcome.Rejected -> failure(outcome.reason)
            // 解析器不会在收尾阶段返回短链（深度上限内必然收敛到 Room/Rejected），兜底按失败处理
            is LiveUrlOutcome.ShortLink -> failure(LiveUrlRejectReason.SHORT_LINK_FAILED)
        }
    }

    /** 获取房间详情与清晰度列表（供 UI 弹清晰度选择）。 */
    suspend fun loadDetailAndQualities(
        roomId: String,
        site: Site,
    ): Pair<LiveRoomDetail, List<LivePlayQuality>>? {
        return try {
            val detail = withContext(Dispatchers.IO) { site.liveSite.roomDetail(roomId) }
            val qualities = withContext(Dispatchers.IO) { site.liveSite.playQualities(detail) }
            Pair(detail, qualities)
        } catch (e: Exception) {
            Log.logPrint(e)
            null
        }
    }

    /** 获取指定清晰度的播放直链（供 UI 弹线路选择）。 */
    suspend fun loadPlayUrls(
        roomId: String,
        site: Site,
        detail: LiveRoomDetail,
        quality: LivePlayQuality,
    ): LivePlayUrl? {
        return try {
            withContext(Dispatchers.IO) { site.liveSite.playUrls(detail, quality) }
        } catch (e: Exception) {
            Log.logPrint(e)
            null
        }
    }

    private fun failure(reason: LiveUrlRejectReason): ParseResult.Failure =
        ParseResult.Failure(reason, messageOf(reason))

    companion object {
        /**
         * 默认解析器（进程内复用同一个 OkHttp 客户端；解析页多次进出不必重建连接池）。
         * 短链客户端单独持有，不与带 Cookie 的全局 [com.kotlin_livez.core.common.HttpClient] 混用。
         */
        private val defaultResolver: LiveLinkResolver by lazy {
            LiveLinkResolver(OkHttpLiveLinkFetcher())
        }

        /**
         * 失败原因 → 用户文案。
         *
         * 「暂不支持该平台」是产品要求（快手已整体移除）：识别到快手域名时给出明确答复，
         * 而不是让用户以为链接写错了。
         */
        fun messageOf(reason: LiveUrlRejectReason): String = when (reason) {
            LiveUrlRejectReason.EMPTY -> "链接不能为空"
            LiveUrlRejectReason.PLATFORM_UNSUPPORTED -> "暂不支持该平台"
            LiveUrlRejectReason.SHORT_LINK_FAILED -> "短链解析失败，请检查网络后重试"
            LiveUrlRejectReason.NOT_HTTP,
            LiveUrlRejectReason.UNKNOWN_HOST,
            LiveUrlRejectReason.MISSING_ROOM_ID,
            LiveUrlRejectReason.INVALID_ROOM_ID,
            -> "无法解析此链接"
        }
    }
}
