package dev.livefork.core.site

import dev.livefork.core.common.CoreError
import dev.livefork.core.common.HttpClient
import dev.livefork.core.common.KuaishouLiveLink
import dev.livefork.core.common.intv
import dev.livefork.core.common.str
import dev.livefork.core.danmaku.KuaishouDanmaku
import dev.livefork.core.danmaku.KuaishouDanmakuArgs
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.random.Random

/**
 * 快手直播站点实现。
 * 对应 Dart `KuaishouSite`（lib/src/kuaishou_site.dart）。站点 id="kuaishou"、name="快手直播" 与
 * 冻结文案保持不变；方法名按 Kotlin 惯例驼峰化（categories/searchRooms/...），职责一一对应。
 *
 * 主要流程：分类/搜索走 live.kuaishou.com 的 live_api；房间详情先抓页面
 * `window.__INITIAL_STATE__` 再解析 liveroom.playList；弹幕凭证（token/websocketUrls）
 * 优先从页面内嵌数据取，缺失时调 `/live_api/liveroom/websocketinfo` 补充。
 */
class KuaishouSite : LiveSite() {

    override var id: String = "kuaishou"
    override var name: String = "快手直播"

    /** 可选的自定义 Cookie */
    var customCookie: String = ""

    /** 快手网页端生成的 kwfv1 值 */
    var customKww: String = ""

    /** 从网页端抓取的 Cookie */
    var cookie: String = ""

    /** 解析后的 Cookie 键值（用于 did 等） */
    var cookieObj: Map<String, String> = emptyMap()

    override fun danmaku(): LiveDanmaku = KuaishouDanmaku()

    companion object {
        private const val userAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val imageExtensions = listOf(
            "svgz", "pjp", "png", "ico", "avif", "tiff", "tif", "jfif", "svg",
            "xbm", "pjpeg", "webp", "jpg", "jpeg", "bmp", "gif",
        )

        /**
         * 从 Cookie 串中提取 kwfv1 并做 URL 解码，补上 `###ssrc` 后缀；
         * 取不到时返回 fallback（对应 Dart `resolveServerKww`）。
         */
        fun resolveServerKww(cookie: String, fallback: String): String {
            for (part in cookie.split(';')) {
                val item = part.trim()
                if (!item.startsWith("kwfv1=")) {
                    continue
                }
                val value = item.substring("kwfv1=".length).trim()
                if (value.isEmpty()) {
                    continue
                }
                return try {
                    // Dart Uri.decodeComponent 只解码 %XX、保留 '+'，先转义 '+' 再交给 URLDecoder
                    "${URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")}###ssrc"
                } catch (_: Exception) {
                    "$value###ssrc"
                }
            }
            return fallback.trim()
        }

        /** 依次取 caption/title/liveStream.caption/liveStream.title/gameInfo.name/author.name 第一个非空值。 */
        fun resolveRoomTitle(room: JsonObject): String {
            val liveStream = room["liveStream"] as? JsonObject ?: JsonObject(emptyMap())
            val gameInfo = room["gameInfo"] as? JsonObject ?: JsonObject(emptyMap())
            val author = room["author"] as? JsonObject ?: JsonObject(emptyMap())
            for (value in listOf(
                room["caption"],
                room["title"],
                liveStream["caption"],
                liveStream["title"],
                gameInfo["name"],
                author["name"],
            )) {
                if (value != null && value !is JsonNull) {
                    val title = value.str().trim()
                    if (title.isNotEmpty()) {
                        return title
                    }
                }
            }
            return ""
        }

        /**
         * 从 playList 中挑选直播间：优先匹配 roomId（author.id），匹配不到则回退整个列表；
         * 在候选中优先选择正在直播的房间。
         */
        fun selectLiveRoomFromPlayList(playList: List<JsonElement>, roomId: String): JsonObject? {
            val rooms = playList.mapNotNull { it as? JsonObject }
            if (rooms.isEmpty()) {
                return null
            }
            val targetRoomId = roomId.trim()
            val matchingRooms = if (targetRoomId.isEmpty()) {
                rooms
            } else {
                rooms.filter { matchesRoomId(it, targetRoomId) }
            }
            val candidates = if (matchingRooms.isEmpty()) rooms else matchingRooms
            return candidates.firstOrNull { resolveLiveStatus(it) } ?: candidates.first()
        }

        /** isLiving/living 为 true 或 1 视为直播中；否则看 liveStream.id 是否非空。 */
        fun resolveLiveStatus(room: JsonObject): Boolean {
            if (isLiveFlag(room["isLiving"]) || isLiveFlag(room["living"])) {
                return true
            }
            val liveStream = room["liveStream"] as? JsonObject ?: room
            val liveStreamId = liveStream["id"].str().trim()
            // 快手在切换 CDN 或刷新推流地址时会短暂省略 playUrls，但有效的
            // liveStream id 仍代表当前房间正在直播。
            return liveStreamId.isNotEmpty()
        }

        private fun matchesRoomId(room: JsonObject, roomId: String): Boolean {
            val author = room["author"] as? JsonObject ?: JsonObject(emptyMap())
            return author["id"].str().trim() == roomId
        }

        /** 对应 Dart `_isLiveFlag`：仅 true / 1 视为真。 */
        private fun isLiveFlag(value: JsonElement?): Boolean {
            if (value == null || value is JsonNull) {
                return false
            }
            val primitive = value as? JsonPrimitive ?: return false
            if (primitive.isString) {
                return false
            }
            return primitive.content == "true" || primitive.content == "1"
        }

        private fun isJsonOne(value: JsonElement?): Boolean {
            if (value == null || value is JsonNull) {
                return false
            }
            val primitive = value as? JsonPrimitive ?: return false
            if (primitive.isString) {
                return false
            }
            return primitive.content.toIntOrNull() == 1
        }

        private fun isJsonTrue(value: JsonElement?): Boolean {
            if (value == null || value is JsonNull) {
                return false
            }
            val primitive = value as? JsonPrimitive ?: return false
            if (primitive.isString) {
                return false
            }
            return primitive.content == "true"
        }

        /** 取第一个非 null 值，保持空字符串（对应 Dart `a ?? b ?? ''` 链）。 */
        private fun firstString(vararg elements: JsonElement?): String {
            for (element in elements) {
                if (element != null && element !is JsonNull) {
                    return element.str()
                }
            }
            return ""
        }
    }

    private val headers: Map<String, Any?> get() = mapOf(
        "User-Agent" to userAgent,
        "accept" to
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9",
        "connection" to "keep-alive",
        "sec-ch-ua" to "Google Chrome;v=120, Chromium;v=120, Not=A?Brand;v=24",
        "sec-ch-ua-platform" to "Windows",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin",
        "Sec-Fetch-User" to "?1",
    )

    private val headersWithCookie: Map<String, Any?> get() {
        val result = headers.toMutableMap()
        val cookieValue = currentCookieHeader()
        if (cookieValue.isNotEmpty()) {
            result["cookie"] = cookieValue
        }
        return result
    }

    private fun searchHeaders(keyword: String): Map<String, Any?> = buildMap {
        putAll(headersWithCookie)
        put("accept", "application/json, text/plain, */*")
        put(
            "referer",
            "https://live.kuaishou.com/search?keyword=" +
                URLEncoder.encode(keyword, "UTF-8").replace("+", "%20"),
        )
        put("Sec-Fetch-Dest", "empty")
        put("Sec-Fetch-Mode", "cors")
    }

    // ==================== 分类 ====================

    override suspend fun categories(): List<LiveCategory> {
        val categories = mutableListOf(
            LiveCategory(id = "1", name = "热门", children = mutableListOf()),
            LiveCategory(id = "2", name = "网游", children = mutableListOf()),
            LiveCategory(id = "3", name = "单机", children = mutableListOf()),
            LiveCategory(id = "4", name = "手游", children = mutableListOf()),
            LiveCategory(id = "5", name = "棋牌", children = mutableListOf()),
            LiveCategory(id = "6", name = "娱乐", children = mutableListOf()),
            LiveCategory(id = "7", name = "综合", children = mutableListOf()),
            LiveCategory(id = "8", name = "文化", children = mutableListOf()),
        )
        for (category in categories) {
            @Suppress("UNCHECKED_CAST")
            (category.children as MutableList<LiveSubCategory>).addAll(getAllSubCategories(category))
        }
        return categories
    }

    /** 循环翻页收集子分类，直到某页不足 pageSize（对应 Dart `_getAllSubCategories`）。 */
    private suspend fun getAllSubCategories(
        category: LiveCategory,
        page: Int = 1,
        pageSize: Int = 30,
    ): List<LiveSubCategory> {
        val allSubs = mutableListOf<LiveSubCategory>()
        try {
            var currentPage = page
            while (true) {
                val subs = getSubCategories(category, currentPage, pageSize)
                allSubs.addAll(subs)
                if (subs.size < pageSize) {
                    break
                }
                currentPage++
            }
        } catch (_: Exception) {
        }
        return allSubs
    }

    private suspend fun getSubCategories(
        category: LiveCategory,
        page: Int,
        pageSize: Int,
    ): List<LiveSubCategory> {
        val result = HttpClient.getJson(
            "https://live.kuaishou.com/live_api/category/data",
            query = mapOf("type" to category.id, "page" to page, "size" to pageSize),
            header = headers,
        )
        val list = result?.jsonObject?.get("data")?.jsonObject?.get("list")?.jsonArray
            ?: JsonArray(emptyList())
        val subs = mutableListOf<LiveSubCategory>()
        for (item in list) {
            val obj = item.jsonObject
            subs.add(
                LiveSubCategory(
                    id = obj["id"].str(),
                    name = obj["name"].str(),
                    parentId = category.id,
                    pic = obj["poster"].str(),
                )
            )
        }
        return subs
    }

    // ==================== 分类直播间列表 ====================

    override suspend fun categoryRooms(category: LiveSubCategory, page: Int): LiveCategoryResult {
        val api = if (category.id.length < 7) {
            "https://live.kuaishou.com/live_api/gameboard/list"
        } else {
            "https://live.kuaishou.com/live_api/non-gameboard/list"
        }
        val result = HttpClient.getJson(
            api,
            query = mapOf(
                "filterType" to 0,
                "pageSize" to 20,
                "gameId" to category.id,
                "page" to page,
            ),
            header = headers,
        )
        val list = result?.jsonObject?.get("data")?.jsonObject?.get("list")?.jsonArray
            ?: JsonArray(emptyList())
        val items = mutableListOf<LiveRoomItem>()
        for (item in list) {
            val obj = item.jsonObject
            val author = obj["author"]?.jsonObject
            var cover = obj["poster"].str()
            if (cover.isNotEmpty() && !isImage(cover)) {
                cover = "$cover.jpg"
            }
            items.add(
                LiveRoomItem(
                    roomId = author?.get("id").str(),
                    title = obj["caption"].str(),
                    cover = cover,
                    userName = author?.get("name").str(),
                    online = parseInt(obj["watchingCount"]),
                )
            )
        }
        return LiveCategoryResult(hasMore = items.size >= 20, items = items)
    }

    // ==================== 推荐直播间 ====================

    override suspend fun recommendRooms(page: Int): LiveCategoryResult {
        val result = HttpClient.getJson(
            "https://live.kuaishou.com/live_api/home/list",
            header = headers,
        )
        val list = result?.jsonObject?.get("data")?.jsonObject?.get("list")?.jsonArray
            ?: JsonArray(emptyList())
        val items = mutableListOf<LiveRoomItem>()
        for (item in list) {
            val gameLiveInfo = item.jsonObject["gameLiveInfo"]?.jsonArray ?: JsonArray(emptyList())
            for (sitem in gameLiveInfo) {
                val liveInfo = sitem.jsonObject["liveInfo"]?.jsonArray ?: JsonArray(emptyList())
                for (titem in liveInfo) {
                    val obj = titem.jsonObject
                    val author = obj["author"]?.jsonObject
                    var gameInfo = obj["gameInfo"]?.jsonObject
                    if (gameInfo == null) {
                        gameInfo = JsonObject(emptyMap())
                    }
                    val cover = gameInfo["poster"].str()
                    items.add(
                        LiveRoomItem(
                            roomId = author?.get("id").str(),
                            title = resolveRoomTitle(obj),
                            cover = cover,
                            userName = author?.get("name").str(),
                            online = parseInt(obj["watchingCount"]),
                        )
                    )
                }
            }
        }
        if (items.any { it.online > 0 }) {
            items.sortByDescending { it.online }
        }
        return LiveCategoryResult(hasMore = false, items = items)
    }

    // ==================== 搜索 ====================

    override suspend fun searchRooms(keyword: String, page: Int): LiveSearchRoomResult {
        try {
            val result = searchLiveStreams(keyword, page)
            if (result.items.isNotEmpty() || page > 1) {
                return result
            }
        } catch (_: Exception) {
        }

        if (page > 1) {
            return LiveSearchRoomResult(hasMore = false, items = emptyList())
        }

        return try {
            searchRoomsByOverview(keyword)
        } catch (_: Exception) {
            LiveSearchRoomResult(hasMore = false, items = emptyList())
        }
    }

    private suspend fun searchLiveStreams(keyword: String, page: Int): LiveSearchRoomResult {
        val result = HttpClient.getJson(
            "https://live.kuaishou.com/live_api/search/liveStream",
            query = mapOf("keyword" to keyword, "page" to page, "ussid" to ""),
            header = searchHeaders(keyword),
        )
        val data = result?.jsonObject?.get("data")
        if (data !is JsonObject || !isJsonOne(data["result"])) {
            return LiveSearchRoomResult(hasMore = false, items = emptyList())
        }
        val list = data["list"]?.jsonArray ?: JsonArray(emptyList())
        val items = mutableListOf<LiveRoomItem>()
        for (item in list) {
            val room = parseSearchLiveRoom(item)
            if (room.roomId.isNotEmpty()) {
                items.add(room)
            }
        }
        return LiveSearchRoomResult(hasMore = list.isNotEmpty(), items = items)
    }

    private suspend fun searchRoomsByOverview(keyword: String): LiveSearchRoomResult {
        val overview = getSearchOverview(keyword)
        val liveStreams = overviewLiveStreams(overview)
        val items = mutableListOf<LiveRoomItem>()
        for (item in liveStreams) {
            val room = parseSearchLiveRoom(item)
            if (room.roomId.isNotEmpty()) {
                items.add(room)
            }
        }
        if (items.isNotEmpty()) {
            return LiveSearchRoomResult(hasMore = false, items = items)
        }
        return LiveSearchRoomResult(hasMore = false, items = emptyList())
    }

    override suspend fun searchAnchors(keyword: String, page: Int): LiveSearchAnchorResult {
        try {
            val result = searchAuthorStreams(keyword, page)
            if (result.items.isNotEmpty() || page > 1) {
                return result
            }
        } catch (_: Exception) {
        }

        if (page > 1) {
            return LiveSearchAnchorResult(hasMore = false, items = emptyList())
        }

        return try {
            val overview = getSearchOverview(keyword)
            val authors = findOverviewSectionList(overview, "authors")
            val items = mutableListOf<LiveAnchorItem>()
            for (item in authors) {
                val anchor = parseSearchAnchor(item)
                if (anchor.roomId.isNotEmpty()) {
                    items.add(anchor)
                }
            }
            LiveSearchAnchorResult(hasMore = false, items = items)
        } catch (_: Exception) {
            LiveSearchAnchorResult(hasMore = false, items = emptyList())
        }
    }

    private suspend fun searchAuthorStreams(keyword: String, page: Int): LiveSearchAnchorResult {
        val result = HttpClient.getJson(
            "https://live.kuaishou.com/live_api/search/author",
            query = mapOf(
                "key" to keyword,
                "keyword" to keyword,
                "page" to page,
                "count" to 15,
                "ussid" to "",
                "lssid" to "",
            ),
            header = searchHeaders(keyword),
        )
        val data = result?.jsonObject?.get("data")
        if (data !is JsonObject || !isJsonOne(data["result"])) {
            return LiveSearchAnchorResult(hasMore = false, items = emptyList())
        }
        val list = data["list"]?.jsonArray ?: JsonArray(emptyList())
        val items = mutableListOf<LiveAnchorItem>()
        for (item in list) {
            val anchor = parseSearchAnchor(item)
            if (anchor.roomId.isNotEmpty()) {
                items.add(anchor)
            }
        }
        return LiveSearchAnchorResult(hasMore = list.size >= 15, items = items)
    }

    private suspend fun getSearchOverview(keyword: String): JsonObject {
        val result = HttpClient.getJson(
            "https://live.kuaishou.com/live_api/search/overview",
            query = mapOf("keyword" to keyword, "ussid" to ""),
            header = searchHeaders(keyword),
        )
        return result?.jsonObject?.get("data") as? JsonObject ?: JsonObject(emptyMap())
    }

    /* 对应 Dart `overviewLiveStreams`：取 overview 中 type == "liveStreams" 的 section。 */
    private fun overviewLiveStreams(overview: JsonObject): List<JsonElement> {
        val sections = overview["list"]?.jsonArray ?: return emptyList()
        for (section in sections) {
            val obj = section as? JsonObject ?: continue
            if (obj["type"].str() == "liveStreams") {
                return obj["list"]?.jsonArray ?: emptyList()
            }
        }
        return emptyList()
    }

    /* 对应 Dart `_findOverviewSectionList`：取 overview 中指定 type 的 section 列表。 */
    private fun findOverviewSectionList(overview: JsonObject, type: String): List<JsonElement> {
        val sections = overview["list"]?.jsonArray ?: return emptyList()
        for (section in sections) {
            val obj = section as? JsonObject ?: continue
            if (obj["type"].str() == type) {
                return obj["list"]?.jsonArray ?: emptyList()
            }
        }
        return emptyList()
    }

    private fun parseSearchLiveRoom(item: JsonElement): LiveRoomItem {
        val obj = item as? JsonObject
        if (obj == null) {
            return LiveRoomItem(roomId = "", title = "", cover = "", userName = "")
        }
        val author = obj["author"] as? JsonObject
        val gameInfo = obj["gameInfo"] as? JsonObject
        var cover = firstString(obj["poster"], obj["coverUrl"], gameInfo?.get("poster"))
        if (cover.isNotEmpty() && !isImage(cover)) {
            cover = "$cover.jpg"
        }
        return LiveRoomItem(
            roomId = firstString(author?.get("id"), obj["authorId"], obj["userId"]),
            title = firstString(obj["caption"], obj["title"], author?.get("name")),
            cover = cover,
            userName = firstString(author?.get("name"), obj["userName"]),
            online = parseInt(obj["watchingCount"]),
        )
    }

    private fun parseSearchAnchor(item: JsonElement): LiveAnchorItem {
        if (item !is JsonObject) {
            return LiveAnchorItem(roomId = "", avatar = "", userName = "", liveStatus = false)
        }
        return LiveAnchorItem(
            roomId = item["id"].str(),
            avatar = item["avatar"].str(),
            userName = item["name"].str(),
            liveStatus = isJsonTrue(item["living"]),
        )
    }

    // ==================== 房间详情 ====================

    override suspend fun roomDetail(roomId: String): LiveRoomDetail {
        val url = "https://live.kuaishou.com/u/$roomId"

        getCookie(url)
        registerDid()

        val anonymousDetail = loadRoomDetail(url = url, roomId = roomId, headers = headers)
        if (anonymousDetail?.status == true) {
            return anonymousDetail
        }

        var cookieDetail: LiveRoomDetail? = null
        if (currentCookieHeader().isNotEmpty()) {
            cookieDetail = loadRoomDetail(url = url, roomId = roomId, headers = headersWithCookie)
            if (cookieDetail != null && (cookieDetail.status || anonymousDetail == null)) {
                return cookieDetail
            }
        }

        if (anonymousDetail != null) {
            return anonymousDetail
        }
        if (cookieDetail != null) {
            return cookieDetail
        }
        throw CoreError("快手直播间状态暂时不可用")
    }

    private suspend fun loadRoomDetail(
        url: String,
        roomId: String,
        headers: Map<String, Any?>,
    ): LiveRoomDetail? = try {
        val resultText = HttpClient.getText(url, query = emptyMap(), header = headers)
        parseRoomDetail(resultText, roomId)
    } catch (_: Exception) {
        null
    }

    /**
     * 解析房间页内嵌的 `window.__INITIAL_STATE__`。
     * playList 为空视为未开播（返回离线详情）；有直播流则挑选房间并组装详情与弹幕参数。
     */
    private suspend fun parseRoomDetail(resultText: String, roomId: String): LiveRoomDetail? {
        return try {
            val match = Regex("window\\.__INITIAL_STATE__=(.*?);").find(resultText) ?: return null
            val text = match.groupValues[1]
            val transferData = text.replace("undefined", "null")
            val jsonObj = Json.parseToJsonElement(transferData)
            val root = jsonObj as? JsonObject ?: return null
            val liveroom = root["liveroom"] as? JsonObject ?: return null
            val playList = liveroom["playList"]?.jsonArray ?: return null
            if (playList.isEmpty()) {
                return offlineDetail(roomId)
            }

            val first = selectLiveRoomFromPlayList(playList, roomId) ?: return null
            val liveStream = first["liveStream"] as? JsonObject ?: JsonObject(emptyMap())
            val author = first["author"] as? JsonObject ?: JsonObject(emptyMap())
            val gameInfo = first["gameInfo"] as? JsonObject ?: JsonObject(emptyMap())
            val isLiving = resolveLiveStatus(first)
            val liveStreamId = liveStream["id"].str()

            val websocketUrls = mutableListOf<String>()
            (liveroom["websocketUrls"]?.jsonArray ?: JsonArray(emptyList())).forEach { item ->
                val websocketUrl = item.str()
                if (websocketUrl.isNotEmpty()) {
                    websocketUrls.add(websocketUrl)
                }
            }
            var danmakuToken = liveroom["token"].str()
            val websocketInfo = first["websocketInfo"] as? JsonObject ?: JsonObject(emptyMap())
            if (danmakuToken.isEmpty()) {
                danmakuToken = websocketInfo["token"].str()
            }
            if (websocketUrls.isEmpty()) {
                val wsUrlsElement = websocketInfo["websocketUrls"] ?: websocketInfo["webSocketAddresses"]
                (wsUrlsElement?.jsonArray ?: JsonArray(emptyList())).forEach { item ->
                    val websocketUrl = item.str()
                    if (websocketUrl.isNotEmpty()) {
                        websocketUrls.add(websocketUrl)
                    }
                }
            }

            // 正在直播但内嵌数据缺少弹幕凭证时，走 websocketinfo 接口补充
            if (isLiving && liveStreamId.isNotEmpty() &&
                (danmakuToken.isEmpty() || websocketUrls.isEmpty())
            ) {
                val info = getWebsocketInfo(
                    roomId = author["id"].str().ifEmpty { roomId },
                    liveStreamId = liveStreamId,
                )
                if (info.token.isNotEmpty()) {
                    danmakuToken = info.token
                }
                if (websocketUrls.isEmpty()) {
                    websocketUrls.addAll(info.websocketUrls)
                }
            }

            var cover = liveStream["poster"].str()
            if (cover.isNotEmpty() && !isImage(cover)) {
                cover = "$cover.jpg"
            }

            val resolvedRoomId = author["id"].str().ifEmpty { roomId }
            LiveRoomDetail(
                roomId = resolvedRoomId,
                title = resolveRoomTitle(first),
                cover = cover,
                userName = author["name"].str(),
                userAvatar = author["avatar"].str(),
                online = if (isLiving) parseInt(gameInfo["watchingCount"]) else 0,
                introduction = nullableString(author["description"]),
                notice = nullableString(author["description"]),
                status = isLiving,
                url = KuaishouLiveLink.publicRoomUri(resolvedRoomId)
                    ?.toString() ?: "https://live.kuaishou.com/u/$roomId",
                data = liveStream["playUrls"],
                danmakuData = KuaishouDanmakuArgs(
                    roomId = resolvedRoomId,
                    liveStreamId = liveStreamId,
                    token = danmakuToken,
                    websocketUrls = websocketUrls,
                    pageId = generatePageId(),
                    expTag = liveStream["expTag"].str(),
                    attach = first["expTag"].str(),
                    cookie = currentCookieHeader(),
                    userAgent = userAgent,
                ),
                categoryId = nullableString(gameInfo["id"]),
                categoryName = nullableString(gameInfo["name"]),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun offlineDetail(roomId: String): LiveRoomDetail = LiveRoomDetail(
        roomId = roomId,
        title = "",
        cover = "",
        userName = "",
        userAvatar = "",
        online = 0,
        status = false,
        url = "",
    )

    override suspend fun liveStatus(roomId: String): Boolean {
        val detail = roomDetail(roomId)
        return detail.status
    }

    // ==================== 清晰度 ====================

    override suspend fun playQualities(detail: LiveRoomDetail): List<LivePlayQuality> {
        val qualities = mutableListOf<LivePlayQuality>()
        try {
            val data = detail.data as? JsonElement
            val qualityList = data?.jsonObject?.get("h264")?.jsonObject
                ?.get("adaptationSet")?.jsonObject?.get("representation")?.jsonArray
                ?: JsonArray(emptyList())
            for (quality in qualityList) {
                val obj = quality.jsonObject
                qualities.add(
                    LivePlayQuality(
                        quality = obj["name"].str(),
                        sort = obj["level"].intv(),
                        data = listOf(obj["url"].str()),
                    )
                )
            }
        } catch (_: Exception) {
        }
        // LivePlayQuality 的 compareTo 已是倒序（sort 大者优先）
        qualities.sort()
        return qualities
    }

    override suspend fun playUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl {
        val urls = mutableListOf<String>()
        if (quality.data is List<*>) {
            for (item in quality.data) {
                urls.add(item?.toString() ?: "")
            }
        }
        return LivePlayUrl(urls = urls)
    }

    // ==================== SC / 贡献榜 ====================

    /** 快手未提供 SC 接口（与 Dart 默认行为一致，返回空列表）。 */
    override suspend fun superChatMessage(roomId: String, detail: LiveRoomDetail?):
        List<LiveSuperChatMessage> = emptyList()

    /** 快手未提供贡献榜接口（与 Dart 默认行为一致，返回空列表）。 */
    override suspend fun contributionRank(roomId: String, detail: LiveRoomDetail?):
        List<LiveContributionRankItem> = emptyList()

    // ==================== Cookie 管理 ====================

    /**
     * 抓取直播间页面并记录 Set-Cookie（对应 Dart 用 Dio + CookieManager 的行为）。
     * 失败时仅保留自定义 Cookie。
     */
    private suspend fun getCookie(url: String) {
        try {
            val requestBuilder = okhttp3.Request.Builder().url(url)
            headersWithCookie.forEach { (k, v) ->
                if (v != null) {
                    requestBuilder.header(k, v.toString())
                }
            }
            val response = withContext(Dispatchers.IO) {
                HttpClient.client.newCall(requestBuilder.get().build()).execute()
            }
            response.use { res ->
                val cookieValues = parseCookieHeader(customCookie)
                res.headers("set-cookie").forEach { setCookie ->
                    val index = setCookie.indexOf('=')
                    if (index > 0) {
                        val name = setCookie.substring(0, index).trim()
                        val valueEnd = setCookie.indexOf(';', index + 1)
                        val value = if (valueEnd >= 0) {
                            setCookie.substring(index + 1, valueEnd)
                        } else {
                            setCookie.substring(index + 1)
                        }
                        if (name.isNotEmpty() && value.isNotEmpty()) {
                            cookieValues[name] = value.trim()
                        }
                    }
                }
                cookieObj = cookieValues
                cookie = formatCookieHeader(cookieValues)
            }
        } catch (_: Exception) {
            cookieObj = parseCookieHeader(customCookie)
            cookie = formatCookieHeader(cookieObj)
        }
    }

    /** 请求 websocketinfo 接口补充弹幕 token 与地址；失败返回空信息。 */
    private suspend fun getWebsocketInfo(roomId: String, liveStreamId: String): KuaishouWebsocketInfo {
        return try {
            val kww = resolveServerKww(currentCookieHeader(), customKww)
            val result = HttpClient.getJson(
                "https://live.kuaishou.com/live_api/liveroom/websocketinfo",
                query = mapOf("liveStreamId" to liveStreamId),
                header = buildMap {
                    putAll(headersWithCookie)
                    if (kww.isNotEmpty()) {
                        put("Kww", kww)
                    }
                    put("accept", "application/json, text/plain, */*")
                    put("referer", "https://live.kuaishou.com/u/$roomId")
                    put("Sec-Fetch-Dest", "empty")
                    put("Sec-Fetch-Mode", "cors")
                },
            )
            val data = result?.jsonObject?.get("data")
            if (data !is JsonObject) {
                return KuaishouWebsocketInfo.empty()
            }
            val urls = mutableListOf<String>()
            val wsUrlsElement = data["websocketUrls"] ?: data["webSocketAddresses"]
            (wsUrlsElement?.jsonArray ?: JsonArray(emptyList())).forEach { item ->
                val url = item.str()
                if (url.isNotEmpty()) {
                    urls.add(url)
                }
            }
            KuaishouWebsocketInfo(token = data["token"].str(), websocketUrls = urls)
        } catch (_: Exception) {
            KuaishouWebsocketInfo.empty()
        }
    }

    // ==================== DID 注册 ====================

    /** 有 did 时向日志服务注册设备信息（失败静默，对应 Dart `_registerDid`）。 */
    private suspend fun registerDid() {
        val did = cookieObj["did"]
        if (did.isNullOrEmpty()) {
            return
        }
        try {
            HttpClient.postJson(
                "https://log-sdk.ksapisrv.com/rest/wd/common/log/collect/misc2?v=3.9.49&kpn=KS_GAME_LIVE_PC",
                query = emptyMap(),
                header = headers,
                data = buildMisc2Data(did),
            )
        } catch (_: Exception) {
        }
    }

    private fun buildMisc2Data(did: String): String {
        val clientTimestamp = System.currentTimeMillis()
        return buildJsonObject {
            put(
                "common",
                buildJsonObject {
                    put(
                        "identity_package",
                        buildJsonObject {
                            put("device_id", did)
                            put("global_id", "")
                        }
                    )
                    put(
                        "app_package",
                        buildJsonObject {
                            put("language", "zh-CN")
                            put("platform", 10)
                            put("container", "WEB")
                            put("product_name", "KS_GAME_LIVE_PC")
                        }
                    )
                    put(
                        "device_package",
                        buildJsonObject {
                            put("os_version", "NT 10.0")
                            put("model", "Windows")
                            put("ua", userAgent)
                        }
                    )
                    put("need_encrypt", "false")
                    put(
                        "network_package",
                        buildJsonObject {
                            put("type", 3)
                        }
                    )
                    put(
                        "h5_extra_attr",
                        "{\"sdk_name\":\"webLogger\",\"sdk_version\":\"3.9.49\",\"sdk_bundle\":\"log.common.js\"," +
                            "\"app_version_name\":\"\",\"host_product\":\"\",\"resolution\":\"1920x1080\"," +
                            "\"screen_with\":1920,\"screen_height\":1080,\"device_pixel_ratio\":1," +
                            "\"domain\":\"https://live.kuaishou.com\"}"
                    )
                    put("global_attr", "{}")
                }
            )
            put(
                "logs",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("client_timestamp", clientTimestamp)
                            put("client_increment_id", Random.nextInt(8999) + 1000)
                            put("session_id", generateSessionId())
                            put("time_zone", "GMT+08:00")
                            put(
                                "event_package",
                                buildJsonObject {
                                    put(
                                        "task_event",
                                        buildJsonObject {
                                            put("type", 1)
                                            put("status", 0)
                                            put("operation_type", 1)
                                            put("operation_direction", 0)
                                            put("session_id", generateSessionId())
                                            put(
                                                "url_package",
                                                buildJsonObject {
                                                    put("page", "GAME_DETAL_PAGE")
                                                    put("identity", generateUuid())
                                                    put("page_type", 2)
                                                    put("params", "{\"game_id\":1001,\"game_name\":\"王者荣耀\"}")
                                                }
                                            )
                                            put("element_package", buildJsonObject {})
                                        }
                                    )
                                }
                            )
                        }
                    )
                }
            )
        }.toString()
    }

    private fun generateSessionId(): String =
        "${randomHex(8)}-${randomHex(4)}-${randomHex(4)}-${randomHex(4)}-${randomHex(12)}"

    private fun generateUuid(): String =
        "${randomHex(8)}-${randomHex(4)}-${randomHex(4)}-${randomHex(4)}-${randomHex(12)}"

    /** 生成 16 位随机页面 ID（字符集与 Dart 一致）。 */
    private fun generatePageId(): String {
        val chars = "useandom-26T198340PX75pxJACKVERYMINDBUSHWOLF_GQZbfghjklqvwyzrict"
        val sb = StringBuilder()
        repeat(16) { sb.append(chars[Random.nextInt(chars.length)]) }
        return sb.toString()
    }

    private fun randomHex(length: Int): String {
        val chars = "0123456789abcdef"
        val sb = StringBuilder()
        repeat(length) { sb.append(chars[Random.nextInt(16)]) }
        return sb.toString()
    }

    // ==================== 工具方法 ====================

    private fun isImage(url: String): Boolean {
        if (url.isEmpty()) {
            return false
        }
        val ext = url.substringAfterLast('.').lowercase()
        return imageExtensions.contains(ext)
    }

    /** 对应 Dart `_parseInt`：JSON 数字按 int/double 取整；字符串仅按整数解析。 */
    private fun parseInt(element: JsonElement?): Int {
        if (element == null || element is JsonNull) {
            return 0
        }
        val primitive = element as? JsonPrimitive ?: return 0
        if (primitive.isString) {
            return primitive.content.trim().toIntOrNull() ?: 0
        }
        primitive.content.trim().toIntOrNull()?.let { return it }
        return primitive.content.trim().toDoubleOrNull()?.toInt() ?: 0
    }

    private fun currentCookieHeader(): String =
        if (customCookie.isNotEmpty()) mergeCookie(customCookie, cookie) else cookie

    private fun mergeCookie(baseCookie: String, extraCookie: String): String {
        val values = parseCookieHeader(baseCookie)
        values.putAll(parseCookieHeader(extraCookie))
        return formatCookieHeader(values)
    }

    private fun parseCookieHeader(cookie: String): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()
        for (part in cookie.split(';')) {
            val item = part.trim()
            if (item.isEmpty()) {
                continue
            }
            val index = item.indexOf('=')
            if (index <= 0) {
                continue
            }
            val key = item.substring(0, index).trim()
            val value = item.substring(index + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }

    private fun formatCookieHeader(values: Map<String, String>): String =
        values.entries.joinToString("; ") { (k, v) -> "$k=$v" }

    /** 取 JsonPrimitive 的字符串；null 或非原始值时返回 null（对应 Dart `?.toString()`）。 */
    private fun nullableString(element: JsonElement?): String? {
        if (element == null || element is JsonNull) {
            return null
        }
        val primitive = element as? JsonPrimitive ?: return null
        return primitive.content
    }
}

/** websocketinfo 接口返回的弹幕连接信息。 */
private data class KuaishouWebsocketInfo(
    val token: String,
    val websocketUrls: List<String>,
) {
    companion object {
        fun empty(): KuaishouWebsocketInfo =
            KuaishouWebsocketInfo(token = "", websocketUrls = emptyList())
    }
}