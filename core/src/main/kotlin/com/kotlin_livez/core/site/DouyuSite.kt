package com.kotlin_livez.core.site

import com.kotlin_livez.core.common.CoreError
import com.kotlin_livez.core.common.HttpClient
import com.kotlin_livez.core.common.intv
import com.kotlin_livez.core.common.str
import com.kotlin_livez.core.danmaku.DouyuDanmaku
import com.kotlin_livez.core.danmaku.LiveDanmaku
import com.kotlin_livez.core.interfaces.LiveSite
import com.kotlin_livez.core.model.DouyuPlayData
import com.kotlin_livez.core.model.LiveAnchorItem
import com.kotlin_livez.core.model.LiveCategory
import com.kotlin_livez.core.model.LiveCategoryResult
import com.kotlin_livez.core.model.LiveContributionRankItem
import com.kotlin_livez.core.model.LivePlayQuality
import com.kotlin_livez.core.model.LivePlayUrl
import com.kotlin_livez.core.model.LiveRoomDetail
import com.kotlin_livez.core.model.LiveRoomItem
import com.kotlin_livez.core.model.LiveSearchAnchorResult
import com.kotlin_livez.core.model.LiveSearchRoomResult
import com.kotlin_livez.core.model.LiveSubCategory
import com.kotlin_livez.core.model.LiveSuperChatMessage
import com.kotlin_livez.core.scripts.DouyuSign
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToInt

/**
 * 斗鱼直播站点实现。
 * 对应 Dart `DouyuSite`（lib/src/douyu_site.dart）。站点 id/name 与冻结文案保持一致；
 * 方法职责一一对应（Dart 拼写 `getCategores`/`getPlayQualites` 在此修正为 categories/playQualities）。
 * 播放签名依赖服务器动态 JS（DouyuSign + Rhino），与 Dart 的 dart_quickjs 行为一致。
 */
class DouyuSite : LiveSite() {

    override var id: String = "douyu"
    override var name: String = "斗鱼直播"

    /**
     * 可选登录 Cookie（对应 Dart `DouyuSite.cookie`）。
     * 匿名播放仍然可用，但斗鱼会对匿名高画质流做时长限制（约 10 分钟后断流）；
     * 配置登录后的 www.douyu.com Cookie 可恢复高画质长期播放。
     */
    var cookie: String = ""

    override fun danmaku(): LiveDanmaku = DouyuDanmaku()

    companion object {
        /** 播放/房间接口的 UA（对应 Dart `_requestHeaders` 的 Chrome/126 UA）。 */
        private const val kPlayUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** betard 房间信息接口的 UA（对应 Dart `_getRoomInfo`）。 */
        private const val kDefaultUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1823.43"

        /** 搜索请求的 UA。 */
        private const val kSearchUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36 Edg/114.0.1823.51"

        /** 贡献榜请求的 UA。 */
        private const val kContributionUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** betard 房间信息请求头（匿名，不注入登录 Cookie —— 与 Dart `_getRoomInfo` 一致）。 */
        private fun betardHeader(roomId: String): Map<String, Any?> = mapOf(
            "referer" to "https://www.douyu.com/$roomId",
            "user-agent" to kDefaultUserAgent,
        )
    }

    /** 基础请求头（对应 Dart `_requestHeaders`）：referer/UA + Cookie 非空时注入登录 Cookie。 */
    private fun requestHeaders(): Map<String, Any?> = buildMap<String, Any?> {
        put("referer", "https://www.douyu.com/")
        put("user-agent", kPlayUserAgent)
        val trimmed = cookie.trim()
        if (trimmed.isNotEmpty()) put("Cookie", trimmed)
    }

    /**
     * 房间维度基础请求头 = 基础头 + 房间 referer
     * （对应 Dart `{..._requestHeaders, 'referer': 'https://www.douyu.com/$roomId'}`）。
     */
    private fun roomRequestHeaders(roomId: String): Map<String, Any?> =
        requestHeaders() + mapOf("referer" to "https://www.douyu.com/$roomId")

    override suspend fun categories(): List<LiveCategory> {
        val categories = mutableListOf<LiveCategory>()
        val result = HttpClient.getJson("https://m.douyu.com/api/cate/list")
        val data = result?.jsonObject?.get("data")?.jsonObject
        val subCateList = data?.get("cate2Info")?.jsonArray ?: JsonArray(emptyList())
        val cate1List = data?.get("cate1Info")?.jsonArray ?: JsonArray(emptyList())
        for (item in cate1List) {
            val obj = item.jsonObject
            val cate1Id = obj["cate1Id"].str()
            val cate1Name = obj["cate1Name"].str()
            val subCategories = mutableListOf<LiveSubCategory>()
            for (sub in subCateList) {
                val s = sub.jsonObject
                if (s["cate1Id"].str() != cate1Id) continue
                subCategories.add(
                    LiveSubCategory(
                        pic = s["icon"].str(),
                        id = s["cate2Id"].str(),
                        parentId = cate1Id,
                        name = s["cate2Name"].str(),
                    )
                )
            }
            categories.add(
                LiveCategory(
                    id = cate1Id,
                    name = cate1Name,
                    children = subCategories,
                )
            )
        }
        // 根据ID排序
        categories.sortBy { it.id.toIntOrNull() ?: 0 }
        return categories
    }

    override suspend fun categoryRooms(category: LiveSubCategory, page: Int): LiveCategoryResult {
        val result = HttpClient.getJson(
            "https://www.douyu.com/gapi/rkc/directory/mixList/2_${category.id}/$page",
            query = emptyMap(),
        )
        val data = result?.jsonObject?.get("data")?.jsonObject
        val items = (data?.get("rl")?.jsonArray ?: JsonArray(emptyList())).mapNotNull { item ->
            val obj = item.jsonObject
            if (obj["type"].intv() != 1) {
                return@mapNotNull null
            }
            LiveRoomItem(
                cover = obj["rs16"].str(),
                online = obj["ol"].intv(),
                roomId = obj["rid"].str(),
                title = obj["rn"].str(),
                userName = obj["nn"].str(),
            )
        }
        val hasMore = page < (data?.get("pgcnt").intv() ?: 0)
        return LiveCategoryResult(hasMore = hasMore, items = items)
    }

    override suspend fun playQualities(detail: LiveRoomDetail): List<LivePlayQuality> {
        val qualities = mutableListOf<LivePlayQuality>()
        // 签名参数每次请求重取：房间停留期间旧签名会过期，复用会让斗鱼对高画质流
        // 返回 timestamp 错误（对应 Dart `_getFreshPlayArgs` 的动机注释）。
        var data = freshPlayArgs(detail.roomId, detail.data?.toString().orEmpty())
        data += "&cdn=&rate=-1&ver=Douyu_223061205&iar=1&ive=1&hevc=0&fa=0"
        val result = HttpClient.postJson(
            "https://www.douyu.com/lapi/live/getH5Play/${detail.roomId}",
            data = formToParams(data),
            header = requestHeaders(),
            formUrlEncoded = true,
        )
        val dataObj = result?.jsonObject?.get("data")?.jsonObject

        // 收集 CDN 列表，scdn 开头的放到最后（对应 Dart 排序）
        val cdns = mutableListOf<String>()
        (dataObj?.get("cdnsWithName")?.jsonArray ?: JsonArray(emptyList())).forEach { item ->
            cdns.add(item.jsonObject["cdn"].str())
        }
        cdns.sortWith(compareBy { if (it.startsWith("scdn")) 1 else 0 })

        val multirates = dataObj?.get("multirates")?.jsonArray ?: JsonArray(emptyList())
        for (item in multirates) {
            val obj = item.jsonObject
            qualities.add(
                LivePlayQuality(
                    quality = obj["name"].str(),
                    data = DouyuPlayData(rate = obj["rate"].intv(), cdns = cdns),
                )
            )
        }
        return qualities
    }

    override suspend fun playUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl {
        val args = freshPlayArgs(detail.roomId, detail.data?.toString().orEmpty())
        val data = quality.data as? DouyuPlayData ?: DouyuPlayData(0, emptyList())

        val urls = mutableListOf<String>()
        for (cdn in data.cdns) {
            val url = getPlayUrl(detail.roomId, args, data.rate, cdn)
            if (url.isNotEmpty()) {
                urls.add(url)
            }
        }
        return LivePlayUrl(urls = urls)
    }

    /** 按单个 CDN 取播放地址（对应 Dart getPlayUrl）。 */
    private suspend fun getPlayUrl(roomId: String, args: String, rate: Int, cdn: String): String {
        val params = formToParams("$args&cdn=$cdn&rate=$rate")
        val result = HttpClient.postJson(
            "https://www.douyu.com/lapi/live/getH5Play/$roomId",
            data = params,
            header = roomRequestHeaders(roomId),
            formUrlEncoded = true,
        )
        val data = result?.jsonObject?.get("data")?.jsonObject
        val rtmpUrl = data?.get("rtmp_url").str()
        val rtmpLive = data?.get("rtmp_live").str()
        return "$rtmpUrl/${htmlUnescape(rtmpLive)}"
    }

    /**
     * 取实时播放签名参数（对应 Dart `_getFreshPlayArgs`）。
     *
     * 斗鱼签名参数在房间停留期间会过期，重试时必须重新拉取服务器动态脚本并重签；
     * 复用 [LiveRoomDetail.data] 会让斗鱼在约 10 分钟后对高画质流返回 timestamp 错误
     * （匿名流尤其明显）。拉取或签名失败时回退 [fallback]，两者皆空则抛错。
     */
    private suspend fun freshPlayArgs(roomId: String, fallback: String): String {
        try {
            val jsEncResult = HttpClient.getText(
                "https://www.douyu.com/swf_api/homeH5Enc?rids=$roomId",
                query = emptyMap(),
                header = roomRequestHeaders(roomId),
            )
            val script = runCatching {
                HttpClient.json.parseToJsonElement(jsEncResult)
                    .jsonObject["data"]?.jsonObject?.get("room$roomId").str()
            }.getOrDefault("")
            if (script.isNotEmpty()) {
                val signed = DouyuSign.getSign(script, roomId)
                if (signed.isNotEmpty()) return signed
            }
        } catch (_: Exception) {
            // 瞬时故障：保留 fallback 兼容路径，后续播放响应仍会被校验
        }
        if (fallback.isEmpty()) throw CoreError("斗鱼播放签名刷新失败")
        return fallback
    }

    override suspend fun recommendRooms(page: Int): LiveCategoryResult {
        val result = HttpClient.getJson(
            "https://www.douyu.com/japi/weblist/apinc/allpage/6/$page",
            query = emptyMap(),
        )
        val data = result?.jsonObject?.get("data")?.jsonObject
        val items = (data?.get("rl")?.jsonArray ?: JsonArray(emptyList())).mapNotNull { item ->
            val obj = item.jsonObject
            if (obj["type"].intv() != 1) {
                return@mapNotNull null
            }
            LiveRoomItem(
                cover = obj["rs16"].str(),
                online = obj["ol"].intv(),
                roomId = obj["rid"].str(),
                title = obj["rn"].str(),
                userName = obj["nn"].str(),
            )
        }
        val hasMore = page < (data?.get("pgcnt").intv() ?: 0)
        return LiveCategoryResult(hasMore = hasMore, items = items)
    }

    override suspend fun roomDetail(roomId: String): LiveRoomDetail {
        val roomInfo = getRoomInfo(roomId)

        val h5RoomInfo = HttpClient.getJson(
            "https://www.douyu.com/swf_api/h5room/$roomId",
            query = emptyMap(),
            header = roomRequestHeaders(roomId),
        )
        val showTime = h5RoomInfo?.jsonObject?.get("data")?.jsonObject
            ?.get("show_time").str().ifEmpty { null }

        // 签名参数此处仅作 fallback：playQualities/playUrls 每次都会重取重签（见 freshPlayArgs）。
        // 保持宽容（失败即空串）避免瞬时故障阻断进房；真正取流失败由 freshPlayArgs 抛错。
        val playArgs = runCatching {
            freshPlayArgs(roomInfo?.get("room_id").str(), "")
        }.getOrDefault("")

        return LiveRoomDetail(
            cover = roomInfo?.get("room_pic").str(),
            online = roomInfo?.get("room_biz_all")?.jsonObject?.get("hot").str()
                .toIntOrNull() ?: 0,
            roomId = roomInfo?.get("room_id").str(),
            title = roomInfo?.get("room_name").str(),
            userName = roomInfo?.get("owner_name").str(),
            userAvatar = roomInfo?.get("owner_avatar").str(),
            introduction = roomInfo?.get("show_details").str(),
            notice = "",
            status = (roomInfo?.get("show_status").intv() ?: 0) == 1 &&
                (roomInfo?.get("videoLoop").intv() ?: 0) != 1,
            danmakuData = roomInfo?.get("room_id").str(),
            data = playArgs,
            url = "https://www.douyu.com/$roomId",
            isRecord = (roomInfo?.get("videoLoop").intv() ?: 0) == 1,
            showTime = showTime,
            categoryId = roomInfo?.get("cate2Id").str().ifEmpty {
                roomInfo?.get("cate_id").str().ifEmpty { roomInfo?.get("cid1").str() }
            },
            categoryName = roomInfo?.get("cate2Name").str().ifEmpty {
                roomInfo?.get("game_name").str().ifEmpty { roomInfo?.get("cate_name").str() }
            },
            categoryParentId = roomInfo?.get("cate1Id").str()
                .ifEmpty { roomInfo?.get("cid2").str() },
            categoryParentName = roomInfo?.get("cate1Name").str()
                .ifEmpty { roomInfo?.get("parent_cate_name").str() },
            categoryPic = roomInfo?.get("game_icon").str()
                .ifEmpty { roomInfo?.get("game_icon_url").str() },
        )
    }

    override suspend fun searchRooms(keyword: String, page: Int): LiveSearchRoomResult {
        val did = generateRandomString(32)
        val result = HttpClient.getJson(
            "https://www.douyu.com/japi/search/api/searchShow",
            query = mapOf("kw" to keyword, "page" to page, "pageSize" to 20),
            header = mapOf(
                "User-Agent" to kSearchUserAgent,
                "referer" to "https://www.douyu.com/search/",
                "Cookie" to "dy_did=$did;acf_did=$did",
            ),
        )
        if ((result?.jsonObject?.get("error").intv() ?: 0) != 0) {
            throw CoreError(result?.jsonObject?.get("msg").str())
        }
        val items = (result?.jsonObject?.get("data")?.jsonObject?.get("relateShow")?.jsonArray
            ?: JsonArray(emptyList())).mapNotNull { item ->
            val obj = item.jsonObject
            LiveRoomItem(
                roomId = obj["rid"].str(),
                title = obj["roomName"].str(),
                cover = obj["roomSrc"].str(),
                userName = obj["nickName"].str(),
                online = parseHotNum(obj["hot"].str()),
            )
        }
        val hasMore = items.isNotEmpty()
        return LiveSearchRoomResult(hasMore = hasMore, items = items)
    }

    override suspend fun searchAnchors(keyword: String, page: Int): LiveSearchAnchorResult {
        val did = generateRandomString(32)
        val result = HttpClient.getJson(
            "https://www.douyu.com/japi/search/api/searchUser",
            query = mapOf(
                "kw" to keyword,
                "page" to page,
                "pageSize" to 20,
                "filterType" to 1,
            ),
            header = mapOf(
                "User-Agent" to kSearchUserAgent,
                "referer" to "https://www.douyu.com/search/",
                "Cookie" to "dy_did=$did;acf_did=$did",
            ),
        )
        val items = (result?.jsonObject?.get("data")?.jsonObject?.get("relateUser")?.jsonArray
            ?: JsonArray(emptyList())).mapNotNull { item ->
            val obj = item.jsonObject
            val anchorInfo = obj["anchorInfo"]?.jsonObject
            val liveStatus = (anchorInfo?.get("isLive").str().toIntOrNull() ?: 0) == 1
            val roomType = anchorInfo?.get("roomType").str().toIntOrNull() ?: 0
            LiveAnchorItem(
                roomId = anchorInfo?.get("rid").str(),
                avatar = anchorInfo?.get("avatar").str(),
                userName = anchorInfo?.get("nickName").str(),
                liveStatus = liveStatus && roomType == 0,
            )
        }
        val hasMore = items.isNotEmpty()
        return LiveSearchAnchorResult(hasMore = hasMore, items = items)
    }

    override suspend fun liveStatus(roomId: String): Boolean {
        val roomInfo = getRoomInfo(roomId)
        return (roomInfo?.get("show_status").intv() ?: 0) == 1 &&
            (roomInfo?.get("videoLoop").intv() ?: 0) != 1
    }

    override suspend fun superChatMessage(roomId: String, detail: LiveRoomDetail?):
        List<LiveSuperChatMessage> {
        //尚不支持（对应 Dart 空返回）
        return emptyList()
    }

    override suspend fun contributionRank(roomId: String, detail: LiveRoomDetail?):
        List<LiveContributionRankItem> {
        val result = HttpClient.getJson(
            "https://www.douyu.com/japi/interact/comm/fanshome/rank/top10",
            query = mapOf("rid" to roomId),
            header = mapOf(
                "referer" to "https://www.douyu.com/$roomId",
                "user-agent" to kContributionUserAgent,
            ),
        )
        val items = (result?.jsonObject?.get("data")?.jsonObject?.get("intimacyRank")?.jsonArray
            ?: JsonArray(emptyList())).mapNotNull { item ->
            val obj = item.jsonObject
            LiveContributionRankItem(
                rank = obj["rank"].intv(),
                userName = obj["nickname"].str(),
                avatar = obj["avatar"].str(),
                scoreText = obj["value"].str().ifEmpty { "0" },
                scoreDetail = "亲密度",
            )
        }
        return items
    }

    /** 取房间信息（对应 Dart _getRoomInfo：betard 可能返回纯文本，统一取 room 节点）。 */
    private suspend fun getRoomInfo(roomId: String): JsonObject? {
        val text = HttpClient.getText(
            "https://www.douyu.com/betard/$roomId",
            query = emptyMap(),
            header = betardHeader(roomId),
        )
        val json = runCatching { HttpClient.json.parseToJsonElement(text) }.getOrNull()
        return json?.jsonObject?.get("room")?.jsonObject
    }

    /** 生成指定长度的16进制随机字符串（对应 Dart generateRandomString）。 */
    private val secureRandom = java.security.SecureRandom()

    private fun generateRandomString(length: Int): String {
        val stringBuffer = StringBuilder()
        repeat(length) {
            stringBuffer.append("0123456789abcdef"[secureRandom.nextInt(16)])
        }
        return stringBuffer.toString()
    }

    /** 热度字符串解析（对应 Dart parseHotNum："1.2万" → 12000）。 */
    private fun parseHotNum(hn: String): Int {
        return try {
            var num = hn.replace("万", "").toDoubleOrNull()
                ?: throw NumberFormatException()
            if (hn.contains("万")) {
                num *= 10000
            }
            num.roundToInt()
        } catch (_: Exception) {
            -999
        }
    }

    /**
     * 将 form 字符串解析为参数 Map。
     * Kotlin 端 postJson 的 formUrlEncoded 分支按 Map 编码发送（Dart 直接透传字符串）；
     * 斗鱼签名串各参数值均为 URL 安全字符，解析后重编码结果与原串一致。
     */
    private fun formToParams(form: String): Map<String, String> {
        val params = LinkedHashMap<String, String>()
        if (form.isEmpty()) return params
        for (pair in form.split("&")) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            if (idx < 0) {
                params[pair] = ""
            } else {
                params[pair.substring(0, idx)] = pair.substring(idx + 1)
            }
        }
        return params
    }

    /** HTML 实体反转义（对应 Dart 的 html_unescape 包，覆盖数字实体与常见命名实体）。 */
    private fun htmlUnescape(input: String): String {
        if (input.isEmpty()) return input
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '&') {
                val semi = input.indexOf(';', i)
                if (semi in (i + 1)..(i + 12)) {
                    val entity = input.substring(i + 1, semi)
                    val decoded = decodeEntity(entity)
                    if (decoded != null) {
                        sb.append(decoded)
                        i = semi + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun decodeEntity(entity: String): String? {
        if (entity.startsWith("#x") || entity.startsWith("#X")) {
            val code = entity.substring(2).toIntOrNull(16) ?: return null
            return code.toChar().toString()
        }
        if (entity.startsWith("#")) {
            val code = entity.substring(1).toIntOrNull() ?: return null
            return code.toChar().toString()
        }
        return namedEntities[entity]
    }

    private val namedEntities: Map<String, String> = mapOf(
        "amp" to "&",
        "lt" to "<",
        "gt" to ">",
        "quot" to "\"",
        "apos" to "'",
        "nbsp" to " ",
        "copy" to "©",
        "reg" to "®",
        "trade" to "™",
        "hellip" to "…",
        "mdash" to "—",
        "ndash" to "–",
        "middot" to "·",
        "times" to "×",
        "divide" to "÷",
        "deg" to "°",
        "plusmn" to "±",
        "yen" to "¥",
        "euro" to "€",
        "pound" to "£",
        "cent" to "¢",
        "sect" to "§",
        "para" to "¶",
        "laquo" to "«",
        "raquo" to "»",
        "lsquo" to "‘",
        "rsquo" to "’",
        "ldquo" to "“",
        "rdquo" to "”",
        "bull" to "•",
        "prime" to "′",
        "Prime" to "″",
        "frac12" to "½",
        "frac13" to "⅓",
        "frac14" to "¼",
        "sup2" to "²",
        "sup3" to "³",
        "micro" to "µ",
    )
}