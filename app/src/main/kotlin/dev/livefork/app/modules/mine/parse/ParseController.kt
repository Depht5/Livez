package dev.livefork.app.modules.mine.parse

import dev.livefork.app.app.Constant
import dev.livefork.app.app.Log
import dev.livefork.app.app.Site
import dev.livefork.app.app.Sites
import dev.livefork.core.common.KuaishouLiveLink
import dev.livefork.core.model.LivePlayQuality
import dev.livefork.core.model.LivePlayUrl
import dev.livefork.core.model.LiveRoomDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 直播链接解析/直链获取控制器 —— 对应 Dart `ParseController`
 * （lib/modules/mine/parse/parse_controller.dart）。
 * 弹出选择清晰度/线路的对话框与剪贴板复制由 UI 层处理，此处提供数据方法。
 */
class ParseController {

    companion object {
        private const val MAX_REDIRECTS = 5
        private val ROOM_URL_REGEX = Regex("https?://[^\\s<>，。！？、；：]+", RegexOption.IGNORE_CASE)
        private val TRAILING_PUNCT_REGEX = Regex("[，。！？、；：,.;:!?]+$")
        private val TRAILING_BRACKETS_REGEX = Regex("""[)\]}>'"）】》”’]+$""")

        /** 从文本中提取 http(s) 链接（对应 Dart extractHttpUrl）。 */
        fun extractHttpUrl(text: String): String {
            return ROOM_URL_REGEX.find(text)?.value.orEmpty()
                .replaceFirst(TRAILING_PUNCT_REGEX, "")
                .replaceFirst(TRAILING_BRACKETS_REGEX, "")
        }
    }

    /** 重定向跟随客户端（对应 Dart redirectClient；不跟随重定向，手动解析 Location）。 */
    private val redirectClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    /** 解析结果（对应 Dart parse 返回的 [id, site] 列表）。 */
    data class ParseResult(val roomId: String, val site: Site)

    /** 解析链接为直播间（对应 Dart parse）。null 表示无法解析。 */
    suspend fun parse(url: String): ParseResult? {
        var target = url
        val extracted = extractHttpUrl(target)
        if (extracted.isNotEmpty()) target = extracted

        if (target.contains("bilibili.com")) {
            val id = Regex("bilibili\\.com/([\\d\\w]+)").find(target)?.groupValues?.get(1).orEmpty()
            return ParseResult(id, Sites.allSites[Constant.kBiliBili] ?: return null)
        }
        if (target.contains("b23.tv")) {
            val u = Regex("https?://b23\\.tv/[0-9a-zA-Z]+").find(target)?.value.orEmpty()
            val location = getLocation(u)
            return parse(location)
        }
        if (target.contains("douyu.com")) {
            var id = if (target.contains("topic")) {
                Regex("[?&]rid=(\\d+)").find(target)?.groupValues?.get(1).orEmpty()
            } else {
                Regex("douyu\\.com/([\\d\\w]+)").find(target)?.groupValues?.get(1).orEmpty()
            }
            return ParseResult(id, Sites.allSites[Constant.kDouyu] ?: return null)
        }
        if (target.contains("huya.com")) {
            val id = Regex("huya\\.com/([\\d\\w]+)").find(target)?.groupValues?.get(1).orEmpty()
            return ParseResult(id, Sites.allSites[Constant.kHuya] ?: return null)
        }
        if (target.contains("live.douyin.com")) {
            val id = Regex("live\\.douyin\\.com/([\\d\\w]+)").find(target)?.groupValues?.get(1).orEmpty()
            return ParseResult(id, Sites.allSites[Constant.kDouyin] ?: return null)
        }
        if (target.contains("webcast.amemv.com")) {
            val id = Regex("reflow/(\\d+)").find(target)?.groupValues?.get(1).orEmpty()
            return ParseResult(id, Sites.allSites[Constant.kDouyin] ?: return null)
        }
        if (target.contains("v.douyin.com")) {
            val u = Regex("https?://v\\.douyin\\.com/[\\w-]+/?", RegexOption.IGNORE_CASE)
                .find(target)?.value.orEmpty()
            val location = getLocation(u)
            return parse(location)
        }
        val kuaishouRoomId = resolveKuaishouRoomId(target)
        if (kuaishouRoomId.isNotEmpty()) {
            return ParseResult(kuaishouRoomId, Sites.allSites[Constant.kKuaishou] ?: return null)
        }
        return null
    }

    /** 解析快手直播间链接（对应 Dart resolveKuaishouRoomId）。 */
    suspend fun resolveKuaishouRoomId(value: String): String {
        val initial = KuaishouLiveLink.parseHttpUrl(value) ?: return ""
        val directRoomId = KuaishouLiveLink.roomIdFromUri(initial)
        if (directRoomId != null) return directRoomId
        if (!KuaishouLiveLink.isShortLink(initial)) return ""

        var current = initial
        val visited = mutableSetOf<String>()
        return withContext(Dispatchers.IO) {
            try {
                for (redirectCount in 0 until MAX_REDIRECTS) {
                    if (!visited.add(current.toString())) return@withContext ""
                    val response = redirectClient.newCall(
                        Request.Builder().url(current.toURL()).build(),
                    ).execute()
                    response.use { resp ->
                        val statusCode = resp.code
                        if (statusCode < 300 || statusCode >= 400) return@withContext ""
                        val location = resp.header("location")
                        if (location.isNullOrBlank()) return@withContext ""
                        val next = current.resolve(location.trim())
                        if (!KuaishouLiveLink.isTrustedRedirectTarget(next)) return@withContext ""
                        val roomId = KuaishouLiveLink.roomIdFromUri(next)
                        if (roomId != null) return@withContext roomId
                        current = next
                    }
                }
                ""
            } catch (e: Exception) {
                Log.logPrint(e)
                ""
            }
        }
    }

    /** 跟随重定向获取最终地址（对应 Dart getLocation）。 */
    suspend fun getLocation(url: String): String {
        val parsed = runCatching { URI(url) }.getOrNull()
            ?: return ""
        if (!parsed.isAbsolute) return ""
        var current = parsed
        return withContext(Dispatchers.IO) {
            try {
                for (redirectCount in 0 until MAX_REDIRECTS) {
                    val response = redirectClient.newCall(
                        Request.Builder().url(current.toURL()).build(),
                    ).execute()
                    response.use { resp ->
                        val statusCode = resp.code
                        if (statusCode < 300 || statusCode >= 400) {
                            return@withContext current.toString()
                        }
                        val location = resp.header("location")
                        if (location.isNullOrBlank()) return@withContext ""
                        current = current.resolve(location.trim())
                    }
                }
                current.toString()
            } catch (e: Exception) {
                Log.logPrint(e)
                current.toString()
            }
        }
    }

    /** 获取房间详情与清晰度列表（供 UI 层弹清晰度选择）。 */
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

    /** 获取指定清晰度的播放直链（供 UI 层弹线路选择）。 */
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
}