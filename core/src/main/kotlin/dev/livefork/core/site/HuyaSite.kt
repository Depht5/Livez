package dev.livefork.core.site

import dev.livefork.core.common.CoreError
import dev.livefork.core.common.CoreLog
import dev.livefork.core.common.HttpClient
import dev.livefork.core.common.at
import dev.livefork.core.common.bool
import dev.livefork.core.common.intv
import dev.livefork.core.common.str
import dev.livefork.core.danmaku.HuyaDanmaku
import dev.livefork.core.danmaku.HuyaDanmakuArgs
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
import dev.livefork.core.model.tars.GetCdnTokenExReq
import dev.livefork.core.model.tars.GetCdnTokenExResp
import dev.livefork.core.model.tars.GetGameEventMessageBoardReq
import dev.livefork.core.model.tars.GetGameEventMessageBoardRsp
import dev.livefork.core.model.tars.HuyaUserId
import dev.livefork.core.model.tars.TarsHttpClient
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * 虎牙直播站点实现。
 * 对应 Dart `HuyaSite`（lib/src/huya_site.dart）。站点 id/name、冻结文案、请求参数保持不变；
 * 方法职责一一对应（Dart 拼写错误 getCategores/getPlayQualites/getRoomDetail 在此修正为
 * categories/playQualities/roomDetail）。Tars 请求封装见 `TarsHttpClient`。
 */
class HuyaSite : LiveSite() {

    override var id: String = "huya"
    override var name: String = "虎牙直播"

    private var playUserAgent: String? = null
    private var lastHeadlineEmptyLogAt: Long? = null

    /** 站点信息 Tars 客户端（对应 Dart tupClient，servantName=liveui）。 */
    private val tupClient = TarsHttpClient("http://wup.huya.com", "liveui", requestHeaders)

    /** 留言板 Tars 客户端（对应 Dart messageBoardClient，servantName=wupui）。 */
    private val messageBoardClient = TarsHttpClient("http://wup.huya.com", "wupui", requestHeaders)

    override fun danmaku(): LiveDanmaku = HuyaDanmaku()

    companion object {
        private const val baseUrl = "https://m.huya.com/"

        /** 业务类型名称（对应 Dart _bussTypeNames）。 */
        private val bussTypeNames: Map<Int, String> = mapOf(
            1 to "网游",
            2 to "单机",
            3 to "手游",
            8 to "娱乐",
        )

        private const val kUserAgent =
            "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.91 Mobile Safari/537.36 Edg/117.0.0.0"

        private const val HYSDK_UA =
            "HYSDK(Windows, 30000002)_APP(pc_exe&7060000&official)_SDK(trans&2.32.3.5646)"

        private val requestHeaders: Map<String, String> = mapOf(
            "Origin" to baseUrl,
            "Referer" to baseUrl,
            "User-Agent" to HYSDK_UA,
        )

        /** 分类缓存（对应 Dart 静态缓存，15 分钟有效）。 */
        private var cachedCategories: List<LiveCategory>? = null
        private var cachedCategoriesAt: Long = 0L
    }

    override suspend fun categories(): List<LiveCategory> {
        val cached = cachedCategories
        val now = System.currentTimeMillis()
        if (cached != null && now - cachedCategoriesAt < 15 * 60 * 1000) {
            return cached.map {
                LiveCategory(id = it.id, name = it.name, children = it.children.toList())
            }
        }
        var categories = listOf(
            LiveCategory(id = "1", name = "网游", children = emptyList()),
            LiveCategory(id = "2", name = "单机", children = emptyList()),
            LiveCategory(id = "8", name = "娱乐", children = emptyList()),
            LiveCategory(id = "3", name = "手游", children = emptyList()),
        )
        val subCategories = coroutineScope {
            categories.map { async { getSubCategores(it.id) } }.map { it.await() }
        }
        categories = categories.mapIndexed { i, item ->
            LiveCategory(id = item.id, name = item.name, children = subCategories[i])
        }
        cachedCategories = categories.map {
            LiveCategory(id = it.id, name = it.name, children = it.children.toList())
        }
        cachedCategoriesAt = now
        return categories
    }

    /** 读取某大类下的子分类（对应 Dart getSubCategores）。 */
    suspend fun getSubCategores(id: String): List<LiveSubCategory> {
        val result = HttpClient.getJson(
            "https://live.cdn.huya.com/liveconfig/game/bussLive",
            query = mapOf("bussType" to id),
        )
        val data = result?.jsonObject?.get("data")?.jsonArray ?: JsonArray(emptyList())
        val subs = mutableListOf<LiveSubCategory>()
        for (item in data) {
            val obj = item.jsonObject
            val gid: String
            val gidElement = obj["gid"]
            gid = when {
                gidElement is JsonObject -> gidElement["value"].str().split(",").first()
                gidElement is JsonPrimitive && !gidElement.isString -> {
                    val content = gidElement.content
                    if (content.contains('.') || content.contains('e') || content.contains('E')) {
                        content.toDoubleOrNull()?.toInt()?.toString() ?: content
                    } else {
                        content
                    }
                }
                else -> gidElement.str()
            }
            subs.add(
                LiveSubCategory(
                    id = gid,
                    name = obj["gameFullName"].str(),
                    parentId = id,
                    pic = "https://huyaimg.msstatic.com/cdnimage/game/$gid-MS.jpg",
                )
            )
        }
        return subs
    }

    override suspend fun categoryRooms(category: LiveSubCategory, page: Int): LiveCategoryResult {
        val result = HttpClient.getJson(
            "https://www.huya.com/cache.php",
            query = mapOf(
                "m" to "LiveList",
                "do" to "getLiveListByPage",
                "tagAll" to 0,
                "gameId" to category.id,
                "page" to page,
            ),
        )
        val data = result?.jsonObject?.get("data")?.jsonObject
        val items = data?.let { parseRoomItems(it) } ?: emptyList()
        val hasMore = data?.get("page").intv() < data?.get("totalPage").intv()
        return LiveCategoryResult(hasMore = hasMore, items = items)
    }

    override suspend fun recommendRooms(page: Int): LiveCategoryResult {
        val result = HttpClient.getJson(
            "https://www.huya.com/cache.php",
            query = mapOf(
                "m" to "LiveList",
                "do" to "getLiveListByPage",
                "tagAll" to 0,
                "page" to page,
            ),
        )
        val data = result?.jsonObject?.get("data")?.jsonObject
        val items = data?.let { parseRoomItems(it) } ?: emptyList()
        val hasMore = data?.get("page").intv() < data?.get("totalPage").intv()
        return LiveCategoryResult(hasMore = hasMore, items = items)
    }

    /** 解析 cache.php 直播间列表（对应 Dart getCategoryRooms/getRecommendRooms 公共逻辑）。 */
    private fun parseRoomItems(data: JsonObject): List<LiveRoomItem> {
        val items = mutableListOf<LiveRoomItem>()
        for (item in data["datas"]?.jsonArray ?: JsonArray(emptyList())) {
            val obj = item.jsonObject
            var cover = obj["screenshot"].str()
            if (!cover.contains("?")) {
                cover += "?x-oss-process=style/w338_h190&"
            }
            var title = obj["introduction"].str()
            if (title.isEmpty()) {
                title = obj["roomName"].str()
            }
            items.add(
                LiveRoomItem(
                    roomId = obj["profileRoom"].str(),
                    title = title,
                    cover = cover,
                    userName = obj["nick"].str(),
                    online = obj["totalCount"].intv(),
                )
            )
        }
        return items
    }

    override suspend fun playQualities(detail: LiveRoomDetail): List<LivePlayQuality> {
        val qualities = mutableListOf<LivePlayQuality>()
        val urlData = detail.data as? HuyaUrlDataModel
        if (urlData == null) return qualities
        if (urlData.bitRates.isEmpty()) {
            urlData.bitRates = listOf(
                HuyaBitRateModel(name = "原画", bitRate = 0),
                HuyaBitRateModel(name = "高清", bitRate = 2000),
            )
        }
        for (item in urlData.bitRates) {
            qualities.add(
                LivePlayQuality(
                    data = mapOf("urls" to urlData.lines, "bitRate" to item.bitRate),
                    quality = item.name,
                )
            )
        }
        return qualities
    }

    override suspend fun playUrls(detail: LiveRoomDetail, quality: LivePlayQuality): LivePlayUrl {
        val ls = mutableListOf<String>()
        val data = quality.data as? Map<*, *>
        val lines = data?.get("urls") as? List<*> ?: emptyList<Any>()
        val bitRate = (data?.get("bitRate") as? Number)?.toInt() ?: 0
        for (element in lines) {
            val line = element as? HuyaLineModel ?: continue
            ls.add(getPlayUrl(line, bitRate))
        }
        return LivePlayUrl(urls = ls, headers = mapOf("user-agent" to HYSDK_UA))
    }

    /** 构造单个播放地址（对应 Dart getPlayUrl）。 */
    suspend fun getPlayUrl(line: HuyaLineModel, bitRate: Int): String {
        var antiCode = getCndTokenInfoEx(line.streamName)
        antiCode = buildAntiCode(line.streamName, line.presenterUid, antiCode)
        var url = "${line.line}/${line.streamName}.flv?$antiCode&codec=264"
        if (bitRate > 0) {
            url += "&ratio=$bitRate"
        }
        return url
    }

    /**
     * 构造 anticode（对应 Dart buildAntiCode）。
     * 使用主播 uid 与当前时间生成 seqid，按平台规则旋转 uid、计算 wsSecret。
     */
    fun buildAntiCode(stream: String, presenterUid: Long, antiCode: String): String {
        val mapAnti = parseQueryParameters(antiCode)
        if (!mapAnti.containsKey("fm")) {
            return antiCode
        }

        val ctype = mapAnti["ctype"]?.firstOrNull() ?: "huya_pc_exe"
        val platformId = mapAnti["t"]?.firstOrNull()?.toIntOrNull() ?: 0
        val isWap = platformId == 103
        val clacStartTime = System.currentTimeMillis()

        CoreLog.i(
            "using $presenterUid | ctype-{$ctype} | platformId - {$platformId} | isWap - {$isWap} | $clacStartTime"
        )

        val seqId = presenterUid + clacStartTime
        val secretHash = md5("$seqId|$ctype|$platformId")

        val convertUid = rotl64(presenterUid)
        val calcUid = if (isWap) presenterUid else convertUid
        val fm = decodeQueryComponent(mapAnti["fm"]!!.first())
        val secretPrefix = String(Base64.getDecoder().decode(fm), Charsets.UTF_8).split('_').first()
        val wsTime = mapAnti["wsTime"]!!.first()
        val secretStr = "${secretPrefix}_${calcUid}_${stream}_${secretHash}_$wsTime"

        val wsSecret = md5(secretStr)

        // 与 Dart 一致：每次调用使用新的随机源（非固定种子）
        val ct = ((wsTime.toLong(16) + Random.nextDouble()) * 1000).toLong()
        val uuid = (((ct % 1e10) + Random.nextDouble()) * 1e3 % 4294967295.0).toInt().toString()
        val antiCodeRes = LinkedHashMap<String, String>()
        antiCodeRes["wsSecret"] = wsSecret
        antiCodeRes["wsTime"] = wsTime
        antiCodeRes["seqid"] = seqId.toString()
        antiCodeRes["ctype"] = ctype
        antiCodeRes["ver"] = "1"
        antiCodeRes["fs"] = mapAnti["fs"]!!.first()
        antiCodeRes["fm"] = encodeQueryComponent(mapAnti["fm"]!!.first())
        antiCodeRes["t"] = platformId.toString()
        if (isWap) {
            antiCodeRes["uid"] = presenterUid.toString()
            antiCodeRes["uuid"] = uuid
        } else {
            antiCodeRes["u"] = convertUid.toString()
        }

        return antiCodeRes.entries.joinToString("&") { (k, v) -> "$k=$v" }
    }

    /**
     * 调用 getCdnTokenInfoEx 获取 sFlvToken（对应 Dart getCndTokenInfoEx）。
     */
    suspend fun getCndTokenInfoEx(stream: String): String {
        val func = "getCdnTokenInfoEx"
        val tid = HuyaUserId().apply { sHuYaUA = "pc_exe&7060000&official" }
        val tReq = GetCdnTokenExReq().apply {
            tId = tid
            sStreamName = stream
        }
        val resp = tupClient.tupRequest(func, tReq, GetCdnTokenExResp())
        return resp.sFlvToken
    }

    override suspend fun roomDetail(roomId: String): LiveRoomDetail {
        val roomInfo = _getRoomInfo(roomId)
        val roomInfoObj = roomInfo["roomInfo"]?.jsonObject
        val tLiveInfo = roomInfoObj?.get("tLiveInfo")?.jsonObject
        val tProfileInfo = roomInfoObj?.get("tProfileInfo")?.jsonObject
        val topSid = _asPositiveInt(roomInfo["topSid"])
        val subSid = _asPositiveInt(roomInfo["subSid"])

        var title = tLiveInfo?.get("sIntroduction").str()
        if (title.isEmpty()) {
            title = tLiveInfo?.get("sRoomName").str()
        }

        val huyaLines = mutableListOf<HuyaLineModel>()
        val huyaBiterates = mutableListOf<HuyaBitRateModel>()
        val lines = tLiveInfo?.get("tLiveStreamInfo")?.jsonObject
            ?.get("vStreamInfo")?.jsonObject?.get("value")?.jsonArray
            ?: JsonArray(emptyList())
        for (item in lines) {
            val obj = item.jsonObject
            if (obj["sFlvUrl"].str().isNotEmpty()) {
                huyaLines.add(
                    HuyaLineModel(
                        line = obj["sFlvUrl"].str(),
                        lineType = HuyaLineType.flv,
                        flvAntiCode = obj["sFlvAntiCode"].str(),
                        hlsAntiCode = obj["sHlsAntiCode"].str(),
                        streamName = obj["sStreamName"].str(),
                        cdnType = obj["sCdnType"].str(),
                        presenterUid = if (topSid > 0) topSid.toLong() else subSid.toLong(),
                    )
                )
            }
        }

        val biterates = tLiveInfo?.get("tLiveStreamInfo")?.jsonObject
            ?.get("vBitRateInfo")?.jsonObject?.get("value")?.jsonArray
            ?: JsonArray(emptyList())
        for (item in biterates) {
            val obj = item.jsonObject
            val name = obj["sDisplayName"].str()
            if (name.contains("HDR")) {
                continue
            }
            huyaBiterates.add(HuyaBitRateModel(bitRate = obj["iBitRate"].intv(), name = name))
        }

        val categoryId = _resolveHuyaCategoryId(tLiveInfo)
        val categoryNameRaw = tLiveInfo?.get("sGameFullName").str().trim()
        val categoryParentId = _resolveHuyaParentCategoryId(tLiveInfo)
        val categoryParentName = _resolveHuyaParentCategoryName(tLiveInfo)

        // liveLineUrl 为 base64 编码的播放基础地址，解码后加 https 前缀
        val liveLineUrl = roomInfo["roomProfile"]?.jsonObject?.get("liveLineUrl").str()
        val liveLineDecoded = runCatching {
            String(Base64.getDecoder().decode(liveLineUrl), Charsets.UTF_8)
        }.getOrDefault("")

        return LiveRoomDetail(
            cover = tLiveInfo?.get("sScreenshot").str(),
            online = tLiveInfo?.get("lTotalCount").intv(),
            roomId = tLiveInfo?.get("lProfileRoom").str(),
            title = title,
            userName = tProfileInfo?.get("sNick").str(),
            userAvatar = tProfileInfo?.get("sAvatar180").str(),
            introduction = tLiveInfo?.get("sIntroduction").str(),
            notice = roomInfo["welcomeText"].str(),
            status = roomInfoObj?.get("eLiveStatus").intv() == 2,
            data = HuyaUrlDataModel(
                url = "https:$liveLineDecoded",
                lines = huyaLines,
                bitRates = huyaBiterates,
                uid = getUid(t = 13, e = 10),
            ),
            danmakuData = HuyaDanmakuArgs(
                ayyuid = tLiveInfo?.get("lYyid").intv().toLong(),
                topSid = topSid.toLong(),
                subSid = subSid.toLong(),
            ),
            url = "https://www.huya.com/$roomId",
            categoryId = categoryId,
            categoryName = if (categoryNameRaw.isEmpty()) null else categoryNameRaw,
            categoryParentId = categoryParentId,
            categoryParentName = categoryParentName,
            categoryPic = _resolveHuyaCategoryPic(categoryId),
        )
    }

    /**
     * 抓取房间信息（对应 Dart _getRoomInfo）。
     * 从移动端页面 HTML 中提取 HNF_GLOBAL_INIT JSON，并回填 topSid/subSid。
     */
    private suspend fun _getRoomInfo(roomId: String): JsonObject {
        val resultText = HttpClient.getText(
            "https://m.huya.com/$roomId",
            query = emptyMap(),
            header = mapOf("user-agent" to kUserAgent),
        )
        val match = Regex("""window\.HNF_GLOBAL_INIT.\=\{[\s\S]*?\}[\s\S]*?</script>""").find(resultText)
        val text = match?.value ?: throw CoreError("虎牙房间信息解析失败")
        val jsonText = text
            .replace(Regex("""window\.HNF_GLOBAL_INIT.\="""), "")
            .replace("</script>", "")
            .replace(Regex("""function.*?\(.*?\)\.\{[\s\S]*?\}""")) { "\"\"" }
        val jsonObj = HttpClient.json.parseToJsonElement(jsonText).jsonObject
        var topSid = Regex("""lChannelId":([0-9]+)""").find(resultText)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        var subSid = Regex("""lSubChannelId":([0-9]+)""").find(resultText)
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        topSid = if (topSid > 0) topSid else _firstPositiveIntByKeys(jsonObj, setOf("lchannelid", "channelid"))
        subSid = if (subSid > 0) subSid else _firstPositiveIntByKeys(jsonObj, setOf("lsubchannelid", "subchannelid"))
        val mutable = LinkedHashMap(jsonObj)
        mutable["topSid"] = JsonPrimitive(topSid)
        mutable["subSid"] = JsonPrimitive(subSid)
        return JsonObject(mutable)
    }

    override suspend fun searchRooms(keyword: String, page: Int): LiveSearchRoomResult {
        val result = HttpClient.getJson(
            "https://search.cdn.huya.com/",
            query = mapOf(
                "m" to "Search",
                "do" to "getSearchContent",
                "q" to keyword,
                "uid" to 0,
                "v" to 4,
                "typ" to -5,
                "livestate" to 0,
                "rows" to 20,
                "start" to (page - 1) * 20,
            ),
        )
        val resp = result?.jsonObject?.get("response")?.jsonObject
        val docs = resp?.get("3")?.jsonObject?.get("docs")?.jsonArray ?: JsonArray(emptyList())
        val items = docs.mapNotNull { item ->
            val obj = item.jsonObject
            var cover = obj["game_screenshot"].str()
            if (!cover.contains("?")) {
                cover += "?x-oss-process=style/w338_h190&"
            }
            var title = obj["game_introduction"].str()
            if (title.isEmpty()) {
                title = obj["game_roomName"].str()
            }
            LiveRoomItem(
                roomId = obj["room_id"].str(),
                title = title,
                cover = cover,
                userName = obj["game_nick"].str(),
                online = obj["game_total_count"].intv(),
            )
        }
        val hasMore = resp?.get("3")?.jsonObject?.get("numFound").intv() > page * 20
        return LiveSearchRoomResult(hasMore = hasMore, items = items)
    }

    override suspend fun searchAnchors(keyword: String, page: Int): LiveSearchAnchorResult {
        val result = HttpClient.getJson(
            "https://search.cdn.huya.com/",
            query = mapOf(
                "m" to "Search",
                "do" to "getSearchContent",
                "q" to keyword,
                "uid" to 0,
                "v" to 1,
                "typ" to -5,
                "livestate" to 0,
                "rows" to 20,
                "start" to (page - 1) * 20,
            ),
        )
        val resp = result?.jsonObject?.get("response")?.jsonObject
        val docs = resp?.get("1")?.jsonObject?.get("docs")?.jsonArray ?: JsonArray(emptyList())
        val items = docs.map { item ->
            val obj = item.jsonObject
            LiveAnchorItem(
                roomId = obj["room_id"].str(),
                avatar = obj["game_avatarUrl180"].str(),
                userName = obj["game_nick"].str(),
                liveStatus = obj["gameLiveOn"].bool(),
            )
        }
        val hasMore = resp?.get("1")?.jsonObject?.get("numFound").intv() > page * 20
        return LiveSearchAnchorResult(hasMore = hasMore, items = items)
    }

    override suspend fun liveStatus(roomId: String): Boolean {
        val roomInfo = _getRoomInfo(roomId)
        return roomInfo["roomInfo"]?.jsonObject?.get("eLiveStatus").intv() == 2
    }

    /** 匿名登录获取 uid（对应 Dart getAnonymousUid）。 */
    suspend fun getAnonymousUid(): String {
        val result = HttpClient.postJson(
            "https://udblgn.huya.com/web/anonymousLogin",
            data = """{"appId":5002,"byPass":3,"context":"","version":"2.4","data":{}}""",
            header = mapOf("user-agent" to kUserAgent),
        )
        return result?.at("data.uid").str()
    }

    /** 每次访问播放地址需要获取一次 UA（对应 Dart getHuYaUA，当前已弃用）。 */
    suspend fun getHuYaUA(): String {
        playUserAgent?.let { return it }
        try {
            val result = HttpClient.getJson(
                "https://github.iill.moe/xiaoyaocz/dart_simple_live/master/assets/play_config.json",
                query = mapOf("ts" to System.currentTimeMillis()),
            )
            playUserAgent = result?.jsonObject?.get("huya")?.jsonObject?.get("user_agent").str()
        } catch (e: Exception) {
            CoreLog.error(e)
        }
        return playUserAgent ?: HYSDK_UA
    }

    override suspend fun superChatMessage(
        roomId: String,
        detail: LiveRoomDetail?,
    ): List<LiveSuperChatMessage> {
        val pidCandidates = LinkedHashSet<Int>()
        val danmakuArgs = detail?.danmakuData as? HuyaDanmakuArgs
        if (danmakuArgs != null) {
            if (danmakuArgs.topSid > 0) {
                pidCandidates.add(danmakuArgs.topSid.toInt())
            }
            if (danmakuArgs.subSid > 0) {
                pidCandidates.add(danmakuArgs.subSid.toInt())
            }
        }
        val urlData = detail?.data as? HuyaUrlDataModel
        if (urlData != null) {
            for (line in urlData.lines) {
                if (line.presenterUid > 0) {
                    pidCandidates.add(line.presenterUid.toInt())
                }
            }
        }

        val profileRoomId = roomId.toIntOrNull() ?: 0
        if (profileRoomId > 0) {
            pidCandidates.add(profileRoomId)
        }

        if (pidCandidates.isEmpty() ||
            (pidCandidates.size == 1 && pidCandidates.contains(profileRoomId))
        ) {
            val roomInfo = _getRoomInfo(roomId)
            val topSid = _asPositiveInt(roomInfo["topSid"])
            val subSid = _asPositiveInt(roomInfo["subSid"])
            if (topSid > 0) {
                pidCandidates.add(topSid)
            }
            if (subSid > 0) {
                pidCandidates.add(subSid)
            }
        }

        if (pidCandidates.isEmpty()) {
            _logHeadlineEmpty(roomId, pidCandidates, "no pid candidate")
            return emptyList()
        }

        for (pid in pidCandidates) {
            if (pid <= 0) {
                continue
            }
            try {
                val messages = _fetchHeadLineMessages(pid.toLong())
                if (messages.isNotEmpty()) {
                    return messages
                }
            } catch (e: Exception) {
                CoreLog.error("Huya headline fetch failed for pid=$pid: $e")
            }
        }
        _logHeadlineEmpty(roomId, pidCandidates, "empty response")
        return emptyList()
    }

    /** 留言板空结果日志防刷（对应 Dart _logHeadlineEmpty）。 */
    private fun _logHeadlineEmpty(roomId: String, pidCandidates: Set<Int>, reason: String) {
        val now = System.currentTimeMillis()
        val last = lastHeadlineEmptyLogAt
        if (last != null && now - last < 60 * 1000) {
            return
        }
        lastHeadlineEmptyLogAt = now
        CoreLog.d(
            "Huya headline $reason, roomId=$roomId, pidCandidates=${pidCandidates.joinToString(",")}"
        )
    }

    override suspend fun contributionRank(
        roomId: String,
        detail: LiveRoomDetail?,
    ): List<LiveContributionRankItem> = emptyList()

    /** 获取留言板超长留言（对应 Dart _fetchHeadLineMessages）。 */
    private suspend fun _fetchHeadLineMessages(pid: Long): List<LiveSuperChatMessage> {
        val userId = HuyaUserId().apply { sHuYaUA = HYSDK_UA }
        val req = GetGameEventMessageBoardReq().apply {
            lPid = pid
            tId = userId
            iMessageBoardScope = 0
            iPageSize = 10
        }
        val rsp = messageBoardClient.tupRequest("getHeadLineMessageBoard", req, GetGameEventMessageBoardRsp())

        val now = Instant.now()
        return rsp.tMessageBoardPanel.vGameEventMessageBoardInfo.mapNotNull { item ->
            val content = item.sContent.trim()
            if (content.isEmpty()) {
                return@mapNotNull null
            }

            val remainingSeconds = if (item.iCountDown > 0) item.iCountDown else item.iTotalSec
            if (remainingSeconds <= 0) {
                return@mapNotNull null
            }

            val totalSeconds = if (item.iTotalSec > 0) item.iTotalSec else remainingSeconds
            val price = when {
                item.iCost > 0 -> item.iCost.toInt()
                item.iCostPay > 0 -> max(1, (item.iCostPay / 100.0).roundToInt())
                else -> 0
            }
            val endTime = now.plusSeconds(max(1L, remainingSeconds))
            val startTime = endTime.minusSeconds(max(1L, totalSeconds))
            LiveSuperChatMessage(
                id = if (item.lMessageId > 0) item.lMessageId.toString() else null,
                backgroundBottomColor = "#F97316",
                backgroundColor = "#FED7AA",
                endTime = endTime,
                face = item.tMessageUser.sAvatar,
                message = content,
                price = price,
                startTime = startTime,
                userName = item.tMessageUser.sNick.trim(),
            )
        }
    }

    // -------------------------------------------------------------------------
    // 内部工具方法（对应 Dart 同名私有方法）
    // -------------------------------------------------------------------------

    /** 解析直播间分类 ID（对应 Dart _resolveHuyaCategoryId）。 */
    private fun _resolveHuyaCategoryId(liveInfo: JsonObject?): String? {
        val gid = liveInfo?.get("iGid").intv()
        if (gid > 0) {
            return gid.toString()
        }
        val gameId = liveInfo?.get("iGameId").intv()
        if (gameId > 0) {
            return gameId.toString()
        }
        return null
    }

    /** 构造分类图标（对应 Dart _resolveHuyaCategoryPic）。 */
    private fun _resolveHuyaCategoryPic(categoryId: String?): String? {
        if (categoryId == null || categoryId.isEmpty()) {
            return null
        }
        return "https://huyaimg.msstatic.com/cdnimage/game/$categoryId-MS.jpg"
    }

    /** 解析父分类 ID（对应 Dart _resolveHuyaParentCategoryId）。 */
    private fun _resolveHuyaParentCategoryId(liveInfo: JsonObject?): String? {
        val bussType = liveInfo?.get("iBussType").intv()
        if (bussType <= 0) {
            return null
        }
        return bussType.toString()
    }

    /** 解析父分类名称（对应 Dart _resolveHuyaParentCategoryName）。 */
    private fun _resolveHuyaParentCategoryName(liveInfo: JsonObject?): String? {
        val bussType = liveInfo?.get("iBussType").intv()
        // intv() 缺失时为 0；bussTypeNames 无 0 键，map[0] 返回 null（等价 Dart null）
        return bussTypeNames[bussType]
    }

    /** 取正整数，否则 0（对应 Dart _asPositiveInt）。 */
    private fun _asPositiveInt(value: Any?): Int {
        if (value is Int) {
            return if (value > 0) value else 0
        }
        val parsed = value?.toString()?.trim()?.toIntOrNull()
        return if (parsed != null && parsed > 0) parsed else 0
    }

    /** 递归查找指定键（忽略大小写）的正整数值（对应 Dart _firstPositiveIntByKeys）。 */
    private fun _firstPositiveIntByKeys(
        source: JsonElement?,
        keys: Set<String>,
        depth: Int = 0,
    ): Int {
        if (source == null || source is JsonNull || depth > 8) {
            return 0
        }
        if (source is JsonObject) {
            for ((k, v) in source) {
                val key = k.lowercase()
                if (key in keys) {
                    val value = _asPositiveInt(v)
                    if (value > 0) {
                        return value
                    }
                }
            }
            for (v in source.values) {
                val result = _firstPositiveIntByKeys(v, keys, depth + 1)
                if (result > 0) {
                    return result
                }
            }
        } else if (source is JsonArray) {
            for (item in source) {
                val result = _firstPositiveIntByKeys(item, keys, depth + 1)
                if (result > 0) {
                    return result
                }
            }
        }
        return 0
    }

    /**
     * 旋转 uid 低位 8 位（对应 Dart rotl64）。
     * 低 32 位循环左移 8 位，高 32 位不变。
     */
    fun rotl64(t: Long): Long {
        val low = t and 0xFFFFFFFFL
        val rotatedLow = ((low shl 8) or (low ushr 24)) and 0xFFFFFFFFL
        val high = t and -0x100000000L
        return high or rotatedLow
    }

    /** 随机 UUID（对应 Dart getUUid）。 */
    fun getUUid(): String {
        val currentTime = System.currentTimeMillis()
        val randomValue = Random.nextLong(4294967295L)
        val result = (currentTime % 10000000000L * 1000 + randomValue) % 4294967295L
        return result.toString()
    }

    /**
     * 生成随机 uid（对应 Dart getUid）。
     * t 非空时生成 t 位随机串（取 e 进制）；否则生成 36 位 UUID 风格串。
     */
    fun getUid(t: Int?, e: Int?): String {
        val n = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        val o = arrayOfNulls<String>(36)
        // 与 Dart 一致：每次调用使用新的随机源（非固定种子）
        if (t != null) {
            for (i in 0 until t) {
                o[i] = n[Random.nextInt(e ?: n.length)].toString()
            }
        } else {
            o[8] = "-"
            o[13] = "-"
            o[18] = "-"
            o[23] = "-"
            o[14] = "4"
            for (i in 0 until 36) {
                if (o[i].isNullOrEmpty()) {
                    val r = Random.nextInt(16)
                    o[i] = n[if (19 == i) 3 and r or 8 else r].toString()
                }
            }
        }
        return o.joinToString("") { it ?: "" }
    }

    /** MD5 十六进制（对应 Dart crypto md5）。 */
    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * 解析 query 参数（对应 Dart Uri(query:).queryParametersAll）。
     * 键值均按 URI 组件解码，保留重复键。
     */
    private fun parseQueryParameters(query: String): Map<String, List<String>> {
        val map = LinkedHashMap<String, MutableList<String>>()
        if (query.isEmpty()) return map
        for (pair in query.split("&")) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val k = if (idx >= 0) pair.substring(0, idx) else pair
            val v = if (idx >= 0) pair.substring(idx + 1) else ""
            map.getOrPut(decodeQueryComponent(k)) { mutableListOf() }.add(decodeQueryComponent(v))
        }
        return map
    }

    /**
     * 解码 URI query 组件（对应 Dart Uri.decodeQueryComponent）。
     * 连续的 %XX 字节序列按 UTF-8 解码，'+' 保留为字面字符。
     */
    private fun decodeQueryComponent(input: String): String {
        if (!input.contains('%')) return input
        val sb = StringBuilder()
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '%' && i + 2 < input.length) {
                val b = input.substring(i + 1, i + 3).toIntOrNull(16)
                if (b != null) {
                    bytes.write(b)
                    i += 3
                    continue
                }
            }
            if (bytes.size() > 0) {
                sb.append(String(bytes.toByteArray(), Charsets.UTF_8))
                bytes.reset()
            }
            sb.append(c)
            i++
        }
        if (bytes.size() > 0) {
            sb.append(String(bytes.toByteArray(), Charsets.UTF_8))
        }
        return sb.toString()
    }

    /**
     * 编码 URI 组件（对应 Dart Uri.encodeComponent）。
     * 保留 -_.!~*'() 与字母数字，其余按 UTF-8 %XX 大写十六进制编码。
     */
    private fun encodeQueryComponent(s: String): String {
        val sb = StringBuilder()
        for (byte in s.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            val unreserved = (c in 48..57) || (c in 65..90) || (c in 97..122) ||
                c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code ||
                c == '!'.code || c == '*'.code || c == '\''.code ||
                c == '('.code || c == ')'.code
            if (unreserved) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append(String.format("%02X", c))
            }
        }
        return sb.toString()
    }
}

/**
 * 虎牙播放地址数据模型。
 * 对应 Dart `HuyaUrlDataModel`（lib/src/huya_site.dart）。
 */
class HuyaUrlDataModel(
    val url: String,
    val uid: String,
    var lines: List<HuyaLineModel>,
    var bitRates: List<HuyaBitRateModel>,
)

/** 传流线路类型。对应 Dart `HuyaLineType`。 */
enum class HuyaLineType { flv, hls }

/** 虎牙播放线路模型。对应 Dart `HuyaLineModel`。 */
class HuyaLineModel(
    val line: String,
    val cdnType: String,
    val flvAntiCode: String,
    val hlsAntiCode: String,
    val streamName: String,
    val lineType: HuyaLineType,
    var bitRate: Int = 0,
    /** 主播 uid（topSid = subSid = presenterUid） */
    val presenterUid: Long,
)

/** 虎牙清晰度模型。对应 Dart `HuyaBitRateModel`。 */
class HuyaBitRateModel(
    val name: String,
    val bitRate: Int,
)