package dev.livefork.core.site

import dev.livefork.core.common.CoreError
import dev.livefork.core.common.CoreLog
import dev.livefork.core.common.HttpClient
import dev.livefork.core.common.at
import dev.livefork.core.common.bool
import dev.livefork.core.common.intv
import dev.livefork.core.common.str
import dev.livefork.core.danmaku.BiliBiliDanmaku
import dev.livefork.core.danmaku.BiliBiliDanmakuArgs
import dev.livefork.core.danmaku.LiveDanmaku
import dev.livefork.core.interfaces.LiveSite
import dev.livefork.core.model.LiveAnchorItem
import dev.livefork.core.model.LiveCategory
import dev.livefork.core.model.LiveCategoryResult
import dev.livefork.core.model.LiveContributionRankItem
import dev.livefork.core.model.LivePlayQuality
import dev.livefork.core.model.LivePlayUrl
import dev.livefork.core.model.LiveRoomDetail
import dev.livefork.core.model.LiveRoomItem
import dev.livefork.core.model.LiveSearchAnchorResult
import dev.livefork.core.model.LiveSearchRoomResult
import dev.livefork.core.model.LiveSubCategory
import dev.livefork.core.model.LiveSuperChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.min

/**
 * B站直播站点实现。
 * 对应 Dart `BiliBiliSite`（lib/src/bilibili_site.dart）。站点 id/name 与冻结文案保持不变；
 * 方法职责一一对应（Dart 拼写错误 getCategores/getPlayQualites 在此修正为 categories/playQualities）。
 */
class BiliBiliSite : LiveSite() {

    override var id: String = "bilibili"
    override var name: String = "哔哩哔哩直播"

    var cookie: String = ""
    var userId: Long = 0L
    private var accessId: String = ""

    override fun danmaku(): LiveDanmaku = BiliBiliDanmaku()

    companion object {
        private const val kDefaultUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.0.0"
        private const val kDefaultReferer = "https://live.bilibili.com/"

        private val mixinKeyEncTab = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
            27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
            37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
            22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
        )

        private const val kMinPlayInfoIntervalMs = 450L
    }

    private var buvid3: String = ""
    private var buvid4: String = ""

    @Volatile
    private var lastPlayInfoRequestAt: Long = 0L

    private val playInfoMutex = kotlinx.coroutines.sync.Mutex()

    private suspend fun getHeader(): Map<String, Any?> {
        if (buvid3.isEmpty()) {
            val buvidInfo = getBuvid()
            buvid3 = buvidInfo["b_3"] ?: ""
            buvid4 = buvidInfo["b_4"] ?: ""
        }
        return if (cookie.isEmpty()) {
            mapOf(
                "user-agent" to kDefaultUserAgent,
                "referer" to kDefaultReferer,
                "cookie" to "buvid3=$buvid3;buvid4=$buvid4;",
            )
        } else {
            mapOf(
                "cookie" to (if (cookie.contains("buvid3")) cookie else "$cookie;buvid3=$buvid3;buvid4=$buvid4;"),
                "user-agent" to kDefaultUserAgent,
                "referer" to kDefaultReferer,
            )
        }
    }

    override suspend fun categories(): List<LiveCategory> {
        val categories = mutableListOf<LiveCategory>()
        val result = HttpClient.getJson(
            "https://api.live.bilibili.com/room/v1/Area/getList",
            query = mapOf("need_entrance" to 1, "parent_id" to 0),
            header = getHeader(),
        )
        val data = result?.jsonObject?.get("data")?.jsonArray ?: return categories
        for (item in data) {
            val obj = item.jsonObject
            val subs = mutableListOf<LiveSubCategory>()
            val list = obj["list"]?.jsonArray
            if (list != null) {
                for (sub in list) {
                    val s = sub.jsonObject
                    subs.add(
                        LiveSubCategory(
                            id = s["id"].str().orEmpty(),
                            name = s["name"].str().orEmpty(),
                            parentId = s["parent_id"].str().orEmpty(),
                            pic = "${s["pic"].str().orEmpty()}@100w.png",
                        )
                    )
                }
            }
            categories.add(
                LiveCategory(
                    children = subs,
                    id = obj["id"].str().orEmpty(),
                    name = obj["name"].str().orEmpty(),
                )
            )
        }
        return categories
    }

    override suspend fun categoryRooms(category: LiveSubCategory, page: Int): LiveCategoryResult {
        val result = HttpClient.getJson(
            "https://api.live.bilibili.com/room/v1/Area/getRoomList",
            query = mapOf(
                "platform" to "web",
                "parent_area_id" to category.parentId,
                "area_id" to category.id,
                "page" to page,
                "page_size" to 30,
            ),
            header = getHeader(),
        )
        val data = result?.jsonObject?.get("data")?.jsonArray ?: JsonArray(emptyList())
        val hasMore = data.size >= 30
        val items = data.mapNotNull { item ->
            val obj = item.jsonObject
            val cover = obj["cover"].str().ifEmpty {
                obj["user_cover"].str().ifEmpty { obj["system_cover"].str().orEmpty() }
            }
            LiveRoomItem(
                roomId = obj["roomid"].str().orEmpty(),
                title = obj["title"].str().orEmpty(),
                cover = if (cover.isEmpty()) "" else "$cover@400w.jpg",
                userName = obj["uname"].str().orEmpty(),
                online = obj["online"].intv() ?: 0,
            )
        }
        return LiveCategoryResult(hasMore = hasMore, items = items)
    }

    override suspend fun playQualities(detail: LiveRoomDetail): List<LivePlayQuality> {
        val qualities = mutableListOf<LivePlayQuality>()
        val result = getRoomPlayInfo(
            mapOf(
                "room_id" to detail.roomId,
                "protocol" to "0,1",
                "format" to "0,1,2",
                "codec" to "0,1",
                "platform" to "web",
            )
        )
        val playUrl = readBilibiliPlayUrl(result, detail.roomId)
        val qualitiesMap = HashMap<Int, String>()
        (playUrl["g_qn_desc"]?.jsonArray ?: JsonArray(emptyList())).forEach { item ->
            val qn = item.jsonObject["qn"].intv() ?: 0
            qualitiesMap[qn] = item.jsonObject["desc"].str().orEmpty()
        }
        val streams = playUrl["stream"]?.jsonArray ?: JsonArray(emptyList())
        val formats = if (streams.isEmpty()) JsonArray(emptyList()) else streams.first().jsonObject["format"]?.jsonArray ?: JsonArray(emptyList())
        val codecs = if (formats.isEmpty()) JsonArray(emptyList()) else formats.first().jsonObject["codec"]?.jsonArray ?: JsonArray(emptyList())
        val accepted = if (codecs.isEmpty()) JsonArray(emptyList()) else codecs.first().jsonObject["accept_qn"]?.jsonArray ?: JsonArray(emptyList())
        for (item in accepted) {
            val qn = item.intv()
            qualities.add(LivePlayQuality(quality = qualitiesMap[qn] ?: "未知清晰度", data = qn))
        }
        if (qualities.isEmpty()) {
            CoreLog.w("B站播放信息未返回清晰度：roomId=${detail.roomId}")
            throw CoreError("B站暂时无法获取播放清晰度，请稍后重试")
        }
        return qualities
    }

    private fun readBilibiliPlayUrl(result: JsonElement?, roomId: String): JsonObject {
        val playUrl = result?.jsonObject?.get("data")?.jsonObject
            ?.get("playurl_info")?.jsonObject?.get("playurl")?.jsonObject
        if (playUrl != null) {
            return playUrl
        }
        CoreLog.w("B站播放信息响应结构异常：roomId=$roomId")
        throw CoreError("B站播放信息响应异常，请稍后重试")
    }

    override suspend fun playUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl {
        val urls = mutableListOf<String>()
        val result = getRoomPlayInfo(
            mapOf(
                "room_id" to detail.roomId,
                "protocol" to "0,1",
                "format" to "0,2",
                "codec" to "0",
                "platform" to "web",
                "qn" to (quality.data ?: 0),
            )
        )
        val playUrl = result?.jsonObject?.get("data")?.jsonObject
            ?.get("playurl_info")?.jsonObject?.get("playurl")?.jsonObject
        val streamList = playUrl?.get("stream")?.jsonArray ?: JsonArray(emptyList())
        for (streamItem in streamList) {
            val formatList = streamItem.jsonObject["format"]?.jsonArray ?: JsonArray(emptyList())
            for (formatItem in formatList) {
                val codecList = formatItem.jsonObject["codec"]?.jsonArray ?: JsonArray(emptyList())
                for (codecItem in codecList) {
                    val codecObj = codecItem.jsonObject
                    val urlList = codecObj["url_info"]?.jsonArray ?: JsonArray(emptyList())
                    val baseUrl = codecObj["base_url"].str().orEmpty()
                    for (urlItem in urlList) {
                        val u = urlItem.jsonObject
                        urls.add("${u["host"].str().orEmpty()}$baseUrl${u["extra"].str().orEmpty()}")
                    }
                }
            }
        }
        // 对链接排序，包含 mcdn 的在后（对应 Dart 排序）
        urls.sortWith(compareBy { if (it.contains("mcdn")) 1 else 0 })
        return LivePlayUrl(
            urls = urls,
            headers = mapOf(
                "referer" to "https://live.bilibili.com",
                "user-agent" to kDefaultUserAgent,
            ),
        )
    }

    private suspend fun getRoomPlayInfo(queryParameters: Map<String, Any?>): JsonElement? {
        val retryDelaysMs = listOf(800L, 1600L)
        for (attempt in 0..retryDelaysMs.size) {
            try {
                return throttledPlayInfoRequest {
                    HttpClient.getJson(
                        "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo",
                        query = queryParameters,
                        header = getHeader(),
                    )
                }
            } catch (e: Exception) {
                if (e is CoreError && e.statusCode == 429 && attempt < retryDelaysMs.size) {
                    val delayMs = retryDelaysMs[attempt]
                    CoreLog.w(
                        "B站播放信息接口触发 429，${delayMs}ms 后重试：" +
                            "roomId=${queryParameters["room_id"]} attempt=${attempt + 1}"
                    )
                    delay(delayMs)
                    continue
                }
                CoreLog.w(
                    "B站播放信息获取失败：roomId=${queryParameters["room_id"]} " +
                        "attempt=${attempt + 1}/${retryDelaysMs.size + 1} error=$e"
                )
                throw e
            }
        }
        throw CoreError("B站播放信息接口重试失败")
    }

    private suspend fun <T> throttledPlayInfoRequest(action: suspend () -> T): T =
        playInfoMutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastPlayInfoRequestAt
            if (elapsed < kMinPlayInfoIntervalMs) {
                delay(kMinPlayInfoIntervalMs - elapsed)
            }
            lastPlayInfoRequestAt = System.currentTimeMillis()
            action()
        }

    override suspend fun recommendRooms(page: Int): LiveCategoryResult {
        val baseUrl = "https://api.live.bilibili.com/xlive/web-interface/v1/second/getListByArea"
        val url = "$baseUrl?platform=web&sort=online&page_size=30&page=$page"
        val queryParams = getWbiSign(url)

        val result = HttpClient.getJson(baseUrl, query = queryParams, header = getHeader())
        val list = result?.jsonObject?.get("data")?.jsonObject?.get("list")?.jsonArray ?: JsonArray(emptyList())
        val hasMore = list.isNotEmpty()
        val items = list.mapNotNull { item ->
            val obj = item.jsonObject
            LiveRoomItem(
                roomId = obj["roomid"].str().orEmpty(),
                title = obj["title"].str().orEmpty(),
                cover = "${obj["cover"].str().orEmpty()}@400w.jpg",
                userName = obj["uname"].str().orEmpty(),
                online = obj["online"].intv() ?: 0,
            )
        }
        return LiveCategoryResult(hasMore = hasMore, items = items)
    }

    override suspend fun roomDetail(roomId: String): LiveRoomDetail {
        val roomInfo = getRoomInfo(roomId)
        val roomInfoObj = roomInfo?.get("room_info")?.jsonObject
        val realRoomId = roomInfoObj?.get("room_id").str().orEmpty().ifEmpty { roomId }

        val danmuInfoBaseUrl = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo"
        val danmuInfoUrl = "$danmuInfoBaseUrl?id=$realRoomId"
        val queryParams = getWbiSign(danmuInfoUrl)
        var danmuData: JsonObject? = null
        val serverHosts = mutableListOf<String>()
        try {
            val roomDanmakuResult = HttpClient.getJson(danmuInfoBaseUrl, query = queryParams, header = getHeader())
            val data = roomDanmakuResult?.jsonObject?.get("data")
            if (data is JsonObject) {
                danmuData = data
                (data["host_list"]?.jsonArray ?: JsonArray(emptyList())).forEach { e ->
                    val host = e.jsonObject["host"].str().orEmpty()
                    if (host.isNotEmpty()) serverHosts.add(host)
                }
            } else {
                CoreLog.w(
                    "B站弹幕信息为空：roomId=$realRoomId " +
                        "code=${roomDanmakuResult?.jsonObject?.get("code").str()} " +
                        "message=${roomDanmakuResult?.jsonObject?.get("message").str()}"
                )
            }
        } catch (e: Exception) {
            CoreLog.w("B站弹幕信息获取失败：roomId=$realRoomId error=$e")
        }

        val anchorBase = roomInfo?.get("anchor_info")?.jsonObject?.get("base_info")?.jsonObject
        val liveStartTime = roomInfoObj?.get("live_start_time").str()
        return LiveRoomDetail(
            roomId = realRoomId,
            title = roomInfoObj?.get("title").str().orEmpty(),
            cover = roomInfoObj?.get("cover").str().orEmpty(),
            userName = anchorBase?.get("uname").str().orEmpty(),
            userAvatar = "${anchorBase?.get("face").str().orEmpty()}@100w.jpg",
            online = roomInfoObj?.get("online").intv() ?: 0,
            status = (roomInfoObj?.get("live_status").intv() ?: 0) == 1,
            url = "https://live.bilibili.com/$roomId",
            introduction = roomInfoObj?.get("description").str().orEmpty(),
            notice = "",
            danmakuData = BiliBiliDanmakuArgs(
                roomId = realRoomId.toIntOrNull() ?: 0,
                uid = userId,
                token = danmuData?.get("token").str().orEmpty(),
                serverHost = serverHosts.firstOrNull() ?: "broadcastlv.chat.bilibili.com",
                buvid = buvid3,
                cookie = cookie,
            ),
            showTime = liveStartTime,
            categoryId = roomInfoObj?.get("area_id").str(),
            categoryName = roomInfoObj?.get("area_name").str(),
            categoryParentId = roomInfoObj?.get("parent_area_id").str(),
            categoryParentName = roomInfoObj?.get("parent_area_name").str(),
        )
    }

    private suspend fun getRoomInfo(roomId: String): JsonObject? {
        val url = "https://api.live.bilibili.com/xlive/web-room/v1/index/getInfoByRoom?room_id=$roomId"
        val queryParams = getWbiSign(url)
        val result = HttpClient.getJson(
            "https://api.live.bilibili.com/xlive/web-room/v1/index/getInfoByRoom",
            query = queryParams,
            header = getHeader(),
        )
        return result?.jsonObject?.get("data")?.jsonObject
    }

    override suspend fun searchRooms(keyword: String, page: Int): LiveSearchRoomResult {
        val result = HttpClient.getJson(
            "https://api.bilibili.com/x/web-interface/search/type?context=&search_type=live&cover_type=user_cover",
            query = mapOf(
                "order" to "",
                "keyword" to keyword,
                "category_id" to "",
                "__refresh__" to "",
                "_extra" to "",
                "highlight" to 0,
                "single_column" to 0,
                "page" to page,
            ),
            header = getHeader(),
        )
        val items = (result?.jsonObject?.get("data")?.jsonObject?.get("result")?.jsonObject
            ?.get("live_room")?.jsonArray ?: JsonArray(emptyList())).mapNotNull { item ->
            val obj = item.jsonObject
            val title = obj["title"].str().orEmpty().replace(Regex("<.*?em.*?>"), "")
            LiveRoomItem(
                roomId = obj["roomid"].str().orEmpty(),
                title = title,
                cover = "https:${obj["cover"].str().orEmpty()}@400w.jpg",
                userName = obj["uname"].str().orEmpty(),
                online = obj["online"].intv() ?: 0,
            )
        }
        return LiveSearchRoomResult(hasMore = items.size >= 40, items = items)
    }

    override suspend fun searchAnchors(keyword: String, page: Int): LiveSearchAnchorResult {
        val result = HttpClient.getJson(
            "https://api.bilibili.com/x/web-interface/search/type?context=&search_type=live_user&cover_type=user_cover",
            query = mapOf(
                "order" to "",
                "keyword" to keyword,
                "category_id" to "",
                "__refresh__" to "",
                "_extra" to "",
                "highlight" to 0,
                "single_column" to 0,
                "page" to page,
            ),
            header = getHeader(),
        )
        val items = (result?.jsonObject?.get("data")?.jsonObject?.get("result")?.jsonArray
            ?: JsonArray(emptyList())).mapNotNull { item ->
            val obj = item.jsonObject
            val uname = obj["uname"].str().orEmpty().replace(Regex("<.*?em.*?>"), "")
            LiveAnchorItem(
                roomId = obj["roomid"].str().orEmpty(),
                avatar = "https:${obj["uface"].str().orEmpty()}@400w.jpg",
                userName = uname,
                liveStatus = obj["is_live"].bool() ?: false,
            )
        }
        return LiveSearchAnchorResult(hasMore = items.size >= 40, items = items)
    }

    override suspend fun liveStatus(roomId: String): Boolean {
        val result = HttpClient.getJson(
            "https://api.live.bilibili.com/room/v1/Room/get_info",
            query = mapOf("room_id" to roomId),
            header = getHeader(),
        )
        return (result?.jsonObject?.get("data")?.jsonObject?.get("live_status").intv() ?: 0) == 1
    }

    override suspend fun superChatMessage(roomId: String, detail: LiveRoomDetail?): List<LiveSuperChatMessage> {
        val result = HttpClient.getJson(
            "https://api.live.bilibili.com/av/v1/SuperChat/getMessageList",
            query = mapOf("room_id" to roomId),
            header = getHeader(),
        )
        val list = result?.jsonObject?.get("data")?.jsonObject?.get("list")?.jsonArray ?: JsonArray(emptyList())
        return list.mapNotNull { item ->
            val obj = item.jsonObject
            val userInfo = obj["user_info"]?.jsonObject
            LiveSuperChatMessage(
                backgroundBottomColor = obj["background_bottom_color"].str().orEmpty(),
                backgroundColor = obj["background_color"].str().orEmpty(),
                endTime = Instant.ofEpochMilli(obj["end_time"].intv()?.toLong()?.times(1000) ?: 0L),
                face = "${userInfo?.get("face").str().orEmpty()}@200w.jpg",
                message = obj["message"].str().orEmpty(),
                price = obj["price"].intv() ?: 0,
                startTime = Instant.ofEpochMilli(obj["start_time"].intv()?.toLong()?.times(1000) ?: 0L),
                userName = userInfo?.get("uname").str().orEmpty(),
            )
        }
    }

    override suspend fun contributionRank(roomId: String, detail: LiveRoomDetail?): List<LiveContributionRankItem> {
        val roomInfo = getRoomInfo(roomId) ?: return emptyList()
        val userRankItems = roomInfo["room_rank_info"]?.jsonObject
            ?.get("user_rank_entry")?.jsonObject
            ?.get("user_contribution_rank_entry")?.jsonObject
            ?.get("item")?.jsonArray
        if (!userRankItems.isNullOrEmpty()) {
            return userRankItems.map { mapContributionRankItem(it) }
        }

        val roomInfoObj = roomInfo["room_info"]?.jsonObject
        val uid = roomInfoObj?.get("uid").str().orEmpty()
        val realRoomId = roomInfoObj?.get("room_id").str().ifEmpty { roomId }
        if (uid.isEmpty()) return emptyList()

        val result = HttpClient.getJson(
            "https://api.live.bilibili.com/xlive/general-interface/v1/rank/queryContributionRank",
            query = mapOf("ruid" to uid, "room_id" to realRoomId, "page" to 1, "page_size" to 50),
            header = getHeader(),
        )
        val items = result?.jsonObject?.get("data")?.jsonObject?.get("item")?.jsonArray ?: JsonArray(emptyList())
        return items.map { mapContributionRankItem(it) }
    }

    private fun mapContributionRankItem(item: JsonElement): LiveContributionRankItem {
        val obj = item.jsonObject
        val medalInfo = obj["medal_info"] ?: obj["uinfo"]?.jsonObject?.get("medal")
        val wealthLevelRaw = obj["wealth_level"] ?: obj["uinfo"]?.jsonObject?.get("wealth")?.jsonObject?.get("level")
        val wealthLevel = wealthLevelRaw.str()?.toIntOrNull()
        val guardLevel = obj["guard_level"].intv() ?: 0
        val base = obj["uinfo"]?.jsonObject?.get("base")?.jsonObject

        return LiveContributionRankItem(
            rank = obj["rank"].intv() ?: 0,
            userName = obj["name"].str().ifEmpty { base?.get("name").str().orEmpty() },
            avatar = obj["face"].str().ifEmpty { base?.get("face").str().orEmpty() },
            scoreText = obj["score"].str().ifEmpty { "0" },
            userLevel = wealthLevel,
            userLevelText = if (wealthLevel == null || wealthLevel <= 0) null else "财富 $wealthLevel",
            fansLevel = medalInfo?.jsonObject?.let {
                (it["level"] ?: it["medal_level"]).str()?.toIntOrNull()
            },
            fansName = medalInfo?.jsonObject?.let {
                it["name"].str().ifEmpty { it["medal_name"].str().orEmpty() }
            },
            scoreDetail = if (guardLevel > 0) "舰队 $guardLevel" else null,
            fansIcon = medalInfo?.jsonObject?.get("icon").str(),
        )
    }

    /** 获取 buvid3 和 buvid4。 */
    private suspend fun getBuvid(): Map<String, String> {
        return try {
            if (cookie.contains("buvid3")) {
                mapOf(
                    "b_3" to Regex("buvid3=(.*?);").find(cookie)?.groupValues?.get(1).orEmpty(),
                    "b_4" to Regex("buvid4=(.*?);").find(cookie)?.groupValues?.get(1).orEmpty(),
                )
            } else {
                val result = HttpClient.getJson(
                    "https://api.bilibili.com/x/frontend/finger/spi",
                    query = emptyMap(),
                    header = mapOf(
                        "user-agent" to kDefaultUserAgent,
                        "referer" to kDefaultReferer,
                        "cookie" to cookie,
                    ),
                )?.jsonObject?.get("data")?.jsonObject
                mapOf(
                    "b_3" to result?.get("b_3").str().orEmpty(),
                    "b_4" to result?.get("b_4").str().orEmpty(),
                )
            }
        } catch (e: Exception) {
            mapOf("b_3" to "", "b_4" to "")
        }
    }

    private var kImgKey = ""
    private var kSubKey = ""

    private suspend fun getWbiKeys(): Pair<String, String> {
        if (kImgKey.isNotEmpty() && kSubKey.isNotEmpty()) {
            return kImgKey to kSubKey
        }
        val resp = HttpClient.getJson("https://api.bilibili.com/x/web-interface/nav", header = getHeader())
        val wbiImg = resp?.jsonObject?.get("data")?.jsonObject?.get("wbi_img")?.jsonObject ?: return "" to ""
        val imgUrl = wbiImg["img_url"].str().orEmpty()
        val subUrl = wbiImg["sub_url"].str().orEmpty()
        val imgKey = imgUrl.substringAfterLast('/').substringBefore('.')
        val subKey = subUrl.substringAfterLast('/').substringBefore('.')
        kImgKey = imgKey
        kSubKey = subKey
        return imgKey to subKey
    }

    private fun getMixinKey(origin: String): String {
        val sb = StringBuilder()
        for (i in mixinKeyEncTab) {
            if (i < origin.length) sb.append(origin[i])
        }
        return sb.toString().substring(0, min(32, sb.length))
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private suspend fun getWbiSign(url: String): Map<String, Any?> {
        val (imgKey, subKey) = getWbiKeys()
        val mixinKey = getMixinKey(imgKey + subKey)
        val currentTime = System.currentTimeMillis() / 1000

        val queryParams = LinkedHashMap<String, String>()
        val urlObj = java.net.URI(url).rawQuery
        if (urlObj != null) {
            urlObj.split("&").forEach { pair ->
                val idx = pair.indexOf('=')
                val k = if (idx >= 0) pair.substring(0, idx) else pair
                val v = if (idx >= 0) pair.substring(idx + 1) else ""
                queryParams[k] = v
            }
        }
        queryParams["wts"] = currentTime.toString()

        // 按 key 重排并过滤 "!'()*"
        val map = LinkedHashMap<String, String>()
        queryParams.keys.sorted().forEach { key ->
            val value = queryParams[key].orEmpty().filter { c -> c !in "!'()*" }
            map[key] = value
        }

        val query = map.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val wbiSign = md5("$query$mixinKey")
        queryParams["w_rid"] = wbiSign
        return queryParams
    }

    /** 获取 access_id（用于账号相关）。 */
    private suspend fun getAccessId(): String {
        if (accessId.isNotEmpty()) return accessId
        val resp = HttpClient.getText(
            "https://live.bilibili.com/lol",
            query = emptyMap(),
            header = getHeader(),
        )
        val id = Regex("\"access_id\":\"(.*?)\"").find(resp)?.groupValues?.get(1)?.replace("\\", "")
        accessId = id.orEmpty()
        return accessId
    }
}
