package com.kotlin_livez.core.danmaku.proto

import com.kotlin_livez.core.common.ProtoWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DouyinProtoTest {

    /**
     * 在线人数包只消费 total / totalUser。固定的独立 wire 数据里同时带上
     * ranksList(tag 2) 与 seatsList(tag 5)，验证这两段被整段跳过且不影响其余字段。
     */
    @Test
    fun roomUserSeqMessage_skipsUnusedRankAndSeatLists() {
        val contributor = ProtoWriter().apply {
            writeUInt64(1, 99L)                                                        // score
            writeBytes(2, ProtoWriter().apply { writeString(3, "榜一") }.toByteArray()) // user.nickName
        }.toByteArray()

        val payload = ProtoWriter().apply {
            writeUInt64(3, 1234L)      // total
            writeBytes(5, contributor) // seatsList（未使用）
            writeBytes(2, contributor) // ranksList（未使用）
            writeUInt64(7, 567L)       // totalUser
            writeString(8, "567")      // totalUserStr
            writeString(9, "1.2万")    // totalStr
        }.toByteArray()

        val message = RoomUserSeqMessage.fromBytes(payload)

        assertEquals(1234L, message.total)
        assertEquals(567L, message.totalUser)
        assertEquals("567", message.totalUserStr)
        assertEquals("1.2万", message.totalStr)
        assertTrue(message.ranksList.isEmpty())
        assertTrue(message.seatsList.isEmpty())
    }
}
