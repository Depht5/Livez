package com.kotlin_livez.core.danmaku

import com.kotlin_livez.core.common.CoreLog
import com.kotlin_livez.core.common.at
import com.kotlin_livez.core.common.bool
import com.kotlin_livez.core.common.intv
import com.kotlin_livez.core.common.str
import com.kotlin_livez.core.common.WebSocketUtil
import com.kotlin_livez.core.model.LiveMessage
import com.kotlin_livez.core.model.LiveMessageColor
import com.kotlin_livez.core.model.LiveMessageSpan
import com.kotlin_livez.core.model.LiveMessageType
import com.kotlin_livez.core.model.LiveSuperChatMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.brotli.dec.BrotliInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.Inflater

/**
 * B站弹幕连接参数。
 * 对应 Dart `BiliBiliDanmakuArgs`（lib/src/danmaku/bilibili_danmaku.dart）。
 */
data class BiliBiliDanmakuArgs(
    val roomId: Int,
    val token: String,
    val serverHost: String,
    val buvid: String,
    val uid: Long,
    val cookie: String,
)

/**
 * B站弹幕实现。
 * 对应 Dart `BiliBiliDanmaku`：二进制帧协议、操作码、zlib（protover=2）/brotli（protover=3）解压、
 * 心跳、进房、弹幕/SC/在线人数解析、表情 span 提取。
 */
class BiliBiliDanmaku : LiveDanmaku() {

    override var heartbeatTime: Int = 60 * 1000

    private var webSocketUtil: WebSocketUtil? = null
    private lateinit var args: BiliBiliDanmakuArgs

    override suspend fun start(arg: Any?) {
        args = arg as? BiliBiliDanmakuArgs ?: return
        if (args.token.isEmpty()) {
            CoreLog.w("B站弹幕信息缺失，跳过弹幕连接：roomId=${args.roomId}")
            onClose?.invoke("弹幕信息缺失，已跳过弹幕连接")
            return
        }
        webSocketUtil = WebSocketUtil(
            url = "wss://${args.serverHost}/sub",
            heartBeatTime = heartbeatTime.toLong(),
            headers = if (args.cookie.isEmpty()) null else mapOf("cookie" to args.cookie),
            onByteMessage = { decodeMessage(it) },
            onReady = {
                onReady?.invoke()
                joinRoom(args)
            },
            onHeartBeat = { heartbeat() },
            onReconnect = { onClose?.invoke("与服务器断开连接，正在尝试重连") },
            onClose = { onClose?.invoke("服务器连接失败$it") },
        )
        webSocketUtil?.connect()
    }

    private fun joinRoom(args: BiliBiliDanmakuArgs) {
        val joinData = encodeData(
            buildJsonObject {
                put("uid", args.uid)
                put("roomid", args.roomId)
                put("protover", 3)
                put("buvid", args.buvid)
                put("platform", "web")
                put("type", 2)
                put("key", args.token)
            }.toString(),
            action = 7,
        )
        webSocketUtil?.sendBytes(joinData)
    }

    override fun heartbeat() {
        webSocketUtil?.sendBytes(encodeData("", action = 2))
    }

    override suspend fun stop() {
        onMessage = null
        onClose = null
        webSocketUtil?.close()
    }

    /** 编码一帧：16 字节包头 + 载荷（对应 Dart encodeData）。 */
    private fun encodeData(msg: String, action: Int): ByteArray {
        val data = msg.toByteArray(Charsets.UTF_8)
        val length = data.size + 16
        val buffer = ByteArray(length)
        writeInt(buffer, 0, length, 4)      // 数据包长度
        writeInt(buffer, 4, 16, 2)          // 数据包头部长度，固定 16
        writeInt(buffer, 6, 0, 2)           // 协议版本，0=JSON
        writeInt(buffer, 8, action, 4)      // 操作类型
        writeInt(buffer, 12, 1, 4)          // 头部固定 1
        System.arraycopy(data, 0, buffer, 16, data.size)
        return buffer
    }

    private fun writeInt(buffer: ByteArray, start: Int, value: Int, len: Int) {
        for (i in 0 until len) {
            buffer[start + len - 1 - i] = ((value shr (8 * i)) and 0xFF).toByte()
        }
    }

    private fun decodeMessage(bytes: ByteArray) {
        try {
            // 协议版本：0=JSON，1=人气值(Int32)，2=zlib 压缩 Buffer，3=brotli 压缩
            val protocolVersion = readInt(bytes, 6, 2)
            // 操作类型：3=心跳回应(人气值)，5=通知(弹幕/广播)，8=进房回应
            val operation = readInt(bytes, 8, 4)
            var body = bytes.copyOfRange(16, bytes.size)
            if (operation == 3) {
                val online = readInt(body, 0, 4)
                onMessage?.invoke(
                    LiveMessage(
                        type = LiveMessageType.online,
                        data = online,
                        color = LiveMessageColor.white,
                        message = "",
                        userName = "",
                    )
                )
            } else if (operation == 5) {
                body = when (protocolVersion) {
                    2 -> zlibDecode(body)
                    3 -> brotliDecode(body)
                    else -> body
                }
                val text = String(body, Charsets.UTF_8)
                val group = text.split(Regex("[\\u0000-\\u001f]+"))
                for (item in group) {
                    if (item.length > 2 && item.startsWith("{")) {
                        parseMessage(item)
                    }
                }
            }
        } catch (e: Exception) {
            CoreLog.error(e)
        }
    }

    private fun zlibDecode(body: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(body)
        val output = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!inflater.finished()) {
            val count = inflater.inflate(buf)
            output.write(buf, 0, count)
        }
        inflater.end()
        return output.toByteArray()
    }

    private fun brotliDecode(body: ByteArray): ByteArray =
        BrotliInputStream(ByteArrayInputStream(body)).use { it.readBytes() }

    private fun parseMessage(jsonMessage: String) {
        try {
            val obj = Json.parseToJsonElement(jsonMessage).jsonObject
            val cmd = obj["cmd"].str().toString()
            if (cmd.contains("DANMU_MSG")) {
                val info = obj["info"]
                if (info != null && info !is kotlinx.serialization.json.JsonNull) {
                    val arr = info.jsonArray
                    val message = arr.getOrNull(1).str().orEmpty()
                    val color = arr.getOrNull(0)?.jsonArray?.getOrNull(3).intv() ?: 0
                    val userArr = arr.getOrNull(2)
                    if (userArr != null && userArr is kotlinx.serialization.json.JsonArray && userArr.isNotEmpty()) {
                        val username = userArr.getOrNull(1).str().orEmpty()
                        val spans = extractSpans(arr, message)
                        val imageUrls = spans.filter { it.isImage }.map { it.imageUrl!!.trim() }.distinct()
                        val liveMsg = LiveMessage(
                            type = LiveMessageType.chat,
                            userName = username,
                            message = message,
                            color = if (color == 0) LiveMessageColor.white else LiveMessageColor.numberToColor(color),
                            imageUrls = if (imageUrls.isEmpty()) null else imageUrls,
                            spans = if (spans.isEmpty()) null else spans,
                        )
                        onMessage?.invoke(liveMsg)
                    }
                }
            } else if (cmd == "SUPER_CHAT_MESSAGE") {
                val data = obj["data"]?.jsonObject ?: return
                val userInfo = data["user_info"]?.jsonObject
                val sc = LiveSuperChatMessage(
                    backgroundBottomColor = data["background_bottom_color"].str().orEmpty(),
                    backgroundColor = data["background_color"].str().orEmpty(),
                    endTime = Instant.ofEpochSecond(data["end_time"].intv()?.toLong() ?: 0L),
                    face = "${userInfo?.get("face").str().orEmpty()}@200w.jpg",
                    message = data["message"].str().orEmpty(),
                    price = data["price"].intv() ?: 0,
                    startTime = Instant.ofEpochSecond(data["start_time"].intv()?.toLong() ?: 0L),
                    userName = userInfo?.get("uname").str().orEmpty(),
                )
                onMessage?.invoke(
                    LiveMessage(
                        type = LiveMessageType.superChat,
                        userName = "SUPER_CHAT_MESSAGE",
                        message = "SUPER_CHAT_MESSAGE",
                        color = LiveMessageColor.white,
                        data = sc,
                    )
                )
            }
        } catch (e: Exception) {
            CoreLog.error(e)
        }
    }

    private fun extractSpans(info: kotlinx.serialization.json.JsonArray, message: String): List<LiveMessageSpan> {
        val emojiMap = extractEmojiMap(info, message)
        if (emojiMap.isEmpty()) return emptyList()
        val spans = mutableListOf<LiveMessageSpan>()
        val keys = emojiMap.keys.sortedByDescending { it.length }
        var index = 0
        while (index < message.length) {
            var matchedKey: String? = null
            for (key in keys) {
                if (key.isEmpty()) continue
                if (message.startsWith(key, index)) {
                    matchedKey = key
                    break
                }
            }
            if (matchedKey == null) {
                val nextIndex = nextEmojiIndex(message, keys, index + 1)
                spans.add(LiveMessageSpan(text = message.substring(index, nextIndex)))
                index = nextIndex
                continue
            }
            val imageUrl = emojiMap[matchedKey]?.trim().orEmpty()
            if (imageUrl.isNotEmpty()) {
                spans.add(LiveMessageSpan(imageUrl = imageUrl))
            }
            index += matchedKey.length
        }
        return spans
    }

    private fun nextEmojiIndex(message: String, keys: List<String>, start: Int): Int {
        var next = message.length
        for (key in keys) {
            if (key.isEmpty()) continue
            val index = message.indexOf(key, start)
            if (index in 0 until next) next = index
        }
        return next
    }

    private fun extractEmojiMap(info: kotlinx.serialization.json.JsonArray, message: String): Map<String, String> {
        val emojiMap = LinkedHashMap<String, String>()

        fun addEmoji(key: String, url: String?) {
            val emojiKey = key.trim()
            val value = url?.trim().orEmpty()
            if (emojiKey.isEmpty() || value.isEmpty()) return
            emojiMap[emojiKey] = value
        }

        // info[0][13].url —— 整条消息是单个表情
        try {
            val i0 = info.getOrNull(0)?.jsonArray
            val key = message.trim()
            if (i0 != null && i0.size > 13 && key.startsWith("[") && key.endsWith("]")) {
                val url = i0.getOrNull(13)?.jsonObject?.get("url").str()
                addEmoji(key, url)
            }
        } catch (_: Exception) {
        }

        // info[0][15].extra.emots —— 多表情
        try {
            val i0 = info.getOrNull(0)?.jsonArray
            if (i0 != null && i0.size > 15) {
                val extra = i0.getOrNull(15)?.jsonObject?.get("extra").str().orEmpty()
                if (extra.isNotEmpty()) {
                    val extraObj = Json.parseToJsonElement(extra).jsonObject
                    val emots = extraObj["emots"]?.jsonObject
                    if (emots != null) {
                        for ((k, v) in emots) {
                            if (k.isNotEmpty() && !message.contains(k)) continue
                            val url = v.jsonObject["url"].str()
                            addEmoji(k, url)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        return emojiMap
    }

    private fun readInt(buffer: ByteArray, start: Int, len: Int): Int {
        var result = 0
        for (i in 0 until len) {
            result = (result shl 8) or (buffer[start + i].toInt() and 0xFF)
        }
        return result
    }
}
