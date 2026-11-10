package com.kotlin_livez.app.modules.mine.parse

import com.kotlin_livez.core.common.LiveLinkFetcher
import com.kotlin_livez.core.common.LiveLinkResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ParseController` 失败原因 → 用户文案的映射测试。
 *
 * 覆盖产品明确的两条特殊文案：快手「暂不支持该平台」、短链失败「请检查网络后重试」，
 * 其余原因统一「无法解析此链接」（与 Dart 文案一致）。
 */
class ParseControllerTest {

    private class FakeFetcher(private val script: Map<String, String> = emptyMap()) :
        LiveLinkFetcher {
        override suspend fun followRedirects(url: String): String = script[url] ?: ""
    }

    private fun controller(script: Map<String, String> = emptyMap()): ParseController =
        ParseController(LiveLinkResolver(FakeFetcher(script)))

    @Test
    fun kuaishouLinkTellsPlatformUnsupported() = runBlocking {
        val result = controller().parse("https://v.kuaishou.com/abc")

        val failure = result as ParseController.ParseResult.Failure
        assertEquals("暂不支持该平台", failure.message)
    }

    @Test
    fun unknownHostIsUnparsable() = runBlocking {
        val result = controller().parse("https://example.com/123")

        assertEquals(
            "无法解析此链接",
            (result as ParseController.ParseResult.Failure).message,
        )
    }

    @Test
    fun emptyInputHasItsOwnMessage() = runBlocking {
        val result = controller().parse("   ")

        assertEquals("链接不能为空", (result as ParseController.ParseResult.Failure).message)
    }

    @Test
    fun directDouyinLinkResolvesToRegisteredSite() = runBlocking {
        val result = controller().parse("https://live.douyin.com/123456")

        val success = result as ParseController.ParseResult.Success
        assertEquals("douyin", success.site.id)
        assertEquals("123456", success.roomId)
    }

    @Test
    fun shortLinkIsResolvedBeforeSiteLookup() = runBlocking {
        val result = controller(
            mapOf("https://v.douyin.com/abc/" to "https://live.douyin.com/7654321"),
        ).parse("https://v.douyin.com/abc/")

        val success = result as ParseController.ParseResult.Success
        assertEquals("douyin", success.site.id)
        assertEquals("7654321", success.roomId)
    }

    @Test
    fun failedShortLinkIsReportedAsNetworkFailure() = runBlocking {
        val result = controller().parse("https://v.douyin.com/abc/")

        val failure = result as ParseController.ParseResult.Failure
        assertEquals("短链解析失败，请检查网络后重试", failure.message)
    }

    @Test
    fun everySiteInRegistryHasRoomParsing() = runBlocking {
        val cases = mapOf(
            "https://live.bilibili.com/2225346" to "bilibili",
            "https://www.douyu.com/4492468" to "douyu",
            "https://www.huya.com/660148" to "huya",
            "https://live.douyin.com/123456789" to "douyin",
        )
        cases.forEach { (url, siteId) ->
            val success = controller().parse(url) as ParseController.ParseResult.Success
            assertEquals("站点不符：$url", siteId, success.site.id)
            assertTrue(success.roomId.isNotEmpty())
        }
    }
}
