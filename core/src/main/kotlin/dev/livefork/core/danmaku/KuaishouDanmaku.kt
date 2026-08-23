package dev.livefork.core.danmaku

import dev.livefork.core.common.CoreLog
import dev.livefork.core.common.WebSocketUtil
import dev.livefork.core.model.LiveMessage
import dev.livefork.core.model.LiveMessageColor
import dev.livefork.core.model.LiveMessageType
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/** protobuf 数据格式异常（对应 Dart FormatException 语义）。 */
private class FormatException(message: String) : Exception(message)

/**
 * 快手弹幕连接参数。
 * 对应 Dart `KuaishouDanmakuArgs`（lib/src/danmaku/kuaishou_danmaku.dart）。
 * 由 KuaishouSite 在 roomDetail() 时写入 LiveRoomDetail.danmakuData。
 */
data class KuaishouDanmakuArgs(
    /** 房间ID */
    val roomId: String,
    /** 推流 ID（弹幕进房必需） */
    val liveStreamId: String,
    /** 弹幕凭证 token */
    val token: String,
    /** WebSocket 候选地址 */
    val websocketUrls: List<String>,
    /** 页面 ID（随机生成） */
    val pageId: String,
    /** 推广标签 */
    val expTag: String = "",
    /** 附加标签 */
    val attach: String = "",
    /** 弹幕连接 Cookie */
    val cookie: String = "",
    /** 用户代理 */
    val userAgent: String = DEFAULT_USER_AGENT,
) {
    companion object {
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}

/**
 * 快手弹幕实现。
 * 对应 Dart `KuaishouDanmaku`：WebSocket 二进制帧为 protobuf 编码的 SocketMessage
 * （field1=payloadType、field2=compressionType、field3=payload），
 * 进房（type=200）、心跳（type=1）、文本帧（type=310）、错误帧（type=103）。
 * 压缩类型 2=gzip；3=AES 暂不支持。
 */
class KuaishouDanmaku : LiveDanmaku() {

    override var heartbeatTime: Int = 20 * 1000

    private var webSocketUtil: WebSocketUtil? = null
    private var danmakuArgs: KuaishouDanmakuArgs? = null

    override suspend fun start(args: Any?) {
        val danmakuArgs = args as? KuaishouDanmakuArgs
        if (danmakuArgs == null ||
            danmakuArgs.liveStreamId.isEmpty() ||
            danmakuArgs.token.isEmpty() ||
            danmakuArgs.websocketUrls.isEmpty()
        ) {
            onClose?.invoke("快手弹幕凭证无效，请在账号设置中重新登录并完成验证")
            return
        }
        this.danmakuArgs = danmakuArgs
        webSocketUtil = WebSocketUtil(
            url = danmakuArgs.websocketUrls.first(),
            backupUrls = danmakuArgs.websocketUrls.drop(1),
            heartBeatTime = heartbeatTime.toLong(),
            headers = buildMap {
                put("User-Agent", danmakuArgs.userAgent)
                put("Origin", "https://live.kuaishou.com")
                put("Referer", "https://live.kuaishou.com/u/${danmakuArgs.roomId}")
                if (danmakuArgs.cookie.isNotEmpty()) {
                    put("Cookie", danmakuArgs.cookie)
                }
            },
            onByteMessage = { decodeMessage(it) },
            onReady = {
                onReady?.invoke()
                joinRoom()
            },
            onHeartBeat = { heartbeat() },
            onReconnect = { onClose?.invoke("与服务器断开连接，正在尝试重连") },
            onClose = { onClose?.invoke("服务器连接失败$it") },
        )
        webSocketUtil?.connect()
    }

    override suspend fun stop() {
        onMessage = null
        onClose = null
        onReady = null
        webSocketUtil?.close()
        webSocketUtil = null
        danmakuArgs = null
    }

    /** 进房：发送 type=200 的 SocketMessage，payload 为 JoinRoom protobuf。 */
    private fun joinRoom() {
        val args = danmakuArgs ?: return
        val payload = KuaishouProtoWriter()
            .writeString(1, args.token)
            .writeString(2, args.liveStreamId)
            .writeVarintField(3, 0L)
            .writeVarintField(4, 0L)
            .writeString(5, args.expTag)
            .writeString(6, args.attach)
            .writeString(7, args.pageId)
        webSocketUtil?.sendBytes(encodeSocketMessage(200, payload.takeBytes()))
    }

    override fun heartbeat() {
        val payload = KuaishouProtoWriter()
            .writeVarintField(1, System.currentTimeMillis())
        webSocketUtil?.sendBytes(encodeSocketMessage(1, payload.takeBytes()))
    }

    /** 解析收到的二进制帧（WebSocket onByteMessage）。 */
    private fun decodeMessage(data: ByteArray) {
        try {
            if (data.isEmpty()) {
                return
            }
            val socketMessage = decodeSocketMessage(data)
            var payload = socketMessage.payload
            if (payload.isEmpty()) {
                return
            }
            if (socketMessage.compressionType == 2) {
                payload = gzipDecode(payload)
            } else if (socketMessage.compressionType == 3) {
                CoreLog.i("[KuaishouDanmaku] 暂不支持 AES 压缩弹幕包")
                return
            }

            when (socketMessage.payloadType) {
                103 -> {
                    val error = decodeError(payload)
                    if (error.isNotEmpty()) {
                        onClose?.invoke(error)
                    }
                }
                310 -> decodeFeedPush(payload)
            }
        } catch (e: Exception) {
            CoreLog.error(e)
        }
    }

    /** 编码一帧 SocketMessage：field1=payloadType、field3=payload。 */
    private fun encodeSocketMessage(payloadType: Int, payload: ByteArray): ByteArray =
        KuaishouProtoWriter()
            .writeVarintField(1, payloadType.toLong())
            .writeBytes(3, payload)
            .takeBytes()

    /** 解码一帧 SocketMessage，提取 payloadType / compressionType / payload。 */
    private fun decodeSocketMessage(data: ByteArray): KuaishouSocketMessage {
        val reader = KuaishouProtoReader(data)
        var payloadType = 0
        var compressionType = 0
        var payload = ByteArray(0)
        reader.readFields { fieldNumber, wireType ->
            when {
                fieldNumber == 1 && wireType == 0 -> payloadType = reader.readVarint().toInt()
                fieldNumber == 2 && wireType == 0 -> compressionType = reader.readVarint().toInt()
                fieldNumber == 3 && wireType == 2 -> payload = reader.readBytes()
                else -> reader.skip(wireType)
            }
        }
        return KuaishouSocketMessage(
            payloadType = payloadType,
            compressionType = compressionType,
            payload = payload,
        )
    }

    /** 解码错误帧（type=103）。 */
    private fun decodeError(payload: ByteArray): String {
        val reader = KuaishouProtoReader(payload)
        var code = 0
        var message = ""
        reader.readFields { fieldNumber, wireType ->
            when {
                fieldNumber == 1 && wireType == 0 -> code = reader.readVarint().toInt()
                fieldNumber == 2 && wireType == 2 -> message = reader.readString()
                else -> reader.skip(wireType)
            }
        }
        if (message.isEmpty() && code == 0) {
            return ""
        }
        return if (message.isEmpty()) "快手弹幕错误：$code" else "快手弹幕错误：$message"
    }

    /** 解码推送帧（type=310），field5 为评论内容。 */
    private fun decodeFeedPush(payload: ByteArray) {
        val reader = KuaishouProtoReader(payload)
        reader.readFields { fieldNumber, wireType ->
            if (fieldNumber == 5 && wireType == 2) {
                val message = decodeCommentFeed(reader.readBytes())
                if (message != null) {
                    onMessage?.invoke(message)
                }
            } else {
                reader.skip(wireType)
            }
        }
    }

    /**
     * 解码单条评论 feed：
     * field2=用户信息、field3=内容、field6=颜色、field7=隐藏标记（值为 2 时隐藏）。
     */
    private fun decodeCommentFeed(payload: ByteArray): LiveMessage? {
        val reader = KuaishouProtoReader(payload)
        var userName = ""
        var content = ""
        var color = LiveMessageColor.white
        var hidden = false
        reader.readFields { fieldNumber, wireType ->
            when {
                fieldNumber == 2 && wireType == 2 -> userName = decodeSimpleUserInfo(reader.readBytes())
                fieldNumber == 3 && wireType == 2 -> content = reader.readString()
                fieldNumber == 6 && wireType == 2 -> color = parseColor(reader.readString())
                fieldNumber == 7 && wireType == 0 -> hidden = reader.readVarint() == 2L
                else -> reader.skip(wireType)
            }
        }

        if (hidden || content.isEmpty()) {
            return null
        }

        return LiveMessage(
            type = LiveMessageType.chat,
            userName = userName,
            message = content,
            color = color,
        )
    }

    /** 解码简单用户信息，field2 为用户名。 */
    private fun decodeSimpleUserInfo(payload: ByteArray): String {
        val reader = KuaishouProtoReader(payload)
        var userName = ""
        reader.readFields { fieldNumber, wireType ->
            if (fieldNumber == 2 && wireType == 2) {
                userName = reader.readString()
            } else {
                reader.skip(wireType)
            }
        }
        return userName
    }

    /** 解析颜色字符串（"#rrggbb"），非法时回退白色。 */
    private fun parseColor(value: String): LiveMessageColor {
        val colorText = value.trim().removePrefix("#")
        if (colorText.length == 6) {
            val colorValue = colorText.toIntOrNull(16)
            if (colorValue != null) {
                return LiveMessageColor.numberToColor(colorValue)
            }
        }
        return LiveMessageColor.white
    }

    private fun gzipDecode(payload: ByteArray): ByteArray =
        GZIPInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
}

/** 一帧已解码的 SocketMessage。 */
private data class KuaishouSocketMessage(
    val payloadType: Int,
    val compressionType: Int,
    val payload: ByteArray,
)

/**
 * protobuf 写入器（对应 Dart `_KuaishouProtoWriter`）。
 * 仅实现站点用到的 varint 字段与 length-delimited 字段；
 * 各写方法返回 this 以便链式调用（对应 Dart cascade 语法）。
 */
private class KuaishouProtoWriter {
    private val builder = java.io.ByteArrayOutputStream()

    fun writeVarintField(fieldNumber: Int, value: Long): KuaishouProtoWriter {
        writeVarint((fieldNumber.toLong() shl 3) or 0L)
        writeVarint(value)
        return this
    }

    /** 空字符串跳过不写（对应 Dart `writeString`）。 */
    fun writeString(fieldNumber: Int, value: String): KuaishouProtoWriter {
        if (value.isEmpty()) {
            return this
        }
        writeBytes(fieldNumber, value.toByteArray(Charsets.UTF_8))
        return this
    }

    fun writeBytes(fieldNumber: Int, value: ByteArray): KuaishouProtoWriter {
        writeVarint((fieldNumber.toLong() shl 3) or 2L)
        writeVarint(value.size.toLong())
        builder.write(value)
        return this
    }

    private fun writeVarint(value: Long) {
        var current = value
        while (current >= 0x80) {
            builder.write(((current and 0x7f) or 0x80).toInt())
            current = current ushr 7
        }
        builder.write(current.toInt())
    }

    fun takeBytes(): ByteArray = builder.toByteArray()
}

/**
 * protobuf 读取器（对应 Dart `_KuaishouProtoReader`）。
 * readFields 逐字段回调；未知字段按 wireType 跳过。
 */
private class KuaishouProtoReader(private val data: ByteArray) {
    private var offset = 0

    fun readFields(onField: (fieldNumber: Int, wireType: Int) -> Unit) {
        while (offset < data.size) {
            val tag = readVarint()
            if (tag == 0L) {
                return
            }
            onField((tag shr 3).toInt(), (tag and 0x07).toInt())
        }
    }

    fun readVarint(): Long {
        var shift = 0
        var result = 0L
        while (offset < data.size) {
            val byte = data[offset++].toInt() and 0xff
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) {
                return result
            }
            shift += 7
            if (shift > 63) {
                throw FormatException("Invalid protobuf varint")
            }
        }
        throw FormatException("Unexpected protobuf EOF")
    }

    fun readBytes(): ByteArray {
        val length = readVarint().toInt()
        if (length < 0) {
            throw FormatException("Unexpected protobuf bytes length")
        }
        val end = offset + length
        if (end > data.size) {
            throw FormatException("Unexpected protobuf bytes EOF")
        }
        val result = data.copyOfRange(offset, end)
        offset = end
        return result
    }

    fun readString(): String = String(readBytes(), Charsets.UTF_8)

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> offset += 8
            2 -> {
                val length = readVarint().toInt()
                if (length < 0) {
                    throw FormatException("Unexpected protobuf skip length")
                }
                offset += length
            }
            5 -> offset += 4
            else -> throw FormatException("Unsupported protobuf wire type: $wireType")
        }
        if (offset > data.size) {
            throw FormatException("Unexpected protobuf skip EOF")
        }
    }
}