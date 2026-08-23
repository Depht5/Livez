package dev.livefork.core.danmaku

import dev.livefork.core.common.CoreLog
import dev.livefork.core.common.WebSocketUtil
import dev.livefork.core.model.LiveMessage
import dev.livefork.core.model.LiveMessageColor
import dev.livefork.core.model.LiveMessageType
import dev.livefork.core.model.tars.HYMessage
import dev.livefork.core.model.tars.HYPushMessage
import dev.livefork.core.model.tars.TarsInputStream
import dev.livefork.core.model.tars.TarsOutputStream

/**
 * 虎牙弹幕连接参数。
 * 对应 Dart `HuyaDanmakuArgs`（lib/src/danmaku/huya_danmaku.dart）。
 */
data class HuyaDanmakuArgs(
    /** 房间 ayyuid */
    val ayyuid: Long,
    /** 顶级房间 ID */
    val topSid: Long,
    /** 子房间 ID */
    val subSid: Long,
) {
    override fun toString(): String {
        return """{"ayyuid":$ayyuid,"topSid":$topSid,"subSid":$subSid}"""
    }
}

/**
 * 虎牙弹幕实现。
 * 对应 Dart `HuyaDanmaku`：WebSocket 二进制帧为 Tars 编码，
 * 进房包 {tag0:int 命令, tag1:bytes 参数}，推送帧 {tag0:int 类型, tag1:bytes 载荷}。
 */
class HuyaDanmaku : LiveDanmaku() {

    override var heartbeatTime: Int = 60 * 1000

    /** 弹幕服务器地址（对应 Dart serverUrl）。 */
    val serverUrl: String = "wss://cdnws.api.huya.com"

    private var webSocketUtil: WebSocketUtil? = null
    private lateinit var danmakuArgs: HuyaDanmakuArgs

    /** 心跳数据：Dart 端 base64.decode("ABQdAAwsNgBM") 的预编码字节，原样发送。 */
    private val heartbeatData: ByteArray = byteArrayOf(
        0x00, 0x14, 0x1D, 0x00, 0x0C, 0x2C, 0x36, 0x00, 0x4C,
    )

    override suspend fun start(arg: Any?) {
        danmakuArgs = arg as? HuyaDanmakuArgs ?: return
        webSocketUtil = WebSocketUtil(
            url = serverUrl,
            heartBeatTime = heartbeatTime.toLong(),
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

    /** 进房（对应 Dart joinRoom，tid 与 sid 均使用 topSid）。 */
    private fun joinRoom() {
        val joinData = getJoinData(danmakuArgs.ayyuid, danmakuArgs.topSid, danmakuArgs.topSid)
        webSocketUtil?.sendBytes(joinData)
    }

    /**
     * 构造进房数据（对应 Dart getJoinData）。
     * 参数结构：{ayyuid=0, true=1, ""=2, ""=3, tid=4, sid=5, 0=6, 0=7}；
     * 外层：{1=0, 参数bytes=1}。
     */
    private fun getJoinData(ayyuid: Long, tid: Long, sid: Long): ByteArray {
        return try {
            val oos = TarsOutputStream()
            oos.writeLong(ayyuid, 0)
            oos.writeBool(true, 1)
            oos.writeString("", 2)
            oos.writeString("", 3)
            oos.writeLong(tid, 4)
            oos.writeLong(sid, 5)
            oos.writeLong(0L, 6)
            oos.writeLong(0L, 7)

            val wscmd = TarsOutputStream()
            wscmd.writeLong(1L, 0)
            wscmd.writeBytes(oos.toByteArray(), 1)
            wscmd.toByteArray()
        } catch (e: Exception) {
            CoreLog.error(e)
            ByteArray(0)
        }
    }

    override fun heartbeat() {
        webSocketUtil?.sendBytes(heartbeatData)
    }

    override suspend fun stop() {
        onMessage = null
        onClose = null
        webSocketUtil?.close()
    }

    /**
     * 解码弹幕消息（对应 Dart decodeMessage）。
     * 外层：{type=0, payload=1}；type==7 时 payload 为 HYPushMessage（Tars 结构）。
     * 其中 uri==1400 为聊天弹幕，uri==8006 为在线人数。
     */
    private fun decodeMessage(data: ByteArray) {
        try {
            var stream = TarsInputStream(data)
            val type = stream.readLong(0, false)
            if (type == 7L) {
                stream = TarsInputStream(stream.readBytes(1, false))
                val wSPushMessage = HYPushMessage()
                wSPushMessage.readFrom(stream)
                if (wSPushMessage.uri == 1400) {
                    val messageNotice = HYMessage()
                    messageNotice.readFrom(TarsInputStream(wSPushMessage.msg))
                    val uname = messageNotice.userInfo.nickName
                    val content = messageNotice.content
                    val color = messageNotice.bulletFormat.fontColor
                    onMessage?.invoke(
                        LiveMessage(
                            type = LiveMessageType.chat,
                            color = if (color <= 0) {
                                LiveMessageColor.white
                            } else {
                                LiveMessageColor.numberToColor(color)
                            },
                            message = content,
                            userName = uname,
                        )
                    )
                } else if (wSPushMessage.uri == 8006) {
                    val s = TarsInputStream(wSPushMessage.msg)
                    val online = s.readLong(0, false).toInt()
                    onMessage?.invoke(
                        LiveMessage(
                            type = LiveMessageType.online,
                            data = online,
                            color = LiveMessageColor.white,
                            message = "",
                            userName = "",
                        )
                    )
                }
            }
        } catch (e: Exception) {
            CoreLog.error(e)
        }
    }
}