package com.kotlin_livez.core.model.tars

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tars TUP3 解码回归测试。
 * 覆盖修复：tupResponseDecode 必须从 RequestPacket.sBuffer 内层解码 newData map，
 * 不能沿用外层输入流——否则虎牙 getCdnTokenInfoEx 解析出空 sFlvToken → 播放 URL
 * 无 anticode → CDN 403，表现为"某直播间加载不出画面"。
 */
class TarsHttpClientTest {

    /** 用生产编码 API 构造一个 TUP3 响应（与 TarsUniPacket.encode 同构）。 */
    private fun buildResponseBytes(): ByteArray {
        // newData = {"": code 的 int 编码, "tRsp": struct bytes}，tag0 Map<String, SimpleList>
        val newData = TarsOutputStream().apply {
            writeMap(
                mapOf(
                    "" to TarsOutputStream().apply { writeLong(0L, 0) }.toByteArray(),
                    "tRsp" to TarsOutputStream().apply {
                        writeTarsStruct(GetCdnTokenExResp(sFlvToken = "wsSecret=abc&wsTime=123"), 0)
                    }.toByteArray(),
                ),
                0,
            )
        }.toByteArray()

        // RequestPacket（tag1..10），sBuffer=tag7
        val packet = TarsOutputStream().apply {
            writeLong(3L, 1)        // iVersion
            writeLong(0L, 2)        // cPacketType（ZERO_TAG）
            writeLong(0L, 3)        // iMessageType
            writeLong(0L, 4)        // iRequestId
            writeString("liveui", 5) // sServantName
            writeString("getCdnTokenInfoEx", 6) // sFuncName
            writeBytes(newData, 7)  // sBuffer
            writeLong(0L, 8)        // iTimeout
            writeMap(emptyMap<String, String>(), 9)   // context
            writeMap(emptyMap<String, String>(), 10)  // status
        }.toByteArray()

        // 4 字节大端总长前缀（长度含自身 4 字节）
        val totalSize = packet.size + 4
        val buffer = ByteArray(totalSize)
        buffer[0] = ((totalSize shr 24) and 0xFF).toByte()
        buffer[1] = ((totalSize shr 16) and 0xFF).toByte()
        buffer[2] = ((totalSize shr 8) and 0xFF).toByte()
        buffer[3] = (totalSize and 0xFF).toByte()
        System.arraycopy(packet, 0, buffer, 4, packet.size)
        return buffer
    }

    @Test
    fun tupResponseDecode_readsFlvTokenFromSBuffer() {
        val client = TarsHttpClient("http://wup.huya.com", "liveui", mapOf())
        val method = client.javaClass.getDeclaredMethod(
            "tupResponseDecode",
            ByteArray::class.java,
            TarsStruct::class.java,
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val resp = method.invoke(client, buildResponseBytes(), GetCdnTokenExResp()) as GetCdnTokenExResp
        assertTrue(resp.sFlvToken.isNotEmpty(), "sFlvToken 应从 sBuffer 内层 newData 解码为非空")
        assertEquals("wsSecret=abc&wsTime=123", resp.sFlvToken)
    }
}