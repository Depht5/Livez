package com.kotlin_livez.core.site

import com.kotlin_livez.core.common.CoreError
import com.kotlin_livez.core.common.CoreLog
import com.kotlin_livez.core.common.DouyinCookieHelper
import com.kotlin_livez.core.common.DouyinSign
import com.kotlin_livez.core.common.HttpClient
import com.kotlin_livez.core.common.at
import com.kotlin_livez.core.common.intv
import com.kotlin_livez.core.common.str
import com.kotlin_livez.core.danmaku.DouyinDanmaku
import com.kotlin_livez.core.danmaku.DouyinDanmakuArgs
import com.kotlin_livez.core.danmaku.LiveDanmaku
import com.kotlin_livez.core.interfaces.LiveSite
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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * 抖音直播站点实现。
 * 对应 Dart `DouyinSite`（lib/src/douyin_site.dart）。站点 id/name 与冻结文案保持不变；
 * 方法职责一一对应（Dart 拼写错误 getCategores/getPlayQualites 在此修正为 categories/playQualities）。
 *
 * 抖音签名（a_bogus / msToken）与 Cookie 辅助分别由
 * `com.kotlin_livez.core.common.DouyinSign` / `DouyinCookieHelper` 提供。
 * 弹幕由 [DouyinDanmaku] 实现（WebSocket + 手写 protobuf，见 danmaku/DouyinDanmaku.kt）。
 */
class DouyinSite : LiveSite() {

    override var id: String = "douyin"

    override var name: String = "抖音直播"

    /** 用户设置的 cookie */
    var cookie: String = ""

    override fun danmaku(): LiveDanmaku = DouyinDanmaku()

    companion object {
        /** 使用 QQBrowser User-Agent（参考 DouyinLiveRecorder） */
        const val kDefaultUserAgent =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.5845.97 Safari/537.36 Core/1.116.567.400 QQBrowser/19.7.6764.400"

        const val kDefaultReferer = "https://live.douyin.com"

        const val kDefaultAuthority = "live.douyin.com"

        /** 默认 Cookie - 只需要 ttwid 字段即可获取所有画质（包括蓝光） */
        const val kDefaultCookie =
            "ttwid=1%7CB1qls3GdnZhUov9o2NxOMxxYS2ff6OSvEWbv0ytbES4%7C1680522049%7C280d802d6d478e3e78d0c807f7c487e7ffec0ae4e5fdd6a0fe74c3c6af149511"

        /** 直播间 Web Cookie 缓存有效期 */
        private const val webCookieCacheTtlMs = 5 * 60 * 1000L

        /** 直播间 Web Cookie 缓存（webRid|baseCookieHash -> cookie，写入时间戳） */
        private val webCookieCache = ConcurrentHashMap<String, String>()
        private val webCookieCacheAt = ConcurrentHashMap<String, Long>()

        /** 分区图片候选字段（依次尝试，返回第一个非空值） */
        private val partitionImageKeys = listOf(
            "icon", "icons", "cover", "background", "avatar_thumb",
            "image", "image_url", "url", "url_list", "static_icon",
        )
    }

    private fun logDebug(msg: String) {
        CoreLog.d("[Douyin] $msg")
    }

    private fun logElapsed(label: String, startAt: Long) {
        logDebug("$label 耗时 ${System.currentTimeMillis() - startAt}ms")
    }

    // -------------------------------------------------------------------
    // Cookie / 请求头
    // -------------------------------------------------------------------

    private fun newRequestHeaders(
        cookieValue: String?,
        authority: String = kDefaultAuthority,
        referer: String = kDefaultReferer,
    ): Map<String, Any?> {
        val requestHeaders = mutableMapOf<String, Any?>(
            "Authority" to authority,
            "Referer" to referer,
            "User-Agent" to kDefaultUserAgent,
        )
        val normalizedCookie = cookieValue?.trim() ?: ""
        if (normalizedCookie.isNotEmpty()) {
            requestHeaders["cookie"] = normalizedCookie
        }
        return requestHeaders
    }

    /** 默认播放 Cookie：只需要 ttwid 字段 */
    private fun defaultPlaybackCookie(): String {
        return DouyinCookieHelper.extractTtwid(kDefaultCookie) ?: kDefaultCookie
    }

    /** 自定义 Cookie 中的 ttwid */
    private fun customPlaybackCookie(): String {
        return DouyinCookieHelper.extractTtwid(cookie) ?: ""
    }

    /** 播放请求使用的 Cookie：优先自定义 ttwid，否则使用默认匿名 ttwid */
    private fun playbackCookie(): String {
        val customTtwid = customPlaybackCookie()
        return if (customTtwid.isNotEmpty()) customTtwid else defaultPlaybackCookie()
    }

    /** 搜索等需要完整 Cookie 的场景 */
    private fun searchCookie(): String {
        val savedCookie = cookie.trim()
        return if (savedCookie.isNotEmpty()) savedCookie else defaultPlaybackCookie()
    }

    private fun getRequestHeaders(includeFullCookie: Boolean = false): Map<String, Any?> {
        return newRequestHeaders(
            cookieValue = if (includeFullCookie) searchCookie() else playbackCookie(),
        )
    }

    /** 进入直播间前获取弹幕/页面用的 Cookie */
    private suspend fun getDanmakuCookie(webRid: String): String {
        val startAt = System.currentTimeMillis()
        val requestHeaders = getRequestHeaders()
        val baseCookie = getCookieHeaderValue(requestHeaders)
        return try {
            val webCookie = withTimeout(5000L) { getWebCookie(webRid) }
            val merged = mergeCookieValues(
                baseCookie,
                webCookie,
                preferBase = cookie.isNotEmpty(),
            )
            logElapsed("_getDanmakuCookie($webRid)", startAt)
            merged
        } catch (e: Exception) {
            CoreLog.error(e)
            logElapsed("_getDanmakuCookie($webRid) fallback", startAt)
            baseCookie
        }
    }

    private fun mergeCookieValues(
        baseCookie: String,
        extraCookie: String,
        preferBase: Boolean = false,
    ): String {
        val base = parseCookieValue(baseCookie)
        val extra = parseCookieValue(extraCookie)
        val merged = if (preferBase) {
            extra.toMutableMap().apply { putAll(base) }
        } else {
            base.toMutableMap().apply { putAll(extra) }
        }
        return merged.entries.joinToString("; ") { (k, v) -> "$k=$v" }
    }

    private fun parseCookieValue(cookieValue: String): Map<String, String> {
        val cookieMap = LinkedHashMap<String, String>()
        for (part in cookieValue.split(";")) {
            val item = part.trim()
            if (item.isEmpty()) continue
            val separatorIndex = item.indexOf("=")
            if (separatorIndex <= 0) continue
            val key = item.substring(0, separatorIndex).trim()
            val value = item.substring(separatorIndex + 1).trim()
            if (key.isNotEmpty()) cookieMap[key] = value
        }
        return cookieMap
    }

    private fun getCookieHeaderValue(requestHeaders: Map<String, Any?>): String {
        return ((requestHeaders["Cookie"] ?: requestHeaders["cookie"]) ?: "").toString().trim()
    }

    private fun ensureCookieEndsWithSemicolon(value: String): String {
        val cookie = value.trim()
        if (cookie.isEmpty() || cookie.endsWith(";")) {
            return cookie
        }
        return "$cookie;"
    }

    // -------------------------------------------------------------------
    // 分类
    // -------------------------------------------------------------------

    override suspend fun categories(): List<LiveCategory> {
        val categories = mutableListOf<LiveCategory>()
        val result = HttpClient.getText(
            "https://live.douyin.com/",
            query = emptyMap(),
            header = getRequestHeaders(),
        )

        val renderDataJson = extractCategoryRenderData(result)
        val categoryData = renderDataJson["categoryData"]?.jsonArray ?: JsonArray(emptyList())

        for (item in categoryData) {
            val itemObj = item.jsonObject
            val subs = mutableListOf<LiveSubCategory>()
            val partition = itemObj["partition"]?.jsonObject
            val id = "${partition?.get("id_str").str()},${partition?.get("type").str()}"
            val subList = itemObj["sub_partition"]?.jsonArray ?: JsonArray(emptyList())
            for (subItem in subList) {
                val subPart = subItem.jsonObject["partition"]?.jsonObject
                val subCategory = LiveSubCategory(
                    id = "${subPart?.get("id_str").str()},${subPart?.get("type").str()}",
                    name = subPart?.get("title").str(),
                    parentId = id,
                    pic = pickPartitionImageUrl(subPart ?: itemObj["partition"])
                        ?: pickPartitionImageUrl(itemObj["partition"]),
                )
                subs.add(subCategory)
            }

            val category = LiveCategory(
                children = subs,
                id = id,
                name = partition?.get("title").str(),
            )
            subs.add(
                0,
                LiveSubCategory(
                    id = category.id,
                    name = category.name,
                    parentId = category.id,
                    pic = pickPartitionImageUrl(itemObj["partition"]),
                ),
            )
            categories.add(category)
        }
        return categories
    }

    /** 首页 HTML 中的分类渲染数据解析 */
    private fun extractCategoryRenderData(html: String): JsonObject {
        val marker = """\"categoryData\":"""
        val markerIndex = html.indexOf(marker)
        if (markerIndex < 0) {
            throw CoreError("抖音分类数据解析失败")
        }
        val arrayStart = html.indexOf("[", markerIndex)
        if (arrayStart < 0) {
            throw CoreError("抖音分类数据解析失败")
        }
        val escapedArray = extractEscapedJsonArray(html, arrayStart)
        val normalizedJson = """{"categoryData":$escapedArray}"""
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
        return HttpClient.json.parseToJsonElement(normalizedJson).jsonObject
    }

    /** 从 HTML 中摘取转义 JSON 数组（按括号深度闭合） */
    private fun extractEscapedJsonArray(source: String, startIndex: Int): String {
        val buffer = StringBuilder()
        var depth = 0
        var inString = false
        var escaped = false
        for (i in startIndex until source.length) {
            val char = source[i]
            buffer.append(char)

            if (escaped) {
                escaped = false
                continue
            }
            if (char == '\\') {
                escaped = true
                continue
            }
            if (char == '"') {
                inString = !inString
                continue
            }
            if (inString) {
                continue
            }
            if (char == '[') {
                depth += 1
            } else if (char == ']') {
                depth -= 1
                if (depth == 0) {
                    return buffer.toString()
                }
            }
        }
        throw CoreError("抖音分类数据解析失败")
    }

    /**
     * 从分区数据中挑选图标/封面图片 URL。
     * 递归查找常见图片字段，返回第一个非空值。
     */
    private fun pickPartitionImageUrl(data: JsonElement?): String? {
        if (data == null) {
            return null
        }
        return when (data) {
            is JsonPrimitive -> {
                val value = data.content.trim()
                if (value.isEmpty()) null else value
            }
            is JsonArray -> {
                for (item in data) {
                    val value = pickPartitionImageUrl(item)
                    if (value != null && value.isNotEmpty()) {
                        return value
                    }
                }
                null
            }
            is JsonObject -> {
                for (key in partitionImageKeys) {
                    val value = pickPartitionImageUrl(data[key])
                    if (value != null && value.isNotEmpty()) {
                        return value
                    }
                }
                for (value in data.values) {
                    val resolved = pickPartitionImageUrl(value)
                    if (resolved != null && resolved.isNotEmpty()) {
                        return resolved
                    }
                }
                null
            }
            else -> null
        }
    }

    private fun resolveCategoryValue(
        source: JsonElement?,
        keys: List<String>,
        depth: Int = 0,
    ): String? {
        if (depth > 6) {
            return null
        }
        return when (source) {
            is JsonArray -> {
                for (item in source) {
                    val value = resolveCategoryValue(item, keys, depth = depth + 1)
                    if (value != null && value.isNotEmpty()) {
                        return value
                    }
                }
                null
            }
            is JsonObject -> {
                for (key in keys) {
                    val value = source[key].str().trim()
                    if (value.isNotEmpty()) {
                        return value
                    }
                }
                for (value in source.values) {
                    val resolved = resolveCategoryValue(value, keys, depth = depth + 1)
                    if (resolved != null && resolved.isNotEmpty()) {
                        return resolved
                    }
                }
                null
            }
            else -> null
        }
    }

    /** 从直播间数据中解析分类信息（兼容多种字段命名与分区结构） */
    private fun resolveDouyinCategoryInfo(roomData: JsonElement): Map<String, String?> {
        val room = roomData as? JsonObject ?: JsonObject(emptyMap())
        val partitionRoadMap = room["partition_road_map"] ?: room["partitionRoadMap"]
        val partition = room["partition"]
            ?: room["room_partition"]
            ?: room["partitionInfo"]
            ?: (partitionRoadMap as? JsonArray)?.lastOrNull()
        val parentCandidate = room["parent_partition"] ?: room["partition_parent"]
        val parentPartition = parentCandidate
            ?: (partition as? JsonObject)?.let {
                it["parent_partition"] ?: it["partition_parent"] ?: it["parent"]
            }
            ?: (partitionRoadMap as? JsonArray)?.takeIf { it.size > 1 }?.first()
            ?: room["partitionInfo"]

        return mapOf(
            "categoryId" to resolveCategoryValue(partition, listOf("id_str", "id", "partition_id", "partition")),
            "categoryName" to resolveCategoryValue(partition, listOf("title", "name", "partition_title")),
            "categoryParentId" to resolveCategoryValue(parentPartition, listOf("id_str", "id", "partition_id", "partition")),
            "categoryParentName" to resolveCategoryValue(parentPartition, listOf("title", "name", "partition_title")),
            "categoryPic" to (pickPartitionImageUrl(partition) ?: pickPartitionImageUrl(parentPartition)),
        )
    }

    /** 分类接口返回的通用校验与房间列表提取 */
    private fun resolveCategoryRoomData(result: JsonElement?): JsonArray {
        if (result is JsonObject && result["status_code"].intv() == 444) {
            throw CoreError("", statusCode = 444)
        }
        if (result !is JsonObject) {
            throw CoreError("抖音分类接口返回异常")
        }
        val data = result["data"]
        if (data !is JsonObject) {
            throw CoreError("抖音分类接口返回异常，可能已触发访问限制")
        }
        val rooms = data["data"]
        if (rooms !is JsonArray) {
            throw CoreError("抖音分类接口返回异常，可能已触发访问限制")
        }
        return rooms
    }

    /** 类目/推荐房间列表的通用解析 */
    private fun parseCategoryRoomResult(result: JsonElement?): LiveCategoryResult {
        val roomData = resolveCategoryRoomData(result)
        val hasMore = roomData.size >= 15
        val items = roomData.mapNotNull { item ->
            val obj = item.jsonObject
            val urlList = obj.at("room.cover.url_list")?.jsonArray
            LiveRoomItem(
                roomId = obj["web_rid"].str(),
                title = obj.at("room.title").str(),
                cover = urlList?.firstOrNull().str() ?: "",
                userName = obj.at("room.owner.nickname").str(),
                online = obj.at("room.room_view_stats.display_value").str().toIntOrNull() ?: 0,
            )
        }
        return LiveCategoryResult(hasMore = hasMore, items = items)
    }

    override suspend fun categoryRooms(category: LiveSubCategory, page: Int): LiveCategoryResult {
        val ids = category.id.split(',')
        val partitionId = ids.getOrElse(0) { "" }
        val partitionType = ids.getOrElse(1) { "" }

        val serverUrl = "https://live.douyin.com/webcast/web/partition/detail/room/v2/"
        val uri = buildUrl(
            serverUrl,
            mapOf(
                "aid" to "6383",
                "app_name" to "douyin_web",
                "live_id" to "1",
                "device_platform" to "web",
                "language" to "zh-CN",
                "enter_from" to "link_share",
                "cookie_enabled" to "true",
                "screen_width" to "1980",
                "screen_height" to "1080",
                "browser_language" to "zh-CN",
                "browser_platform" to "Win32",
                "browser_name" to "Edge",
                "browser_version" to "125.0.0.0",
                "browser_online" to "true",
                "count" to "15",
                "offset" to ((page - 1) * 15).toString(),
                "partition" to partitionId,
                "partition_type" to partitionType,
                "req_from" to "2",
            ),
        )
        val requestUrl = DouyinSign.getAbogusUrlWithMsToken(uri, kDefaultUserAgent)

        val result = HttpClient.getJson(
            requestUrl,
            header = getRequestHeaders(),
        )
        return parseCategoryRoomResult(result)
    }

    override suspend fun recommendRooms(page: Int): LiveCategoryResult {
        val serverUrl = "https://live.douyin.com/webcast/web/partition/detail/room/v2/"
        val uri = buildUrl(
            serverUrl,
            mapOf(
                "aid" to "6383",
                "app_name" to "douyin_web",
                "live_id" to "1",
                "device_platform" to "web",
                "language" to "zh-CN",
                "enter_from" to "link_share",
                "cookie_enabled" to "true",
                "screen_width" to "1980",
                "screen_height" to "1080",
                "browser_language" to "zh-CN",
                "browser_platform" to "Win32",
                "browser_name" to "Edge",
                "browser_version" to "125.0.0.0",
                "browser_online" to "true",
                "count" to "15",
                "offset" to ((page - 1) * 15).toString(),
                "partition" to "720",
                "partition_type" to "1",
                "req_from" to "2",
            ),
        )
        val requestUrl = DouyinSign.getAbogusUrlWithMsToken(uri, kDefaultUserAgent)

        val result = HttpClient.getJson(
            requestUrl,
            header = getRequestHeaders(),
        )
        return parseCategoryRoomResult(result)
    }

    // -------------------------------------------------------------------
    // 房间详情
    // -------------------------------------------------------------------

    override suspend fun roomDetail(roomId: String): LiveRoomDetail {
        val startAt = System.currentTimeMillis()
        try {
            // 有两种 roomId：webRid（固定，11-12 位）与 roomId（每次开播变化，一般 19 位）。
            // 这里简单判断，长度小于等于 16 视为 webRid。
            if (roomId.length <= 16) {
                val webRid = roomId
                return getRoomDetailByWebRid(webRid)
            }
            return getRoomDetailByRoomId(roomId)
        } finally {
            logElapsed("roomDetail($roomId)", startAt)
        }
    }

    /** 通过 roomId 获取直播间信息 */
    private suspend fun getRoomDetailByRoomId(roomId: String): LiveRoomDetail {
        val startAt = System.currentTimeMillis()
        // 读取房间信息
        val roomData = getRoomDataByRoomId(roomId)
        val room = roomData["data"]?.jsonObject?.get("room")
        if (room !is JsonObject) {
            throw CoreError("抖音直播间数据为空，可能是房间不存在、未开播或被风控限制")
        }

        // 通过房间信息获取 WebRid
        val webRid = room["owner"]?.jsonObject?.get("web_rid").str()

        // 读取用户唯一 ID，用于弹幕连接（似非必须，随机生成）
        val userUniqueId = generateRandomNumber(12).toString()

        val owner = room["owner"]?.jsonObject
        val categoryInfo = resolveDouyinCategoryInfo(room)

        val status = room["status"].intv()

        // roomId 是一次性的，若对应直播间不是直播中状态，就通过 webRid 重新获取
        if (status == 4) {
            val result = getRoomDetailByWebRid(webRid)
            logElapsed("getRoomDetailByRoomId($roomId) redirect", startAt)
            return result
        }

        val roomStatus = status == 2
        // 主要是为了获取 cookie，用于弹幕 websocket 连接
        val danmakuCookie = getDanmakuCookie(webRid)

        val detail = LiveRoomDetail(
            roomId = webRid,
            title = room["title"].str(),
            cover = if (roomStatus) (room.at("cover.url_list")?.jsonArray?.firstOrNull().str() ?: "") else "",
            userName = owner?.get("nickname").str(),
            userAvatar = owner?.at("avatar_thumb.url_list")?.jsonArray?.firstOrNull().str() ?: "",
            online = if (roomStatus) room.at("room_view_stats.display_value").intv() else 0,
            status = roomStatus,
            url = "https://live.douyin.com/$webRid",
            introduction = owner?.get("signature").str(),
            notice = "",
            categoryId = categoryInfo["categoryId"],
            categoryName = categoryInfo["categoryName"],
            categoryParentId = categoryInfo["categoryParentId"],
            categoryParentName = categoryInfo["categoryParentName"],
            categoryPic = categoryInfo["categoryPic"],
            danmakuData = DouyinDanmakuArgs(
                webRid = webRid,
                roomId = roomId,
                userId = userUniqueId,
                cookie = danmakuCookie,
            ),
            data = room["stream_url"],
        )
        logElapsed("getRoomDetailByRoomId($roomId)", startAt)
        return detail
    }

    /** 通过 WebRid 获取直播间信息：优先 API，失败后回落 HTML */
    private suspend fun getRoomDetailByWebRid(webRid: String): LiveRoomDetail {
        val startAt = System.currentTimeMillis()
        try {
            val result = getRoomDetailByWebRidApi(webRid)
            logElapsed("getRoomDetailByWebRid($webRid) api", startAt)
            return result
        } catch (e: Exception) {
            CoreLog.error(e)
            if (e is CoreError && e.statusCode == 444) {
                throw e
            }
        }
        val result = getRoomDetailByWebRidHtml(webRid)
        logElapsed("getRoomDetailByWebRid($webRid) html", startAt)
        return result
    }

    /** 通过 WebRid 访问直播间 API 获取直播间信息 */
    private suspend fun getRoomDetailByWebRidApi(webRid: String): LiveRoomDetail {
        val startAt = System.currentTimeMillis()
        // 读取房间信息
        val data = getRoomDataByApi(webRid)

        val roomData = data["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw RuntimeException("抖音房间数据为空")
        val userData = data["user"]?.jsonObject
        val roomId = roomData["id_str"].str()
        val categoryInfo = resolveDouyinCategoryInfo(roomData)

        // 读取用户唯一 ID，用于弹幕连接（似非必须，随机生成）
        val userUniqueId = generateRandomNumber(12).toString()

        val owner = roomData["owner"]?.jsonObject

        val roomStatus = roomData["status"].intv() == 2

        // 主要是为了获取 cookie，用于弹幕 websocket 连接
        val danmakuCookie = getDanmakuCookie(webRid)
        val detail = LiveRoomDetail(
            roomId = webRid,
            title = roomData["title"].str(),
            cover = if (roomStatus) (roomData.at("cover.url_list")?.jsonArray?.firstOrNull().str() ?: "") else "",
            userName = if (roomStatus) owner?.get("nickname").str() else userData?.get("nickname").str(),
            userAvatar = if (roomStatus) {
                owner?.at("avatar_thumb.url_list")?.jsonArray?.firstOrNull().str() ?: ""
            } else {
                userData?.at("avatar_thumb.url_list")?.jsonArray?.firstOrNull().str() ?: ""
            },
            online = if (roomStatus) roomData.at("room_view_stats.display_value").intv() else 0,
            status = roomStatus,
            url = "https://live.douyin.com/$webRid",
            introduction = owner?.get("signature").str(),
            notice = "",
            categoryId = categoryInfo["categoryId"],
            categoryName = categoryInfo["categoryName"],
            categoryParentId = categoryInfo["categoryParentId"],
            categoryParentName = categoryInfo["categoryParentName"],
            categoryPic = categoryInfo["categoryPic"],
            danmakuData = DouyinDanmakuArgs(
                webRid = webRid,
                roomId = roomId,
                userId = userUniqueId,
                cookie = danmakuCookie,
            ),
            data = if (roomStatus) roomData["stream_url"] else JsonObject(emptyMap()),
        )
        logElapsed("_getRoomDetailByWebRidApi($webRid)", startAt)
        return detail
    }

    /** 通过 WebRid 访问直播间网页，从 HTML 中获取直播间信息 */
    private suspend fun getRoomDetailByWebRidHtml(webRid: String): LiveRoomDetail {
        val startAt = System.currentTimeMillis()
        val roomData = getRoomDataByHtml(webRid)
        val roomId = roomData.at("roomStore.roomInfo.room.id_str").str()
        val userUniqueId = resolveUserUniqueIdFromRoomData(roomData)

        val room = roomData.at("roomStore.roomInfo.room")?.jsonObject
        val owner = room?.get("owner")?.jsonObject
        val anchor = roomData.at("roomStore.roomInfo.anchor")?.jsonObject
        val categoryInfo = room?.let { resolveDouyinCategoryInfo(it) } ?: emptyMap()
        val roomStatus = room?.get("status").intv() == 2

        // 主要是为了获取 cookie，用于弹幕 websocket 连接
        val danmakuCookie = getDanmakuCookie(webRid)

        val detail = LiveRoomDetail(
            roomId = webRid,
            title = room?.get("title").str(),
            cover = if (roomStatus) (room?.at("cover.url_list")?.jsonArray?.firstOrNull().str() ?: "") else "",
            userName = if (roomStatus) owner?.get("nickname").str() else anchor?.get("nickname").str(),
            userAvatar = if (roomStatus) {
                owner?.at("avatar_thumb.url_list")?.jsonArray?.firstOrNull().str() ?: ""
            } else {
                anchor?.at("avatar_thumb.url_list")?.jsonArray?.firstOrNull().str() ?: ""
            },
            online = if (roomStatus) room?.at("room_view_stats.display_value").intv() else 0,
            status = roomStatus,
            url = "https://live.douyin.com/$webRid",
            introduction = owner?.get("signature").str(),
            notice = "",
            categoryId = categoryInfo["categoryId"],
            categoryName = categoryInfo["categoryName"],
            categoryParentId = categoryInfo["categoryParentId"],
            categoryParentName = categoryInfo["categoryParentName"],
            categoryPic = categoryInfo["categoryPic"],
            danmakuData = DouyinDanmakuArgs(
                webRid = webRid,
                roomId = roomId,
                userId = userUniqueId,
                cookie = danmakuCookie,
            ),
            data = if (roomStatus) room?.get("stream_url") else JsonObject(emptyMap()),
        )
        logElapsed("_getRoomDetailByWebRidHtml($webRid)", startAt)
        return detail
    }

    /** 从页面渲染数据中解析用户唯一 ID */
    private fun resolveUserUniqueIdFromRoomData(roomData: JsonElement): String {
        val resolved = resolveNestedString(roomData, listOf("userStore", "odin", "user_unique_id"))
        if (resolved != null && resolved.isNotEmpty()) {
            return resolved
        }
        val fallback = resolveNestedString(roomData, listOf("userStore", "user", "user_unique_id"))
        if (fallback != null && fallback.isNotEmpty()) {
            return fallback
        }
        return generateRandomNumber(12).toString()
    }

    private fun resolveNestedString(source: JsonElement?, path: List<String>): String? {
        var current: JsonElement? = source
        for (key in path) {
            if (current !is JsonObject) {
                return null
            }
            current = current[key]
            if (current == null) {
                return null
            }
        }
        val value = when (current) {
            is JsonPrimitive -> current.content
            else -> current.toString()
        }.trim()
        return if (value.isEmpty()) null else value
    }

    /** 进入直播间前需要先获取 cookie（HEAD 请求带缓存） */
    private suspend fun getWebCookie(webRid: String): String {
        val requestHeaders = getRequestHeaders().toMutableMap()
        val baseCookie = getCookieHeaderValue(requestHeaders)
        val cacheKey = "$webRid|${baseCookie.hashCode()}"
        val cachedAt = webCookieCacheAt[cacheKey]
        val cachedValue = webCookieCache[cacheKey]
        if (cachedAt != null &&
            cachedValue != null &&
            System.currentTimeMillis() - cachedAt < webCookieCacheTtlMs
        ) {
            logDebug("_getWebCookie($webRid) 使用缓存")
            return cachedValue
        }
        val startAt = System.currentTimeMillis()
        requestHeaders["Referer"] = "https://live.douyin.com/$webRid"
        var headResp: Response? = null
        try {
            headResp = HttpClient.head(
                "https://live.douyin.com/$webRid",
                header = requestHeaders,
            )
        } catch (e: Exception) {
            if (baseCookie.isNotEmpty()) {
                logDebug("获取直播间 Web Cookie 的 HEAD 请求失败，使用已保存 Cookie 继续：$e")
                webCookieCache[cacheKey] = baseCookie
                webCookieCacheAt[cacheKey] = System.currentTimeMillis()
                logElapsed("_getWebCookie($webRid) fallback", startAt)
                return baseCookie
            }
            throw e
        }
        if (headResp?.code == 444) {
            throw CoreError("", statusCode = 444)
        }
        var dyCookie = if (baseCookie.isNotEmpty()) {
            ensureCookieEndsWithSemicolon(baseCookie)
        } else {
            ""
        }
        headResp?.headers("set-cookie")?.forEach { element ->
            val cookie = element.split(";").firstOrNull() ?: ""
            if (cookie.contains("ttwid") || cookie.contains("__ac_nonce") || cookie.contains("msToken")) {
                dyCookie += "$cookie;"
            }
        }
        webCookieCache[cacheKey] = dyCookie
        webCookieCacheAt[cacheKey] = System.currentTimeMillis()
        logElapsed("_getWebCookie($webRid)", startAt)
        return dyCookie
    }

    /** 通过 webRid 获取直播间 Web 信息（HTML 解析） */
    private suspend fun getRoomDataByHtml(webRid: String): JsonObject {
        val startAt = System.currentTimeMillis()
        val dyCookie = getWebCookie(webRid)
        val requestStartAt = System.currentTimeMillis()
        val result = HttpClient.getText(
            "https://live.douyin.com/$webRid",
            query = emptyMap(),
            header = mapOf(
                "Authority" to kDefaultAuthority,
                "Referer" to kDefaultReferer,
                "Cookie" to dyCookie,
                "User-Agent" to kDefaultUserAgent,
            ),
        )
        logElapsed("_getRoomDataByHtml($webRid) request", requestStartAt)
        val parseStartAt = System.currentTimeMillis()
        if (result.trim().isEmpty()) {
            throw CoreError("抖音直播间页面返回为空，请稍后再试")
        }
        if (!result.contains("\\\"state\\\"")) {
            throw CoreError("抖音直播间页面数据不可用，可能是访问受限或页面结构已变化")
        }

        val renderData = Regex("""\{\\"state\\":\{\\"appStore.*?\]\\n""").find(result)?.value ?: ""
        if (renderData.isEmpty()) {
            throw CoreError("抖音直播间页面数据解析失败，请稍后再试")
        }
        val str = renderData
            .trim()
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("]\\n", "")
        val renderDataJson = HttpClient.json.parseToJsonElement(str)
        val state = renderDataJson.jsonObject["state"]
        if (state !is JsonObject) {
            throw CoreError("抖音直播间页面状态数据异常")
        }
        logElapsed("_getRoomDataByHtml($webRid) parse", parseStartAt)
        logElapsed("_getRoomDataByHtml($webRid)", startAt)
        return state
    }

    /** 通过 webRid 访问直播间 API，返回 data 部分 */
    private suspend fun getRoomDataByApi(webRid: String): JsonObject {
        val startAt = System.currentTimeMillis()
        val result = requestWithCustomCookieFallback(
            label = "抖音房间接口",
            request = { cookieValue -> requestRoomDataByApi(webRid, cookieValue) },
            hasData = ::hasWebRoomData,
        )

        if (result !is JsonObject) {
            throw Exception("抖音接口返回格式异常")
        }

        val data = result["data"]
        if (data !is JsonObject) {
            throw CoreError("抖音直播间数据为空，请稍后再试")
        }
        val rooms = data["data"]
        if (rooms !is JsonArray || rooms.isEmpty()) {
            throw CoreError("抖音直播间数据为空，可能是房间不存在、未开播或被风控限制")
        }

        logElapsed("_getRoomDataByApi($webRid)", startAt)
        return data
    }

    private suspend fun requestRoomDataByApi(webRid: String, cookieValue: String): JsonElement? {
        val serverUrl = "https://live.douyin.com/webcast/room/web/enter/"
        val requestHeader = newRequestHeaders(
            cookieValue = cookieValue,
            referer = "https://live.douyin.com/$webRid",
        )

        val uri = buildUrl(
            serverUrl,
            mapOf(
                "aid" to "6383",
                "app_name" to "douyin_web",
                "live_id" to "1",
                "device_platform" to "web",
                "language" to "zh-CN",
                "browser_language" to "zh-CN",
                "browser_platform" to "Win32",
                "browser_name" to "Chrome",
                "browser_version" to "125.0.0.0",
                "web_rid" to webRid,
                "msToken" to "",
            ),
        )
        val signStartAt = System.currentTimeMillis()
        val requestUrl = DouyinSign.getAbogusUrlWithMsToken(uri, kDefaultUserAgent)
        logElapsed("_getRoomDataByApi($webRid) a_bogus", signStartAt)

        val requestStartAt = System.currentTimeMillis()
        val result = HttpClient.getJson(
            requestUrl,
            header = requestHeader,
        )
        logElapsed("_getRoomDataByApi($webRid) request", requestStartAt)
        return result
    }

    /** 通过 roomId 获取直播间信息（reflow 接口） */
    private suspend fun getRoomDataByRoomId(roomId: String): JsonObject {
        val result = requestWithCustomCookieFallback(
            label = "抖音 reflow 接口",
            request = { cookieValue -> requestRoomDataByRoomId(roomId, cookieValue) },
            hasData = ::hasReflowRoomData,
        )
        return result as? JsonObject ?: throw Exception("抖音 reflow 接口返回格式异常")
    }

    /**
     * 带自定义 Cookie 兜底的请求：
     * 自定义 Cookie 请求失败或返回空数据时，使用匿名 ttwid 重试一次。
     */
    private suspend fun requestWithCustomCookieFallback(
        label: String,
        request: suspend (String) -> JsonElement?,
        hasData: (JsonElement?) -> Boolean,
    ): JsonElement? {
        val customPlaybackCookie = customPlaybackCookie()
        val firstResult = try {
            request(playbackCookie())
        } catch (error: Exception) {
            if (customPlaybackCookie.isEmpty()) {
                throw error
            }
            logDebug("$label 使用自定义 Cookie 请求失败，使用匿名 ttwid 重试：$error")
            return request(defaultPlaybackCookie())
        }
        if (customPlaybackCookie.isEmpty() || hasData(firstResult)) {
            return firstResult
        }
        logDebug("$label 返回空数据，使用匿名 ttwid 重试一次")
        return request(defaultPlaybackCookie())
    }

    private suspend fun requestRoomDataByRoomId(roomId: String, cookieValue: String): JsonElement? {
        return HttpClient.getJson(
            "https://webcast.amemv.com/webcast/room/reflow/info/",
            query = mapOf(
                "type_id" to 0,
                "live_id" to 1,
                "room_id" to roomId,
                "sec_user_id" to "",
                "version_code" to "99.99.99",
                "app_id" to 6383,
            ),
            header = newRequestHeaders(cookieValue = cookieValue),
        )
    }

    private fun hasWebRoomData(result: JsonElement?): Boolean {
        if (result !is JsonObject) {
            return false
        }
        val data = result["data"]
        if (data !is JsonObject) {
            return false
        }
        val rooms = data["data"]
        return rooms is JsonArray && rooms.any { it is JsonObject && it.isNotEmpty() }
    }

    private fun hasReflowRoomData(result: JsonElement?): Boolean {
        if (result !is JsonObject) {
            return false
        }
        val data = result["data"]
        if (data !is JsonObject) {
            return false
        }
        val room = data["room"]
        return room is JsonObject && room.isNotEmpty()
    }

    // -------------------------------------------------------------------
    // 播放
    // -------------------------------------------------------------------

    override suspend fun playQualities(detail: LiveRoomDetail): List<LivePlayQuality> {
        val startAt = System.currentTimeMillis()
        val qualities = mutableListOf<LivePlayQuality>()

        try {
            val detailData = detail.data as? JsonElement
            val liveCoreData = detailData?.jsonObject?.get("live_core_sdk_data")
            if (liveCoreData == null) {
                return qualities
            }

            val pullData = liveCoreData.jsonObject["pull_data"]
            if (pullData == null) {
                return qualities
            }
            val pullObj = pullData.jsonObject

            val options = pullObj["options"]?.jsonObject
            val qualityList = options?.get("qualities")?.jsonArray ?: JsonArray(emptyList())

            val streamData = pullObj["stream_data"].str()

            if (!streamData.startsWith("{")) {
                // 非 stream_data 模式：直接按 level 从 flv/hls 列表倒序取链接
                val flvList = detailData?.jsonObject?.get("flv_pull_url")?.jsonObject
                    ?.values?.map { it.str() }.orEmpty()
                val hlsList = detailData?.jsonObject?.get("hls_pull_url_map")?.jsonObject
                    ?.values?.map { it.str() }.orEmpty()
                for (qualityItem in qualityList) {
                    val q = qualityItem.jsonObject
                    val level = q["level"].intv()
                    val urls = mutableListOf<String>()
                    val flvIndex = flvList.size - level
                    if (flvIndex >= 0 && flvIndex < flvList.size) {
                        urls.add(flvList[flvIndex])
                    }
                    val hlsIndex = hlsList.size - level
                    if (hlsIndex >= 0 && hlsIndex < hlsList.size) {
                        urls.add(hlsList[hlsIndex])
                    }
                    val qualityEntry = LivePlayQuality(
                        quality = q["name"].str(),
                        sort = level,
                        data = urls,
                    )
                    if (urls.isNotEmpty()) {
                        qualities.add(qualityEntry)
                    }
                }
            } else {
                // stream_data 模式：按 sdk_key 从解析出的数据中取 flv/hls
                val qualityData = HttpClient.json.parseToJsonElement(streamData)
                    .jsonObject["data"]?.jsonObject
                for (qualityItem in qualityList) {
                    val q = qualityItem.jsonObject
                    val urls = mutableListOf<String>()
                    val mainObj = qualityData?.get(q["sdk_key"].str())
                        ?.jsonObject?.get("main")?.jsonObject
                    val flvUrl = mainObj?.get("flv").str()
                    if (flvUrl.isNotEmpty()) {
                        urls.add(flvUrl)
                    }
                    val hlsUrl = mainObj?.get("hls").str()
                    if (hlsUrl.isNotEmpty()) {
                        urls.add(hlsUrl)
                    }
                    val qualityEntry = LivePlayQuality(
                        quality = q["name"].str(),
                        sort = q["level"].intv(),
                        data = urls,
                    )
                    if (urls.isNotEmpty()) {
                        qualities.add(qualityEntry)
                    }
                }
            }
        } catch (e: Exception) {
            CoreLog.error(e)
        }
        qualities.sort()
        logDebug("获取到的画质列表: ${qualities.map { it.quality }.toList()}")
        logElapsed("playQualities(${detail.roomId})", startAt)
        return qualities
    }

    override suspend fun playUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl {
        val startAt = System.currentTimeMillis()
        // 返回列表的副本，防止外部 clear() 影响原始数据
        val urls = (quality.data as? List<*>)?.mapNotNull { it as? String }?.toList() ?: emptyList()
        val result = LivePlayUrl(urls = urls)
        logElapsed("playUrls(${detail.roomId}, ${quality.quality})", startAt)
        return result
    }

    // -------------------------------------------------------------------
    // 搜索
    // -------------------------------------------------------------------

    override suspend fun searchRooms(keyword: String, page: Int): LiveSearchRoomResult {
        val serverUrl = "https://www.douyin.com/aweme/v1/web/live/search/"
        val uri = buildUrl(
            serverUrl,
            mapOf(
                "device_platform" to "webapp",
                "aid" to "6383",
                "channel" to "channel_pc_web",
                "search_channel" to "aweme_live",
                "keyword" to keyword,
                "search_source" to "switch_tab",
                "query_correct_type" to "1",
                "is_filter_search" to "0",
                "from_group_id" to "",
                "offset" to ((page - 1) * 10).toString(),
                "count" to "10",
                "pc_client_type" to "1",
                "version_code" to "170400",
                "version_name" to "17.4.0",
                "cookie_enabled" to "true",
                "screen_width" to "1980",
                "screen_height" to "1080",
                "browser_language" to "zh-CN",
                "browser_platform" to "Win32",
                "browser_name" to "Edge",
                "browser_version" to "125.0.0.0",
                "browser_online" to "true",
                "engine_name" to "Blink",
                "engine_version" to "125.0.0.0",
                "os_name" to "Windows",
                "os_version" to "10",
                "cpu_core_num" to "12",
                "device_memory" to "8",
                "platform" to "PC",
                "downlink" to "10",
                "effective_type" to "4g",
                "round_trip_time" to "100",
                "webid" to "7382872326016435738",
            ),
        )
        val requestUrl = uri
        val requestHeaders = getRequestHeaders(includeFullCookie = true)
        var dyCookie = ""
        val savedCookie = getCookieHeaderValue(requestHeaders)
        if (savedCookie.isNotEmpty()) {
            dyCookie = ensureCookieEndsWithSemicolon(savedCookie)
        }
        var headResp: Response? = null
        try {
            headResp = HttpClient.head(
                "https://live.douyin.com",
                header = requestHeaders,
            )
        } catch (e: Exception) {
            if (dyCookie.isEmpty()) {
                throw e
            }
            logDebug("抖音搜索预取 Cookie 的 HEAD 请求失败，使用已保存 Cookie 继续：$e")
        }
        if (headResp != null) {
            headResp.headers("set-cookie").forEach { element ->
                val cookie = element.split(";").firstOrNull() ?: ""
                if (cookie.contains("ttwid") || cookie.contains("__ac_nonce")) {
                    dyCookie += "$cookie;"
                }
            }
        }

        val result = HttpClient.getJson(
            requestUrl,
            header = mapOf(
                "Authority" to "www.douyin.com",
                "accept" to "application/json, text/plain, */*",
                "accept-language" to "zh-CN,zh;q=0.9,en;q=0.8",
                "cookie" to dyCookie,
                "priority" to "u=1, i",
                "referer" to "https://www.douyin.com/search/${URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")}?type=live",
                "sec-ch-ua" to "\"Microsoft Edge\";v=\"125\", \"Chromium\";v=\"125\", \"Not.A/Brand\";v=\"24\"",
                "sec-ch-ua-mobile" to "?0",
                "sec-ch-ua-platform" to "\"Windows\"",
                "sec-fetch-dest" to "empty",
                "sec-fetch-mode" to "cors",
                "sec-fetch-site" to "same-origin",
                "user-agent" to kDefaultUserAgent,
            ),
        )
        if (result == null) {
            throw Exception("抖音直播搜索被限制，请稍后再试")
        }
        if (result is JsonObject && result["status_code"].intv() == 2483) {
            throw Exception("抖音搜索需要登录，请在账号管理中通过网页登录或手动配置完整抖音 Cookie")
        }
        val items = mutableListOf<LiveRoomItem>()
        (result.jsonObject["data"]?.jsonArray ?: JsonArray(emptyList())).forEach { item ->
            val itemObj = item.jsonObject
            val rawData = HttpClient.json
                .parseToJsonElement(itemObj["lives"]?.jsonObject?.get("rawdata").str())
                .jsonObject
            items.add(
                LiveRoomItem(
                    roomId = rawData["owner"]?.jsonObject?.get("web_rid").str(),
                    title = rawData["title"].str(),
                    cover = rawData.at("cover.url_list")?.jsonArray?.firstOrNull().str() ?: "",
                    userName = rawData["owner"]?.jsonObject?.get("nickname").str(),
                    online = rawData.at("stats.total_user").str().toIntOrNull() ?: 0,
                ),
            )
        }
        return LiveSearchRoomResult(hasMore = items.size >= 10, items = items)
    }

    override suspend fun searchAnchors(keyword: String, page: Int): LiveSearchAnchorResult {
        val result = searchRooms(keyword, page)
        val lowerKeyword = keyword.trim().lowercase()
        val rooms = result.items.toMutableList()
        rooms.sortWith(Comparator { a, b ->
            val aMatched = a.userName.lowercase().contains(lowerKeyword)
            val bMatched = b.userName.lowercase().contains(lowerKeyword)
            if (aMatched != bMatched) {
                if (aMatched) -1 else 1
            } else {
                b.online - a.online
            }
        })
        return LiveSearchAnchorResult(
            hasMore = result.hasMore,
            items = rooms.map { room ->
                LiveAnchorItem(
                    roomId = room.roomId,
                    userName = room.userName,
                    avatar = room.cover,
                    liveStatus = true,
                )
            },
        )
    }

    // -------------------------------------------------------------------
    // 直播状态
    // -------------------------------------------------------------------

    override suspend fun liveStatus(roomId: String): Boolean {
        val targetId = roomId.trim()
        if (targetId.isEmpty()) {
            return false
        }
        var resolvedDetail: LiveRoomDetail? = null
        return try {
            val status = tryGetLiveStatus(targetId)
            if (status == true) {
                return true
            }
            resolvedDetail = roomDetail(targetId)
            if (resolvedDetail != null && resolvedDetail.status) {
                return true
            }
            if (status != null) {
                return status
            }
            resolvedDetail?.status ?: false
        } catch (e: Exception) {
            if (e is CoreError && e.statusCode == 444) {
                throw e
            }
            if (resolvedDetail != null) {
                return resolvedDetail.status
            }
            CoreLog.error(e)
            false
        }
    }

    /** 两路尝试：webRid 接口与 roomId 接口，任一路成功即返回 */
    private suspend fun tryGetLiveStatus(targetId: String): Boolean? {
        val attempts = mutableListOf<suspend () -> Boolean?>()
        if (targetId.length <= 16) {
            attempts.add { getLiveStatusByWebRid(targetId) }
            attempts.add { getLiveStatusByRoomId(targetId) }
        } else {
            attempts.add { getLiveStatusByRoomId(targetId) }
            attempts.add { getLiveStatusByWebRid(targetId) }
        }

        var lastError: Throwable? = null
        for (i in attempts.indices) {
            try {
                return attempts[i]()
            } catch (e: Exception) {
                if (e is CoreError && e.statusCode == 444) {
                    throw e
                }
                lastError = e
                if (i == 0) {
                    logDebug("getLiveStatus($targetId) 第1路失败，尝试第2路：$e")
                }
            }
        }

        if (lastError != null) {
            CoreLog.error(lastError)
        }
        return null
    }

    private suspend fun getLiveStatusByWebRid(webRid: String): Boolean {
        val data = getRoomDataByApi(webRid)
        val roomList = data["data"]
        if (roomList is JsonArray && roomList.isNotEmpty()) {
            val roomData = roomList.first()
            return isDouyinLiveStatus(roomData)
        }
        throw CoreError("抖音直播状态数据为空")
    }

    private suspend fun getLiveStatusByRoomId(roomId: String): Boolean? {
        val roomData = getRoomDataByRoomId(roomId)
        val room = roomData["data"]?.jsonObject?.get("room")
        if (room !is JsonObject) {
            return null
        }
        val status = parseDouyinStatus(room["status"] ?: room["live_status"] ?: room["room_status"])
        if (status == null) {
            return null
        }
        if (status == 4) {
            val webRid = room["owner"]?.jsonObject?.get("web_rid").str().trim()
            if (webRid.isNotEmpty()) {
                return getLiveStatusByWebRid(webRid)
            }
        }
        return status == 2
    }

    private fun isDouyinLiveStatus(data: JsonElement): Boolean {
        if (data !is JsonObject) {
            return false
        }
        val candidates = listOf(data["status"], data["live_status"], data["room_status"], data["status_str"])
        for (candidate in candidates) {
            val parsed = parseDouyinStatus(candidate)
            if (parsed != null) {
                return parsed == 2
            }
        }
        return false
    }

    private fun parseDouyinStatus(value: JsonElement?): Int? {
        return when (value) {
            is JsonPrimitive -> {
                value.content.trim().toIntOrNull()
                    ?: value.content.trim().toDoubleOrNull()?.toInt()
            }
            is JsonObject -> {
                for (key in listOf("status", "live_status", "room_status")) {
                    val parsed = parseDouyinStatus(value[key])
                    if (parsed != null) {
                        return parsed
                    }
                }
                null
            }
            else -> null
        }
    }

    // -------------------------------------------------------------------
    // SC 与贡献榜
    // -------------------------------------------------------------------

    override suspend fun superChatMessage(roomId: String, detail: LiveRoomDetail?): List<LiveSuperChatMessage> {
        return emptyList()
    }

    override suspend fun contributionRank(roomId: String, detail: LiveRoomDetail?): List<LiveContributionRankItem> {
        val roomDetail = detail ?: roomDetail(roomId)
        val webRid = if (roomDetail.roomId.isNotEmpty()) roomDetail.roomId else roomId
        val danmakuArgs = roomDetail.danmakuData as? DouyinDanmakuArgs

        val roomInfo = getRoomDataByApi(webRid)
        val roomList = roomInfo["data"]?.jsonArray ?: JsonArray(emptyList())
        if (roomList.isEmpty()) {
            return emptyList()
        }
        val roomData = roomList.first().jsonObject
        val owner = roomData["owner"]?.jsonObject ?: roomInfo["user"]?.jsonObject ?: JsonObject(emptyMap())
        val anchorId = owner["id_str"].str().ifEmpty { owner["id"].str() }
        val secAnchorId = owner["sec_uid"].str()
        val realRoomId = danmakuArgs?.roomId ?: roomData["id_str"].str()
        if (anchorId.isEmpty() || secAnchorId.isEmpty() || realRoomId.isEmpty()) {
            return emptyList()
        }

        val requestHeader = getRequestHeaders().toMutableMap()
        requestHeader["Referer"] = "https://live.douyin.com/$webRid"

        val uri = buildUrl(
            "https://live.douyin.com/webcast/ranklist/audience/",
            mapOf(
                "aid" to "6383",
                "app_name" to "douyin_web",
                "live_id" to "1",
                "device_platform" to "web",
                "language" to "zh-CN",
                "enter_from" to "link_share",
                "cookie_enabled" to "true",
                "screen_width" to "1920",
                "screen_height" to "1080",
                "browser_language" to "zh-CN",
                "browser_platform" to "Win32",
                "browser_name" to "Chrome",
                "browser_version" to "125.0.0.0",
                "os_name" to "Windows",
                "os_version" to "10",
                "webcast_sdk_version" to "2450",
                "room_id" to realRoomId,
                "anchor_id" to anchorId,
                "sec_anchor_id" to secAnchorId,
                "ignoreToast" to "true",
                "rank_type" to "30",
                "msToken" to "",
            ),
        )
        val requestUrl = DouyinSign.getAbogusUrlWithMsToken(uri, kDefaultUserAgent)
        val result = HttpClient.getJson(
            requestUrl,
            header = requestHeader,
        )
        val items = result?.jsonObject?.get("data")?.jsonObject?.get("ranks")?.jsonArray
            ?: JsonArray(emptyList())
        return items.mapIndexedNotNull { index, item ->
            val obj = item.jsonObject
            val user = obj["user"]?.jsonObject ?: JsonObject(emptyMap())
            val payGrade = user["pay_grade"]?.jsonObject ?: JsonObject(emptyMap())
            val fansData = user["fans_club"]?.jsonObject?.get("data")?.jsonObject ?: JsonObject(emptyMap())
            val userLevel = payGrade["level"].str().toIntOrNull()
            val fansLevel = fansData["level"].str().toIntOrNull()
            val scoreText = resolveDouyinRankScore(obj)
            val scoreDescription = obj["score_description"].str().trim()
            val exactlyScore = obj["exactly_score"].str().trim()
            val scoreDetail = when {
                scoreDescription.isNotEmpty() && scoreDescription != scoreText -> scoreDescription
                exactlyScore.isNotEmpty() && exactlyScore != scoreText -> exactlyScore
                else -> {
                    val gapDescription = obj["gap_description"].str().trim()
                    if (gapDescription.isEmpty()) null else gapDescription
                }
            }

            val itemUserName = user["nickname"].str()
            if (itemUserName.trim().isEmpty()) {
                return@mapIndexedNotNull null
            }

            LiveContributionRankItem(
                rank = resolveDouyinRank(obj, index),
                userName = itemUserName,
                avatar = firstImageUrl(user["avatar_thumb"]),
                scoreText = scoreText,
                scoreDetail = scoreDetail,
                userLevel = userLevel,
                userLevelText = if (userLevel == null || userLevel <= 0) null else "财富 $userLevel",
                userLevelIcon = firstImageUrl(payGrade["new_im_icon_with_level"]),
                fansLevel = fansLevel,
                fansName = fansData["club_name"].str(),
                fansIcon = pickDouyinBadgeIcon(fansData["badge"]?.jsonObject?.get("icons")),
            )
        }
    }

    private fun resolveDouyinRank(item: JsonObject, index: Int): Int {
        val parsed = item["rank"].str().toIntOrNull()
        if (parsed == null || parsed <= 0) {
            return index + 1
        }
        if (parsed == 1 && index > 0) {
            return index + 1
        }
        return parsed
    }

    private fun firstImageUrl(data: JsonElement?): String {
        if (data !is JsonObject) {
            return ""
        }
        val urls = data["url_list"]?.jsonArray
        if (urls != null && urls.isNotEmpty()) {
            return urls.first().str()
        }
        return ""
    }

    private fun pickDouyinBadgeIcon(icons: JsonElement?): String? {
        if (icons !is JsonObject) {
            return null
        }
        for (key in listOf("4", "3", "2", "1", "0")) {
            val url = firstImageUrl(icons[key])
            if (url.isNotEmpty()) {
                return url
            }
        }
        for (value in icons.values) {
            val url = firstImageUrl(value)
            if (url.isNotEmpty()) {
                return url
            }
        }
        return null
    }

    private fun resolveDouyinRankScore(item: JsonObject): String {
        val exactlyScore = item["exactly_score"].str().trim()
        if (exactlyScore.isNotEmpty()) {
            return exactlyScore
        }
        val scoreDescription = item["score_description"].str().trim()
        if (scoreDescription.isNotEmpty()) {
            return scoreDescription
        }
        val score = item["score"].str().trim()
        if (score.isNotEmpty()) {
            return score
        }
        val delta = item["delta"].str().trim()
        if (delta.isNotEmpty()) {
            return delta
        }
        return "0"
    }

    // -------------------------------------------------------------------
    // 工具
    // -------------------------------------------------------------------

    /** 构造带 query 参数的完整 URL（query 值会按需编码） */
    private fun buildUrl(serverUrl: String, queryParameters: Map<String, Any?>): String {
        val builder = serverUrl.toHttpUrl().newBuilder()
        queryParameters.forEach { (k, v) -> if (v != null) builder.addQueryParameter(k, v.toString()) }
        return builder.build().toString()
    }

    /** 生成指定长度的 16 进制随机字符串 */
    private fun generateRandomString(length: Int): String {
        val random = SecureRandom()
        val sb = StringBuilder()
        repeat(length) { sb.append(random.nextInt(16).toString(16)) }
        return sb.toString()
    }

    /** 生成随机的数字 */
    private fun generateRandomNumber(length: Int): Int {
        val random = SecureRandom()
        val sb = StringBuilder()
        repeat(length) { sb.append(random.nextInt(10)) }
        return sb.toString().toIntOrNull() ?: Random.nextInt(1000000000)
    }
}