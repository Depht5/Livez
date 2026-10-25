package com.kotlin_livez.app.services

import com.kotlin_livez.app.modules.sync.local_sync.LocalSyncController
import com.kotlin_livez.app.modules.sync.local_sync.SyncDataType
import com.kotlin_livez.app.modules.sync.local_sync.SyncDeviceController
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

    /* ---------- 设置类型链（task 10-25-lan-sync-polish F3） ---------- */

    @Test
    fun `设置值转换保留原生类型`() {
        assertEquals(true, ProfileBackupService.jsonSettingValue(kotlinx.serialization.json.JsonPrimitive(true)))
        assertEquals(27, ProfileBackupService.jsonSettingValue(kotlinx.serialization.json.JsonPrimitive(27)))
        assertEquals(
            5000000000L,
            ProfileBackupService.jsonSettingValue(kotlinx.serialization.json.JsonPrimitive(5000000000L)),
        )
        assertEquals(1.5f, ProfileBackupService.jsonSettingValue(kotlinx.serialization.json.JsonPrimitive(1.5f)))
        // 字符串保持字符串（JSON 引号语义），即便长得像数字
        assertEquals(
            "abc",
            ProfileBackupService.jsonSettingValue(kotlinx.serialization.json.JsonPrimitive("abc")),
        )
    }

    @Test
    fun `设置值导出与导入 round-trip 类型不变`() {
        val samples = listOf(true, 42, 3.5f, "文本", "12345")
        for (value in samples) {
            val exported = ProfileBackupService.settingValueToJson(value)
            assertEquals(value, ProfileBackupService.jsonSettingValue(exported))
        }
    }

    /* ---------- 已连接设备登记（task 10-25-lan-sync-polish F2） ---------- */

    @Test
    fun `已连接设备按地址去重并刷新到队首`() {
        SyncService.connectedPeers.value = emptyList()
        SyncService.recordConnectedPeer(id = "1", name = "A", address = "10.0.0.1", type = "android")
        SyncService.recordConnectedPeer(id = "2", name = "B", address = "10.0.0.2", type = "android")
        // A 再次连接（刷新）：仍只有 2 条，A 回到队首
        SyncService.recordConnectedPeer(id = "1", name = "A2", address = "10.0.0.1", type = "android")
        val peers = SyncService.connectedPeers.value
        assertEquals(2, peers.size)
        assertEquals("10.0.0.1", peers[0].address)
        assertEquals("A2", peers[0].name)
        assertEquals("10.0.0.2", peers[1].address)
    }

    @Test
    fun `无身份的对端以地址作为名称`() {
        SyncService.connectedPeers.value = emptyList()
        SyncService.recordConnectedPeer(id = "", name = "", address = "10.0.0.3", type = "")
        val peer = SyncService.connectedPeers.value.single()
        assertEquals("10.0.0.3", peer.name)
    }

    /* ---------- 批量选项展开（task 10-25-lan-sync-batch-ui） ---------- */

    @Test
    fun `全部数据展开为七个类别且不含自身`() {
        val plan = SyncDeviceController.planCategories(setOf(SyncDataType.ALL))
        assertEquals(
            listOf(
                SyncDataType.SETTINGS,
                SyncDataType.FOLLOW,
                SyncDataType.HISTORY,
                SyncDataType.DANMU,
                SyncDataType.BILIBILI,
                SyncDataType.DOUYU,
                SyncDataType.DOUYIN,
            ),
            plan,
        )
    }

    @Test
    fun `全部数据与其余选项混选时以全部数据为准`() {
        val plan = SyncDeviceController.planCategories(
            setOf(SyncDataType.ALL, SyncDataType.FOLLOW, SyncDataType.DOUYIN),
        )
        assertEquals(SyncDeviceController.planCategories(setOf(SyncDataType.ALL)), plan)
    }

    @Test
    fun `部分选择按枚举序过滤`() {
        val plan = SyncDeviceController.planCategories(
            setOf(SyncDataType.DOUYIN, SyncDataType.SETTINGS, SyncDataType.HISTORY),
        )
        assertEquals(
            listOf(SyncDataType.SETTINGS, SyncDataType.HISTORY, SyncDataType.DOUYIN),
            plan,
        )
    }

    @Test
    fun `空选择展开为空序列`() {
        assertEquals(emptyList<SyncDataType>(), SyncDeviceController.planCategories(emptySet()))
    }
}
