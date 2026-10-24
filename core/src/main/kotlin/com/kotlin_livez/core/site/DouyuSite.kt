package com.kotlin_livez.core.site

import com.kotlin_livez.core.common.CoreError
import com.kotlin_livez.core.common.CoreLog
import com.kotlin_livez.core.common.DouyuPassport
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

    /**
     * 本机持久化设备号（由 app 层 `DouyuAccountService` 注入 32 位小写 hex）。
     * 参与签名 `did` 与请求头 Cookie 的 `dy_did`/`acf_did`（见 [deviceDid]）：
     * 为空时退回 Cookie 的 `acf_did`，再退回进程内兜底设备号。
     */
    @JvmField
    var deviceId: String = ""

    /**
     * 取流前 Cookie 续期回调：由 app 层注入（扫码登录后用 LTP0 + dy_did 经 safeAuth 换新 Cookie）。
     * 返回新 Cookie；返回 null 或抛错时沿用现有 Cookie（对应 Dart `DouyuSite.onCookieExpired`）。
     */
    var onCookieExpired: (suspend () -> String?)? = null

    /**
     * 取流前检查 Cookie 有效期（`acf_jwt_token` 的 JWT exp，约 7 天）：过期时调用
     * [onCookieExpired] 换新，成功即写回 [cookie]。
     *
     * 续期失败**不抛给播放链路**（只记日志）：沿用旧 Cookie，由断流重试/换线路兜底，
     * 与 Dart `_ensureFreshCookie` 的宽容语义一致。
     */
    internal suspend fun ensureFreshCookie() {
        val current = cookie.trim()
        if (current.isEmpty() || !DouyuPassport.isCookieExpired(current)) return
        val renew = onCookieExpired ?: return
        try {
            val renewed = renew()?.trim().orEmpty()
            if (renewed.isNotEmpty()) {
                cookie = renewed
                CoreLog.i("[DouyuSite] 斗鱼播放 Cookie 已自动续期")
            }
        } catch (e: Exception) {
            CoreLog.w("[DouyuSite] 斗鱼 Cookie 自动续期失败：${e.message}")
        }
    }

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

        /**
         * 进程内兜底设备号（32 位小写 hex，仅初始化一次）。
         * 应用层未注入持久化 `deviceId`、Cookie 也没有 `acf_did` 时使用，
         * 保证匿名态不会退化回"所有用户共用同一个固定假设备号"（对应上游 `processFallbackDid`）。
         */
        private val processFallbackDid: String = generateHexDid(32)

        /** 生成指定长度的小写 16 进制随机串（对应 Dart generateRandomString 的字符集）。 */
        private fun generateHexDid(length: Int): String {
            val chars = "0123456789abcdef"
            val random = java.security.SecureRandom()
            return buildString(length) {
                repeat(length) { append(chars[random.nextInt(chars.length)]) }
            }
        }

        /** betard 房间信息请求头（匿名，不注入登录 Cookie —— 与 Dart `_getRoomInfo` 一致）。 */
        private fun betardHeader(roomId: String): Map<String, Any?> = mapOf(
            "referer" to "https://www.douyu.com/$roomId",
            "user-agent" to kDefaultUserAgent,
        )
    }

    /**
     * 基础请求头（对应 Dart `_requestHeaders`）：referer/UA + 设备身份 Cookie。
     * Cookie 恒携带 [deviceCookieHeader]（登录态保留用户 Cookie，匿名态自动补 `dy_did`/`acf_did`），
     * 使请求头设备号与 `ub98484234` 签名 `did` 同源。
     */
    private fun requestHeaders(): Map<String, Any?> = mapOf(
        "referer" to "https://www.douyu.com/",
        "user-agent" to kPlayUserAgent,
        "Cookie" to deviceCookieHeader,
    )

    /**
     * 搜索专用请求头：保持原搜索请求的形态（Edg/114 UA + 搜索页 referer）不变，
     * 只把原先随机生成的 `dy_did`/`acf_did` 换成设备身份 Cookie [deviceCookieHeader]
     * （PRD R1 只要求移除随机 `Cookie` 覆盖，不扩大到 UA/referer）。
     *
     * 注意：此处刻意不使用 [requestHeaders] 做合并 —— 它用小写 `"user-agent"`，
     * 而搜索头用大写 `"User-Agent"`，Kotlin Map 键大小写敏感，合并会出现两个 UA 头。
     */
    private fun searchHeaders(): Map<String, String> = mapOf(
        "User-Agent" to kSearchUserAgent,
        "referer" to "https://www.douyu.com/search/",
        "Cookie" to deviceCookieHeader,
    )

    /**
     * 房间维度基础请求头 = 基础头 + 房间 referer
     * （对应 Dart `{..._requestHeaders, 'referer': 'https://www.douyu.com/$roomId'}`）。
     */
    private fun roomRequestHeaders(roomId: String): Map<String, Any?> =
        requestHeaders() + mapOf("referer" to "https://www.douyu.com/$roomId")

    /** 从 [cookie] 读取指定键的值；键缺失、片段没有 `=` 或键值为空白时返回空串。 */
    internal fun cookieValue(key: String): String {
        if (cookie.trim().isEmpty()) return ""
        for (part in cookie.split(";")) {
            val item = part.trim()
            val separatorIndex = item.indexOf('=')
            if (separatorIndex <= 0) continue
            val currentKey = item.substring(0, separatorIndex).trim()
            if (currentKey == key) {
                return item.substring(separatorIndex + 1).trim()
            }
        }
        return ""
    }

    /**
     * 签名与 Cookie 共用的设备号，优先级：
     * Cookie 的 `acf_did` -> 注入的 [deviceId] -> 进程内兜底设备号（对应上游 `deviceDid`）。
     */
    internal val deviceDid: String
        get() {
            val cookieDid = cookieValue("acf_did")
            if (cookieDid.isNotEmpty()) return cookieDid
            val normalizedDeviceId = deviceId.trim()
            if (normalizedDeviceId.isNotEmpty()) return normalizedDeviceId
            return processFallbackDid
        }

    /**
     * 设备身份请求头 Cookie（对应上游 `deviceCookieHeader`）：
     * 保留用户 [cookie] 原文，缺失 `dy_did`/`acf_did` 时用 [deviceDid] 补齐（`"; "` 连接）。
     */
    internal val deviceCookieHeader: String
        get() {
            val did = deviceDid
            val segments = mutableListOf<String>()
            val trimmedCookie = cookie.trim().trimEnd(';').trim()
            if (trimmedCookie.isNotEmpty()) {
                segments.add(trimmedCookie)
            }
            if (cookieValue("dy_did").isEmpty()) {
                segments.add("dy_did=$did")
            }
            if (cookieValue("acf_did").isEmpty()) {
                segments.add("acf_did=$did")
            }
            return segments.joinToString("; ")
        }

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
        // 取流前先确认登录 Cookie 未过期（过期则用 LTP0/dy_did 自动续期）。
        ensureFreshCookie()
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
        // 同 playQualities：每次取流前校验/续期登录 Cookie（对应 Dart getPlayUrls 首行）。
        ensureFreshCookie()
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
                // 签名设备号与请求头 Cookie 的 dy_did/acf_did 同源（上游 1.13.2-pre 对齐）。
                val signed = DouyuSign.getSign(script, roomId, deviceDid)
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
        // 不再用随机 did 覆盖 Cookie：搜索头原样保留，Cookie 走设备身份（上游 1.13.2-pre）。
        val result = HttpClient.getJson(
            "https://www.douyu.com/japi/search/api/searchShow",
            query = mapOf("kw" to keyword, "page" to page, "pageSize" to 20),
            header = searchHeaders(),
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
        // 同上：搜索请求不再覆盖 Cookie，跟随设备身份请求头。
        val result = HttpClient.getJson(
            "https://www.douyu.com/japi/search/api/searchUser",
            query = mapOf(
                "kw" to keyword,
                "page" to page,
                "pageSize" to 20,
                "filterType" to 1,
            ),
            header = searchHeaders(),
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