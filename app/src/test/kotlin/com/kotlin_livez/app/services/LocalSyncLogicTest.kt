package com.kotlin_livez.app.services

import com.kotlin_livez.app.modules.sync.local_sync.LocalSyncController
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 局域网同步纯逻辑契约测试（task 10-24-lan-sync-ui）：
 * 地址解析（parseAddress）、扫码结果拆分（splitScanAddresses）、
 * 接收侧分块进度换算（chunkProgress，itemStart 偏移）、斗鱼账号请求体解析（parseDouyuAccountBody）。
 */
class LocalSyncLogicTest {

    /* ---------- parseAddress ---------- */

    @Test
    fun `裸 IP 补默认端口`() {
        assertEquals("192.168.1.5" to 23234, LocalSyncController.parseAddressStatic("192.168.1.5"))
    }

    @Test
    fun `裸 IP 带端口`() {
        assertEquals("192.168.1.5" to 23234, LocalSyncController.parseAddressStatic("192.168.1.5:23234"))
    }

    @Test
    fun `http 前缀自定义端口`() {
        assertEquals("10.0.0.2" to 8080, LocalSyncController.parseAddressStatic("http://10.0.0.2:8080"))
    }

    @Test
    fun `空白与非法输入返回 null`() {
        assertNull(LocalSyncController.parseAddressStatic(""))
        assertNull(LocalSyncController.parseAddressStatic("   "))
        // http:// 后无 host → Uri.host 为空
        assertNull(LocalSyncController.parseAddressStatic("http://"))
    }

    /* ---------- splitScanAddresses ---------- */

    @Test
    fun `单地址返回单元素`() {
        assertEquals(listOf("192.168.1.5:23234"), LocalSyncController.splitScanAddresses("192.168.1.5:23234"))
    }

    @Test
    fun `多地址按分号拆分`() {
        assertEquals(
            listOf("192.168.1.5:23234", "192.168.1.6:23234"),
            LocalSyncController.splitScanAddresses("192.168.1.5:23234;192.168.1.6:23234"),
        )
    }

    @Test
    fun `拆分去空白并丢弃空段`() {
        assertEquals(
            listOf("a:1", "b:2"),
            LocalSyncController.splitScanAddresses(" a:1 ; ; b:2 ;"),
        )
    }

    /* ---------- chunkProgress（对应 Dart _wrapChunkProgress） ---------- */

    @Test
    fun `无分段声明时原样透传`() {
        val progress = SyncProgress(stage = "接收关注", current = 5, total = 0, message = "任意")
        val chunk = SyncChunk(chunkIndex = 1, chunkTotal = 1, itemStart = 0, itemEnd = 0, itemTotal = 0)
        assertSame(progress, chunkProgress(progress, chunk))
    }

    @Test
    fun `itemStart 偏移换算`() {
        val progress = SyncProgress(stage = "接收关注", current = 50, total = 800)
        val chunk = SyncChunk(chunkIndex = 2, chunkTotal = 3, itemStart = 100, itemEnd = 150, itemTotal = 300)
        val mapped = chunkProgress(progress, chunk)
        assertEquals(150, mapped.current)
        assertEquals(300, mapped.total)
        assertEquals("接收关注 150/300", mapped.message)
    }

    @Test
    fun `偏移结果夹取到 itemTotal`() {
        val progress = SyncProgress(stage = "接收关注", current = 50, total = 800)
        val chunk = SyncChunk(chunkIndex = 2, chunkTotal = 3, itemStart = 280, itemEnd = 300, itemTotal = 300)
        val mapped = chunkProgress(progress, chunk)
        assertEquals(300, mapped.current)
    }

    /* ---------- parseDouyuAccountBody ---------- */

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `完整请求体三字段齐全`() {
        val body = parseDouyuAccountBody(json, """{"cookie":"a=1;b=2","ltp0":"LTP0","dyDid":"DID"}""")
        assertEquals("a=1;b=2", body.cookie)
        assertEquals("LTP0", body.ltp0)
        assertEquals("DID", body.dyDid)
    }

    @Test
    fun `空 cookie 表示清除账号`() {
        val body = parseDouyuAccountBody(json, """{"cookie":""}""")
        assertEquals("", body.cookie)
        assertNull(body.ltp0)
        assertNull(body.dyDid)
    }

    @Test
    fun `字段缺失与 JsonNull 都视为 null`() {
        val body = parseDouyuAccountBody(
            json,
            """{"cookie":"a=1","ltp0":null}""",
        )
        assertEquals("a=1", body.cookie)
        assertNull(body.ltp0)
        assertNull(body.dyDid)
    }
}
