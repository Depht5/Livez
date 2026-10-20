package com.kotlin_livez.core.danmaku

import com.kotlin_livez.core.common.CoreError
import com.kotlin_livez.core.common.CoreLog
import com.kotlin_livez.core.common.DouyinSign
import com.kotlin_livez.core.common.HttpClient
import com.kotlin_livez.core.common.ProtoParseException
import com.kotlin_livez.core.common.WebSocketUtil
import com.kotlin_livez.core.danmaku.proto.ChatMessage
import com.kotlin_livez.core.danmaku.proto.GiftMessage
import com.kotlin_livez.core.danmaku.proto.Image
import com.kotlin_livez.core.danmaku.proto.PushFrame
import com.kotlin_livez.core.danmaku.proto.Response
import com.kotlin_livez.core.danmaku.proto.RoomUserSeqMessage
import com.kotlin_livez.core.model.LiveMessage
import com.kotlin_livez.core.model.LiveMessageColor
import com.kotlin_livez.core.model.LiveMessageSpan
import com.kotlin_livez.core.model.LiveMessageType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.zip.GZIPInputStream

/**
 * 抖音弹幕连接参数。
 * 对应 Dart `DouyinDanmakuArgs`（lib/src/danmaku/douyin_danmaku.dart）。
 * 由 DouyinSite 在 roomDetail() 时写入 LiveRoomDetail.danmakuData。
 */
data class DouyinDanmakuArgs(
    /** 直播间 webRid（固定） */
    val webRid: String,
    /** 直播间 roomId（每次开播变化） */
    val roomId: String,
    /** 用户唯一ID，用于弹幕连接 */
    val userId: String,
    /** 弹幕连接的 Cookie */
    val cookie: String,
)

/**
 * 抖音弹幕实现。
 * 对应 Dart `DouyinDanmaku`（lib/src/danmaku/douyin_danmaku.dart）：
 * IM 上下文预取（a_bogus/msToken/webcast 签名）→ WebSocket 推送协议（PushFrame/Response）→
 * 聊天/在线人数消息分发；聊天富文本 rtfContent + 内置表情 asset 映射 + 80ms 批量 flush。
 */
class DouyinDanmaku : LiveDanmaku() {

    override var heartbeatTime: Int = 10 * 1000

    private var serverUrl = "wss://webcast3-ws-web-lq.douyin.com/webcast/im/push/v2/"
    private lateinit var danmakuArgs: DouyinDanmakuArgs
    private var webSocketUtil: WebSocketUtil? = null

    /** 待 flush 的聊天消息缓冲（对应 Dart _pendingChatMessages） */
    private val pendingChatMessages = mutableListOf<LiveMessage>()

    /** 聊天 flush 定时任务（对应 Dart _flushChatTimer） */
    private var flushChatJob: Job? = null

    /** 聊天队列访问锁（Kotlin 多线程下保护队列，Dart 单线程无此需求） */
    private val chatLock = Any()

    private var imContext: DouyinImContext? = null
    private var contextRefreshUsed = false

    /** 内部协程作用域（flush 定时、动态上下文刷新） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private companion object {
        /** 单批最多 flush 条数（对应 Dart _maxChatFlushBatch） */
        const val MAX_CHAT_FLUSH_BATCH = 50

        /** flush 间隔毫秒（对应 Dart _chatFlushInterval） */
        const val CHAT_FLUSH_INTERVAL = 80L

        /** `[表情]` 文本标记；每条聊天复用同一实例，避免高频重新编译正则。 */
        val EMOJI_REGEX = Regex("\\[[^\\[\\]]{1,16}\\]")

        /** 固定心跳帧字节：心跳与进房都只发 `PushFrame(payloadType='hb')`，只序列化一次。 */
        val HEARTBEAT_BYTES: ByteArray = PushFrame(payloadType = "hb").writeToBuffer()
    }

    override suspend fun start(arg: Any?) {
        val startTime = System.currentTimeMillis()
        danmakuArgs = arg as? DouyinDanmakuArgs ?: return
        contextRefreshUsed = false
        try {
            imContext = fetchImContext()
        } catch (e: Exception) {
            CoreLog.w("[DouyinDanmaku] 动态弹幕上下文获取失败，使用兼容地址：$e")
        }
        openWebSocket()
        CoreLog.i(
            "[DouyinDanmaku] start(${danmakuArgs.webRid}) 耗时 ${System.currentTimeMillis() - startTime}ms",
        )
    }

    /** 构建 IM fetch 查询参数（对应 Dart _buildQueryParameters）。 */
    private fun buildQueryParameters(
        cursor: String? = null,
        internalExt: String? = null,
        dynamicHeartbeat: Long? = null,
    ): Map<String, String> {
        val ts = System.currentTimeMillis()
        val query = linkedMapOf<String, String>()
        query["app_name"] = "douyin_web"
        query["version_code"] = "180800"
        query["webcast_sdk_version"] = "1.3.0"
        query["update_version_code"] = "1.3.0"
        query["compress"] = "gzip"
        query["resp_content_type"] = "protobuf"
        query["cursor"] = cursor ?: "h-1_t-${ts}_r-1_d-1_u-1"
        query["host"] = "https://live.douyin.com"
        query["aid"] = "6383"
        query["live_id"] = "1"
        query["did_rule"] = "3"
        query["debug"] = "false"
        query["maxCacheMessageNumber"] = "20"
        query["endpoint"] = "live_pc"
        query["support_wrds"] = "1"
        query["im_path"] = "/webcast/im/fetch/"
        query["user_unique_id"] = danmakuArgs.userId
        query["device_platform"] = "web"
        query["cookie_enabled"] = "true"
        query["screen_width"] = "1920"
        query["screen_height"] = "1080"
        query["browser_language"] = "zh-CN"
        query["browser_platform"] = "Win32"
        query["browser_name"] = "Mozilla"
        query["browser_version"] = DouyinSign.defaultUserAgent.replace("Mozilla/", "")
        query["browser_online"] = "true"
        query["tz_name"] = "Asia/Shanghai"
        query["identity"] = "audience"
        query["room_id"] = danmakuArgs.roomId
        query["heartbeatDuration"] = dynamicHeartbeat?.toString() ?: "0"
        if (!internalExt.isNullOrEmpty()) query["internal_ext"] = internalExt
        return query
    }

    /** 签名参数（对应 Dart _signatureParameters）。 */
    private fun signatureParameters(): Map<String, Any?> =
        DouyinSign.getDefaultSignatureParams(danmakuArgs.roomId, danmakuArgs.userId)

    /** WebSocket / IM 请求头（对应 Dart _socketHeaders）。 */
    private fun socketHeaders(): Map<String, String> = linkedMapOf(
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9,en;q=0.8",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "User-Agent" to DouyinSign.defaultUserAgent,
        "Cookie" to danmakuArgs.cookie,
        "Origin" to "https://live.douyin.com",
        "Referer" to "https://live.douyin.com/${danmakuArgs.webRid}",
    )

    /**
     * 预取 IM 上下文（对应 Dart _fetchImContext）：
     * GET /webcast/im/fetch/ → protobuf Response，取 pushServer/cursor/internalExt/heartbeatDuration。
     * 失败返回 null（上层回退兼容地址）。
     */
    private suspend fun fetchImContext(): DouyinImContext? {
        try {
            val query = buildQueryParameters().toMutableMap()
            query["signature"] = DouyinSign.getSignatureForParams(signatureParameters())
            val unsignedUri = buildUrl("https://live.douyin.com/webcast/im/fetch/", query)
            val requestUrl = DouyinSign.getAbogusUrlWithMsToken(
                unsignedUri,
                DouyinSign.defaultUserAgent,
                msToken = cookieValue("msToken"),
            )
            val bytes = HttpClient.getBytes(requestUrl, header = socketHeaders())
            if (bytes.isEmpty()) {
                CoreLog.w(
                    "[DouyinDanmaku] IM 预取失败：空响应 cookie=${danmakuArgs.cookie.trim().isNotEmpty()}",
                )
                return null
            }
            val response = decodeImResponse(bytes)
            val pushServer = response.pushServer.trim()
            val cursor = response.cursor.trim().let { c ->
                if (c.isNotEmpty()) c else response.liveCursor.trim()
            }
            if (cursor.isEmpty()) {
                CoreLog.w("[DouyinDanmaku] IM 预取缺少动态游标")
                return null
            }
            consumeResponse(response)
            val duration = response.heartbeatDuration.toInt()
            if (duration > 0) {
                heartbeatTime = duration.coerceIn(1000, 120000)
            }
            CoreLog.i(
                "[DouyinDanmaku] IM 预取成功 host=${if (pushServer.isEmpty()) "fallback" else redactHost(pushServer)} heartbeat=${heartbeatTime}ms",
            )
            return DouyinImContext(
                pushServer = if (pushServer.isEmpty()) serverUrl else pushServer,
                cursor = cursor,
                internalExt = response.internalExt,
                heartbeatDuration = duration.toLong(),
            )
        } catch (e: CoreError) {
            val reason = when {
                e.statusCode == 444 -> "HTTP 444 风控限制"
                e.statusCode > 0 -> "HTTP ${e.statusCode}"
                else -> e.message
            }
            CoreLog.w(
                "[DouyinDanmaku] IM 预取失败：$reason cookie=${danmakuArgs.cookie.trim().isNotEmpty()}",
            )
            return null
        } catch (e: ProtoParseException) {
            CoreLog.w("[DouyinDanmaku] IM 预取 protobuf 解析失败：$e")
            return null
        } catch (e: Exception) {
            CoreLog.w("[DouyinDanmaku] IM 预取响应不可用：$e")
            return null
        }
    }

    /**
     * 解码 IM fetch 响应（对应 Dart _decodeImResponse）：
     * 首字节 '{' 视为 JSON（缺 protobuf 参数/风控），gzip 魔数 1f 8b 先解压，再解析 Response。
     */
    private fun decodeImResponse(bytes: ByteArray): Response {
        var payload = bytes
        if (payload.isNotEmpty() && (payload[0].toInt() and 0xFF) == 0x7b) {
            throw ProtoParseException("响应为JSON，可能缺少protobuf参数或触发风控")
        }
        if (payload.size >= 2 && (payload[0].toInt() and 0xFF) == 0x1f && (payload[1].toInt() and 0xFF) == 0x8b) {
            payload = gzipDecode(payload)
        }
        return Response.fromBytes(payload)
    }

    /** 从 Cookie 取指定键的值（对应 Dart _cookieValue）。 */
    private fun cookieValue(name: String): String? {
        for (part in danmakuArgs.cookie.split(';')) {
            val pieces = part.trim().split('=')
            if (pieces.size >= 2 && pieces.first().trim() == name) {
                return pieces.subList(1, pieces.size).joinToString("=").trim()
            }
        }
        return null
    }

    /** 规范化推送服务器地址（补 wss:// 前缀与 /webcast/im/push/v2/ 路径）。 */
    private fun normalizePushServer(value: String): String {
        var server = value.trim()
        if (server.isEmpty()) {
            return serverUrl
        }
        if (!server.contains("://")) {
            server = "wss://$server"
        }
        val uri = URI(server)
        val path = if (uri.path.isEmpty() || uri.path == "/") {
            "/webcast/im/push/v2/"
        } else {
            uri.path
        }
        return URI("wss", uri.authority, path, uri.query, uri.fragment).toString()
    }

    /** 构建 WebSocket 连接 URL（查询参数 + webcast 签名）。 */
    private suspend fun buildSocketUrl(base: String, context: DouyinImContext?): String {
        val query = buildQueryParameters(
            cursor = context?.cursor,
            internalExt = context?.internalExt,
            dynamicHeartbeat = context?.heartbeatDuration,
        ).toMutableMap()
        query["signature"] = DouyinSign.getSignatureForParams(signatureParameters())
        val builder = base.toHttpUrl().newBuilder().query(null)
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build().toString()
    }

    /** 候选服务器地址列表（动态地址优先，随后静态兼容地址及其镜像）。 */
    private suspend fun socketUrls(context: DouyinImContext?): List<String> {
        val dynamicUrl = context?.let { buildSocketUrl(normalizePushServer(it.pushServer), it) }
        val staticUrl = buildSocketUrl(serverUrl, context)
        val urls = mutableListOf<String>()
        if (!dynamicUrl.isNullOrEmpty()) urls.add(dynamicUrl)
        urls.add(staticUrl)
        urls.add(staticUrl.replace("webcast3-ws-web-lq", "webcast5-ws-web-lf"))
        urls.add(staticUrl.replace("webcast3-ws-web-lq", "webcast5-ws-web-hl"))
        urls.add(staticUrl.replace("webcast3-ws-web-lq", "webcast3-ws-web-hl"))
        urls.add(staticUrl.replace("webcast3-ws-web-lq", "webcast3-ws-web-lf"))
        return urls.distinct()
    }

    /** 建立 WebSocket（对应 Dart _openWebSocket）。 */
    private suspend fun openWebSocket() {
        val urls = socketUrls(imContext)
        CoreLog.d(
            "[DouyinDanmaku] 连接弹幕服务器 room=${danmakuArgs.webRid} candidates=${urls.size} dynamic=${imContext != null}",
        )
        webSocketUtil?.close()
        webSocketUtil = WebSocketUtil(
            url = urls.first(),
            backupUrls = urls.drop(1),
            headers = socketHeaders(),
            heartBeatTime = heartbeatTime.toLong(),
            onByteMessage = { decodeMessage(it) },
            onReady = {
                onReady?.invoke()
                joinRoom()
            },
            onHeartBeat = { heartbeat() },
            onReconnect = { onClose?.invoke("与服务器断开连接，正在尝试重连") },
            onClose = { e ->
                CoreLog.w("[DouyinDanmaku] WebSocket 握手/连接失败：$e")
                if (!contextRefreshUsed && imContext != null) {
                    contextRefreshUsed = true
                    scope.launch { refreshContextAndReconnect(e) }
                } else {
                    onClose?.invoke("服务器连接失败（握手或传输失败）$e")
                }
            },
        )
        webSocketUtil?.connect()
    }

    /** 刷新 IM 上下文并重连（对应 Dart _refreshContextAndReconnect）。 */
    private suspend fun refreshContextAndReconnect(error: String) {
        CoreLog.w("[DouyinDanmaku] 动态推送连接失败，刷新 IM 上下文：$error")
        try {
            val context = fetchImContext()
            if (context != null) {
                webSocketUtil?.close()
                imContext = context
                openWebSocket()
                return
            }
        } catch (e: Exception) {
            CoreLog.w("[DouyinDanmaku] 刷新 IM 上下文失败：$e")
        }
        onClose?.invoke("服务器连接失败（动态上下文刷新后握手失败）$error")
    }

    /** 心跳：PushFrame(payloadType='hb')。 */
    override fun heartbeat() {
        webSocketUtil?.sendBytes(HEARTBEAT_BYTES)
    }

    /** 进房：与 Dart 一样发送一个 hb 帧。 */
    private fun joinRoom() {
        webSocketUtil?.sendBytes(HEARTBEAT_BYTES)
    }

    /** 解码 WebSocket 二进制帧：PushFrame → gzip 解压 → Response → 分发。 */
    fun decodeMessage(bytes: ByteArray) {
        val start = System.currentTimeMillis()
        try {
            val wssPackage = PushFrame.fromBytes(bytes)
            val decompressed = gzipDecode(wssPackage.payload)
            val payloadPackage = Response.fromBytes(decompressed)
            val counts = consumeResponse(payloadPackage, logId = wssPackage.logId)
            val elapsed = System.currentTimeMillis() - start
            if (elapsed >= 16 || counts.second >= 20) {
                CoreLog.i(
                    "[DouyinDanmaku] decodeMessage 耗时 ${elapsed}ms messages=${counts.first} chats=${counts.second}",
                )
            }
        } catch (e: Exception) {
            CoreLog.w("[DouyinDanmaku] decodeMessage 解析失败：$e")
        }
    }

    /**
     * 消费 Response（对应 Dart _consumeResponse）。
     * 返回 (消息总数, 聊天数)；needAck 时回 ack。
     */
    private fun consumeResponse(payloadPackage: Response, logId: Long? = null): Pair<Int, Int> {
        var messageCount = 0
        var chatCount = 0
        if (payloadPackage.needAck && logId != null) {
            sendAck(logId, payloadPackage.internalExt)
        }
        for (msg in payloadPackage.messagesList) {
            messageCount++
            when (msg.method) {
                "WebcastChatMessage" -> {
                    val liveMessage = unpackWebcastChatMessage(msg.payload)
                    if (liveMessage != null) {
                        chatCount++
                        enqueueChatMessage(liveMessage)
                    }
                }
                "WebcastRoomUserSeqMessage" -> unpackWebcastRoomUserSeqMessage(msg.payload)
                // 礼物消息当前不展示，不再为其执行完整的 GiftMessage protobuf 反序列化。
            }
        }
        return Pair(messageCount, chatCount)
    }

    /** 脱敏推流服务器地址，仅取 host。 */
    private fun redactHost(value: String): String {
        return try {
            val uri = URI(if (value.contains("://")) value else "wss://$value")
            uri.host ?: "invalid"
        } catch (_: Exception) {
            "invalid"
        }
    }

    /** 解析聊天消息 → LiveMessage（对应 Dart unPackWebcastChatMessage）。 */
    fun unpackWebcastChatMessage(payload: ByteArray): LiveMessage? {
        val chatMessage = ChatMessage.fromBytes(payload)
        val spans = extractRtfSpans(chatMessage)
        if (spans.isEmpty()) {
            appendTextWithEmojiFallback(spans, chatMessage.content)
        }
        val imageUrls = spans
            .filter { it.isImage }
            .map { it.imageUrl!!.trim() }
            .toSet()
            .toList()
        val message = buildChatMessageText(chatMessage, spans)
        return LiveMessage(
            type = LiveMessageType.chat,
            color = LiveMessageColor.white,
            // 暂不知道具体怎么转换颜色（Dart 注释保留）
            message = message,
            userName = chatMessage.nickName,
            imageUrls = if (imageUrls.isEmpty()) null else imageUrls,
            spans = if (spans.isEmpty()) null else spans,
        )
    }

    /** 入队聊天消息，按批次 flush（对应 Dart _enqueueChatMessage）。 */
    private fun enqueueChatMessage(message: LiveMessage) {
        synchronized(chatLock) {
            pendingChatMessages.add(message)
            if (pendingChatMessages.size >= MAX_CHAT_FLUSH_BATCH) {
                if (flushChatJob == null) {
                    flushChatJob = scope.launch { flushChatMessages() }
                }
                return
            }
            if (flushChatJob == null) {
                flushChatJob = scope.launch {
                    delay(CHAT_FLUSH_INTERVAL)
                    flushChatMessages()
                }
            }
        }
    }

    /** 批量 flush 聊天消息（对应 Dart _flushChatMessages，最多 50 条/批）。 */
    private fun flushChatMessages() {
        val batch: List<LiveMessage>
        synchronized(chatLock) {
            flushChatJob = null
            if (pendingChatMessages.isEmpty()) {
                return
            }
            val batchSize = minOf(pendingChatMessages.size, MAX_CHAT_FLUSH_BATCH)
            batch = pendingChatMessages.take(batchSize)
            pendingChatMessages.subList(0, batchSize).clear()
        }
        for (message in batch) {
            onMessage?.invoke(message)
        }
        val remaining: Boolean = synchronized(chatLock) { pendingChatMessages.isNotEmpty() }
        if (remaining) {
            synchronized(chatLock) {
                if (flushChatJob == null && pendingChatMessages.isNotEmpty()) {
                    flushChatJob = scope.launch {
                        delay(CHAT_FLUSH_INTERVAL)
                        flushChatMessages()
                    }
                }
            }
        }
    }

    /** 组装聊天展示文本（对应 Dart _buildChatMessageText）。 */
    private fun buildChatMessageText(chatMessage: ChatMessage, spans: List<LiveMessageSpan>): String {
        val content = chatMessage.content.trim()
        if (content.isNotEmpty()) {
            return content
        }
        if (spans.isEmpty()) {
            return chatMessage.content
        }
        val buffer = StringBuilder()
        for (span in spans) {
            if (span.isText) {
                buffer.append(span.text)
            }
        }
        return buffer.toString().trim()
    }

    /** 从 rtfContent 提取富文本 spans（对应 Dart _extractRtfSpans）。 */
    private fun extractRtfSpans(chatMessage: ChatMessage): MutableList<LiveMessageSpan> {
        val spans = mutableListOf<LiveMessageSpan>()
        if (!chatMessage.hasRtfContent) {
            return spans
        }
        for (piece in chatMessage.rtfContent!!.piecesList) {
            val imageValue = piece.imageValue
            if (imageValue != null && imageValue.image != null) {
                val imageUrl = extractImageUrl(imageValue.image!!)
                if (imageUrl != null) {
                    spans.add(LiveMessageSpan(imageUrl = imageUrl))
                    continue
                }
                val fallback = extractImageFallbackText(imageValue.image!!)
                if (fallback != null) {
                    appendTextWithEmojiFallback(spans, fallback)
                }
            }
            if (piece.stringValue.trim().isNotEmpty()) {
                appendTextWithEmojiFallback(spans, piece.stringValue)
            }
            val patternRef = piece.patternRefValue
            if (patternRef != null) {
                val pattern = patternRef.defaultPattern.trim()
                if (pattern.isNotEmpty()) {
                    appendTextWithEmojiFallback(spans, pattern)
                }
            }
        }
        return spans
    }

    /** 追加文本，并把 `[表情]` 标记替换为表情图片 span（对应 Dart _appendTextWithEmojiFallback）。 */
    private fun appendTextWithEmojiFallback(spans: MutableList<LiveMessageSpan>, input: String) {
        if (input.isEmpty()) {
            return
        }
        var start = 0
        for (match in EMOJI_REGEX.findAll(input)) {
            val token = match.value
            val asset = DouyinEmojiAssets.assetFor(token) ?: continue
            if (match.range.first > start) {
                spans.add(LiveMessageSpan(text = input.substring(start, match.range.first)))
            }
            spans.add(LiveMessageSpan(imageUrl = asset))
            start = match.range.last + 1
        }
        if (start < input.length) {
            spans.add(LiveMessageSpan(text = input.substring(start)))
        }
    }

    /** 提取图片 URL（优先 urlListList 中 http(s) 项，其次 openWebUrl/uri）。 */
    private fun extractImageUrl(image: Image): String? {
        for (url in image.urlListList) {
            val value = url.trim()
            if (value.startsWith("http://") || value.startsWith("https://")) {
                return value
            }
        }
        val openWebUrl = image.openWebUrl.trim()
        if (openWebUrl.startsWith("http://") || openWebUrl.startsWith("https://")) {
            return openWebUrl
        }
        val uri = image.uri.trim()
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            return uri
        }
        return null
    }

    /** 提取图片回退文本（alternativeText → name → [uri]）。 */
    private fun extractImageFallbackText(image: Image): String? {
        val alternativeText = image.content?.alternativeText?.trim().orEmpty()
        if (alternativeText.isNotEmpty()) {
            return alternativeText
        }
        val name = image.content?.name?.trim().orEmpty()
        if (name.isNotEmpty()) {
            return name
        }
        val uri = image.uri.trim()
        if (uri.isNotEmpty()) {
            return "[$uri]"
        }
        return null
    }

    /** 在线人数消息（对应 Dart unPackWebcastRoomUserSeqMessage）。 */
    fun unpackWebcastRoomUserSeqMessage(payload: ByteArray) {
        val roomUserSeqMessage = RoomUserSeqMessage.fromBytes(payload)
        onMessage?.invoke(
            LiveMessage(
                type = LiveMessageType.online,
                data = roomUserSeqMessage.totalUser,
                color = LiveMessageColor.white,
                message = "",
                userName = "",
            ),
        )
    }

    /** 礼物消息：当前仅记录日志、不展示（对应 Dart 端未处理礼物消息）。 */
    fun unpackWebcastGiftMessage(payload: ByteArray) {
        val giftMessage = GiftMessage.fromBytes(payload)
        CoreLog.d(
            "[DouyinDanmaku] 收到礼物消息（暂不展示）user=${giftMessage.user?.nickName ?: ""} " +
                "gift=${giftMessage.gift?.name ?: ""} giftId=${giftMessage.giftId} combo=${giftMessage.comboCount}",
        )
    }

    /** 回执 ack：PushFrame(payloadType='ack', logId, payload=internalExt)。 */
    fun sendAck(logId: Long, internalExt: String) {
        val obj = PushFrame(
            payloadType = "ack",
            logId = logId,
            payload = internalExt.toByteArray(Charsets.UTF_8),
        )
        webSocketUtil?.sendBytes(obj.writeToBuffer())
    }

    override fun stop() {
        synchronized(chatLock) {
            flushChatJob?.cancel()
            flushChatJob = null
            pendingChatMessages.clear()
        }
        onMessage = null
        onClose = null
        onReady = null
        webSocketUtil?.close()
        webSocketUtil = null
    }

    /** 构建带查询参数的 URL（okhttp 编码，与 Dart Uri.replace(queryParameters:) 对齐）。 */
    private fun buildUrl(base: String, query: Map<String, String>): String {
        val builder = base.toHttpUrl().newBuilder()
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build().toString()
    }

    /** gzip 解压（对应 Dart gzip.decode）。 */
    private fun gzipDecode(data: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
}

/** IM 上下文（对应 Dart _DouyinImContext）。 */
private data class DouyinImContext(
    val pushServer: String,
    val cursor: String,
    val internalExt: String,
    val heartbeatDuration: Long,
)
