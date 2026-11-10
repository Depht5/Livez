package com.kotlin_livez.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [LiveUrlParser] 契约测试 —— 用例主体移植自 Dart
 * `simple_live_core/test/live_url_parser_test.dart`，并补充 Kotlin 侧边界：
 * 平台下架（快手）、协议校验、分享文案提取、B 站 `/blanc/` 形态。
 */
class LiveUrlParserTest {

    private fun room(url: String): LiveUrlOutcome.Room =
        assertIs<LiveUrlOutcome.Room>(LiveUrlParser.parse(url))

    private fun rejected(url: String): LiveUrlOutcome.Rejected =
        assertIs<LiveUrlOutcome.Rejected>(LiveUrlParser.parse(url))

    @Test
    fun douyinRoomId() {
        val result = room("https://live.douyin.com/123456789")
        assertEquals("douyin", result.siteId)
        assertEquals("123456789", result.roomId)
    }

    @Test
    fun roomIdWithDotIsNotTruncated() {
        assertEquals("123.456", room("https://live.douyin.com/123.456").roomId)
        assertEquals("abc.def", room("https://www.huya.com/abc.def").roomId)
    }

    @Test
    fun douyinWebLivePaths() {
        assertEquals("884412345678", room("https://www.douyin.com/live/884412345678").roomId)
        assertEquals(
            "884412345678",
            room("https://www.douyin.com/follow/live/884412345678?from=tab").roomId,
        )
    }

    @Test
    fun douyinNonLivePathsAreRejected() {
        assertEquals(
            LiveUrlRejectReason.MISSING_ROOM_ID,
            rejected("https://www.douyin.com/video/7123456789012").reason,
        )
        assertEquals(
            LiveUrlRejectReason.MISSING_ROOM_ID,
            rejected("https://www.douyin.com/user/MS4wLjABAAAA").reason,
        )
        assertEquals(
            LiveUrlRejectReason.MISSING_ROOM_ID,
            rejected("https://www.douyin.com/live").reason,
        )
        assertEquals(
            LiveUrlRejectReason.MISSING_ROOM_ID,
            rejected("https://www.douyin.com/follow/live").reason,
        )
    }

    @Test
    fun hostIsCaseInsensitive() {
        val result = room("HTTPS://LIVE.DOUYIN.COM/123456")
        assertEquals("douyin", result.siteId)
        assertEquals("123456", result.roomId)
    }

    @Test
    fun schemeCanBeOmitted() {
        val result = room("live.douyin.com/123456")
        assertEquals("douyin", result.siteId)
        assertEquals("123456", result.roomId)
    }

    @Test
    fun shortLinksAreMarked() {
        val douyin = assertIs<LiveUrlOutcome.ShortLink>(
            LiveUrlParser.parse("https://v.douyin.com/iAbC12d3/"),
        )
        assertEquals("douyin", douyin.siteId)

        val bilibili = assertIs<LiveUrlOutcome.ShortLink>(
            LiveUrlParser.parse("https://b23.tv/Ab12Cd3"),
        )
        assertEquals("bilibili", bilibili.siteId)
    }

    @Test
    fun amemvReflowLink() {
        val result = room("https://webcast.amemv.com/douyin/webcast/reflow/7012345678")
        assertEquals("douyin", result.siteId)
        assertEquals("7012345678", result.roomId)
    }

    @Test
    fun bilibiliRoomIgnoresQuery() {
        val result = room("https://live.bilibili.com/2225346?spm_id_from=x")
        assertEquals("bilibili", result.siteId)
        assertEquals("2225346", result.roomId)
    }

    /** B 站分享卡片常见 `/blanc/<id>` 形态；Dart 侧会误取 `blanc` 作为房间号。 */
    @Test
    fun bilibiliBlancPathUsesSecondSegment() {
        val result = room("https://live.bilibili.com/blanc/31323893")
        assertEquals("bilibili", result.siteId)
        assertEquals("31323893", result.roomId)
    }

    @Test
    fun bilibiliEmptyPathIsRejected() {
        assertEquals(
            LiveUrlRejectReason.MISSING_ROOM_ID,
            rejected("https://live.bilibili.com/").reason,
        )
    }

    @Test
    fun douyuRoomAndTopicRid() {
        assertEquals("4492468", room("https://www.douyu.com/4492468").roomId)

        val topic = room("https://www.douyu.com/topic/xyz?rid=5087042")
        assertEquals("douyu", topic.siteId)
        assertEquals("5087042", topic.roomId)
    }

    @Test
    fun douyuTopicRequiresNumericRid() {
        assertEquals(
            LiveUrlRejectReason.MISSING_ROOM_ID,
            rejected("https://www.douyu.com/topic/xyz").reason,
        )
        assertEquals(
            LiveUrlRejectReason.INVALID_ROOM_ID,
            rejected("https://www.douyu.com/topic/xyz?rid=abc").reason,
        )
    }

    @Test
    fun huyaRoom() {
        val result = room("https://www.huya.com/660148")
        assertEquals("huya", result.siteId)
        assertEquals("660148", result.roomId)
    }

    /** 快手已按产品需求整体移除：识别到域名后必须给出「平台不支持」，而不是「无法解析」。 */
    @Test
    fun kuaishouLinksAreReportedAsUnsupportedPlatform() {
        listOf(
            "https://live.kuaishou.com/u/vvvy",
            "https://v.kuaishou.com/nQxz1",
            "https://live.m.chenzhongtech.com/fw/live/mobile-room",
            "https://m.chenzhongtech.com/fw/live/mobile-room",
        ).forEach { url ->
            assertEquals(
                LiveUrlRejectReason.PLATFORM_UNSUPPORTED,
                rejected(url).reason,
                "应识别为平台不支持：$url",
            )
        }
    }

    @Test
    fun unknownOrInvalidInputIsRejected() {
        assertEquals(LiveUrlRejectReason.UNKNOWN_HOST, rejected("https://example.com/123").reason)
        assertEquals(LiveUrlRejectReason.EMPTY, rejected("").reason)
        assertEquals(LiveUrlRejectReason.NOT_HTTP, rejected("随便一段话").reason)
        assertEquals(LiveUrlRejectReason.NOT_HTTP, rejected("ftp://live.douyin.com/123").reason)
    }

    @Test
    fun extractHttpUrlFromShareText() {
        assertEquals(
            "https://v.douyin.com/GiurKu1HX_I/",
            LiveUrlParser.extractHttpUrl(
                "正在直播，复制链接打开抖音 https://v.douyin.com/GiurKu1HX_I/ 5@9.com",
            ),
        )
        // 中文标点作为边界
        assertEquals(
            "https://live.douyin.com/123456",
            LiveUrlParser.extractHttpUrl("直播地址：https://live.douyin.com/123456，欢迎观看"),
        )
        // 尾部右括号/标点剥掉（微信分享常见）
        assertEquals(
            "https://v.douyin.com/abc",
            LiveUrlParser.extractHttpUrl("(https://v.douyin.com/abc)，欢迎观看"),
        )
        assertEquals("", LiveUrlParser.extractHttpUrl("没有链接"))
    }

    /** 端口/大小写等归一化后仍应命中；非支持域名里的"像域名"输入不应崩。 */
    @Test
    fun normalizeEdgeCases() {
        val result = room("https://www.huya.com:443/660148")
        assertEquals("660148", result.roomId)
        assertTrue(LiveUrlParser.parse("  https://www.huya.com/660148  ") is LiveUrlOutcome.Room)
    }
}
