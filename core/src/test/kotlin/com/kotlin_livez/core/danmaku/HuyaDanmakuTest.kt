package com.kotlin_livez.core.danmaku

import com.kotlin_livez.core.model.LiveMessage
import com.kotlin_livez.core.model.LiveMessageType
import com.kotlin_livez.core.model.tars.HYBulletFormat
import com.kotlin_livez.core.model.tars.HYMessage
import com.kotlin_livez.core.model.tars.HYMsgItem
import com.kotlin_livez.core.model.tars.HYPushMessage
import com.kotlin_livez.core.model.tars.HYPushMessageV2
import com.kotlin_livez.core.model.tars.HYSender
import com.kotlin_livez.core.model.tars.TarsInputStream
import com.kotlin_livez.core.model.tars.TarsOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HuyaDanmakuTest {

    @Test
    fun invalidRegistrationArgsFailStartExplicitly() {
        runBlocking {
            assertFailsWith<IllegalArgumentException> {
                HuyaDanmaku().start(HuyaDanmakuArgs(ayyuid = 0L, topSid = 0L, subSid = 0L))
            }
        }
    }

    @Test
    fun commandType22_decodesIndependentWireFixture() {
        // 固定帧由独立 Tars 实现按虎牙 schema 生成，不依赖本项目的 TarsOutputStream：
        // command=22; group="g"; item(uri=6111,msg=[1,2],msgId=3)。
        val frame = byteArrayOf(
            0x00, 0x16, 0x1D, 0x00, 0x00, 0x13,
            0x06, 0x01, 0x67, 0x19, 0x00, 0x01, 0x0A,
            0x01, 0x17, 0xDF.toByte(), 0x1D, 0x00, 0x00, 0x02,
            0x01, 0x02, 0x20, 0x03, 0x0B,
        )

        val outer = TarsInputStream(frame)
        assertEquals(22L, outer.readLong(0, true))
        val push = HYPushMessageV2().apply {
            readFrom(TarsInputStream(outer.readBytes(1, true)))
        }

        assertEquals("g", push.groupId)
        assertEquals(1, push.msgItems.size)
        assertEquals(6111L, push.msgItems.single().uri)
        assertEquals(listOf<Byte>(1, 2), push.msgItems.single().msg.toList())
        assertEquals(3L, push.msgItems.single().msgId)
    }

    @Test
    fun joinPacket_preservesLongIdsAndUsesSubSid() {
        val args = HuyaDanmakuArgs(
            ayyuid = 35_184_606_802_579L,
            topSid = 1_199_591_987_704L,
            subSid = 1_199_591_987_705L,
        )

        val outer = TarsInputStream(HuyaDanmaku().buildJoinData(args))
        assertEquals(1L, outer.readLong(0, true))
        val registration = TarsInputStream(outer.readBytes(1, true))
        assertEquals(args.ayyuid, registration.readLong(0, true))
        assertEquals(true, registration.readBool(1, true))
        assertEquals("", registration.readString(2, true))
        assertEquals("", registration.readString(3, true))
        assertEquals(args.topSid, registration.readLong(4, true))
        assertEquals(args.subSid, registration.readLong(5, true))
    }

    @Test
    fun commandType7_decodesLegacyChatPush() {
        val received = mutableListOf<LiveMessage>()
        val danmaku = HuyaDanmaku().apply { onMessage = received::add }
        val push = HYPushMessage().apply {
            uri = 1400L
            msg = chatPayload("legacy-user", "legacy-message")
        }

        danmaku.decodeMessage(commandFrame(7L, push.toByteArray()))

        assertEquals(1, received.size)
        assertEquals(LiveMessageType.chat, received.single().type)
        assertEquals("legacy-user", received.single().userName)
        assertEquals("legacy-message", received.single().message)
    }

    @Test
    fun commandType7_dropsInvisibleChatPayload() {
        val received = mutableListOf<LiveMessage>()
        val danmaku = HuyaDanmaku().apply { onMessage = received::add }
        val push = HYPushMessage().apply {
            uri = 1400L
            msg = chatPayload("ghost", " \u200B\u2060 ")
        }

        danmaku.decodeMessage(commandFrame(7L, push.toByteArray()))

        assertEquals(emptyList<LiveMessage>(), received)
    }

    @Test
    fun commandType22_decodesEveryBatchedPushItem() {
        val received = mutableListOf<LiveMessage>()
        val danmaku = HuyaDanmaku().apply { onMessage = received::add }
        val batched = HYPushMessageV2().apply {
            groupId = "room-group"
            msgItems = listOf(
                HYMsgItem().apply {
                    uri = 1400L
                    msgId = 1001L
                    msg = chatPayload("batch-user", "batch-message")
                },
                HYMsgItem().apply {
                    uri = 8006L
                    msgId = 1002L
                    msg = TarsOutputStream().apply { writeLong(123_456L, 0) }.toByteArray()
                },
            )
        }

        danmaku.decodeMessage(commandFrame(22L, batched.toByteArray()))

        assertEquals(2, received.size)
        assertEquals(LiveMessageType.chat, received[0].type)
        assertEquals("batch-user", received[0].userName)
        assertEquals("batch-message", received[0].message)
        assertEquals(LiveMessageType.online, received[1].type)
        assertEquals(123_456L, received[1].data)
    }

    @Test
    fun commandType22_badItemDoesNotDiscardRemainingBatch() {
        val received = mutableListOf<LiveMessage>()
        val danmaku = HuyaDanmaku().apply { onMessage = received::add }
        val batched = HYPushMessageV2().apply {
            msgItems = listOf(
                HYMsgItem().apply {
                    uri = 1400L
                    msg = byteArrayOf(0x07) // 截断的 Tars STRING4
                },
                HYMsgItem().apply {
                    uri = 1400L
                    msg = chatPayload("survivor", "still-decoded")
                },
            )
        }

        danmaku.decodeMessage(commandFrame(22L, batched.toByteArray()))

        assertEquals(1, received.size)
        assertEquals("survivor", received.single().userName)
        assertEquals("still-decoded", received.single().message)
    }

    private fun commandFrame(type: Long, payload: ByteArray): ByteArray =
        TarsOutputStream().apply {
            writeLong(type, 0)
            writeBytes(payload, 1)
        }.toByteArray()

    private fun chatPayload(userName: String, content: String): ByteArray =
        HYMessage().apply {
            userInfo = HYSender().apply {
                uid = 42L
                lMid = 43L
                nickName = userName
                gender = 0
            }
            this.content = content
            bulletFormat = HYBulletFormat().apply { fontColor = 0 }
        }.toByteArray()
}
