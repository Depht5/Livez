package com.kotlin_livez.core.common

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LiveLinkResolver] / [OkHttpLiveLinkFetcher] 契约测试。
 *
 * 短链恢复的三条硬约束（都是历史缺陷点）：
 * 1. 嵌套深度有上限 —— 短链返回 200（跳转环的一种）时不能无限递归；
 * 2. 请求不带 Cookie —— 短链恢复必须与登录态无关；
 * 3. 跳转环/超跳数必须收敛为失败，而不是把上一个地址当成功。
 */
class LiveLinkResolverTest {

    /** 假 fetcher：按脚本返回落地地址，未命中视为无法恢复。 */
    private class FakeFetcher(private val script: Map<String, String>) : LiveLinkFetcher {
        val calls = mutableListOf<String>()

        override suspend fun followRedirects(url: String): String {
            calls += url
            return script[url] ?: ""
        }
    }

    @Test
    fun directLinkDoesNotTouchNetwork() = runBlocking {
        val fetcher = FakeFetcher(emptyMap())
        val outcome = LiveLinkResolver(fetcher).resolve("https://live.douyin.com/123456")

        assertTrue(outcome is LiveUrlOutcome.Room)
        assertEquals("123456", (outcome as LiveUrlOutcome.Room).roomId)
        assertTrue(fetcher.calls.isEmpty(), "直链不应发起网络请求")
    }

    @Test
    fun shortLinkIsResolvedThroughRedirect() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf("https://v.douyin.com/abc/" to "https://live.douyin.com/7654321"),
        )
        val outcome = LiveLinkResolver(fetcher).resolve("https://v.douyin.com/abc/")

        val room = outcome as LiveUrlOutcome.Room
        assertEquals("7654321", room.roomId)
        assertEquals("douyin", room.siteId)
        assertEquals(listOf("https://v.douyin.com/abc/"), fetcher.calls)
    }

    @Test
    fun shareTextWithShortLinkIsExtractedFirst() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf("https://v.douyin.com/abc/" to "https://live.douyin.com/7654321"),
        )
        val outcome = LiveLinkResolver(fetcher)
            .resolve("正在直播 https://v.douyin.com/abc/ 快来看")

        assertEquals("7654321", (outcome as LiveUrlOutcome.Room).roomId)
    }

    @Test
    fun unresolvableShortLinkFails() = runBlocking {
        val outcome = LiveLinkResolver(FakeFetcher(emptyMap()))
            .resolve("https://v.douyin.com/abc/")

        assertEquals(
            LiveUrlRejectReason.SHORT_LINK_FAILED,
            (outcome as LiveUrlOutcome.Rejected).reason,
        )
    }

    /** 短链一直返回自身（200 结束），深度上限必须兜住，且只在每层各试一次。 */
    @Test
    fun selfReferencingShortLinkStopsAtDepthLimit() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf("https://v.douyin.com/loop" to "https://v.douyin.com/loop"),
        )
        val outcome = LiveLinkResolver(fetcher).resolve("https://v.douyin.com/loop")

        assertEquals(
            LiveUrlRejectReason.SHORT_LINK_FAILED,
            (outcome as LiveUrlOutcome.Rejected).reason,
        )
        assertEquals(LiveLinkResolver.MAX_DEPTH, fetcher.calls.size)
    }

    @Test
    fun kuaishouShortLinkIsRejectedWithoutNetwork() = runBlocking {
        val fetcher = FakeFetcher(
            mapOf("https://v.kuaishou.com/abc" to "https://live.kuaishou.com/u/room"),
        )
        val outcome = LiveLinkResolver(fetcher).resolve("https://v.kuaishou.com/abc")

        assertEquals(
            LiveUrlRejectReason.PLATFORM_UNSUPPORTED,
            (outcome as LiveUrlOutcome.Rejected).reason,
        )
        assertTrue(fetcher.calls.isEmpty(), "平台已下架，不应发起网络请求")
    }

    // ---- OkHttpLiveLinkFetcher：真实 302 链路 ----

    @Test
    fun okHttpFetcherFollowsRedirectsWithoutCookie() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(302)
                    .addHeader("Location", server.url("/second").toString()),
            )
            server.enqueue(
                MockResponse().setResponseCode(302)
                    .addHeader("Location", server.url("/third").toString()),
            )
            server.enqueue(MockResponse().setResponseCode(200))

            val fetcher = OkHttpLiveLinkFetcher(OkHttpLiveLinkFetcher.defaultClient())
            val finalUrl = fetcher.followRedirects(server.url("/first").toString())

            assertEquals(server.url("/third").toString(), finalUrl)
            repeat(3) {
                assertNull(server.takeRequest().getHeader("Cookie"), "短链恢复不能带 Cookie")
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun okHttpFetcherReturnsLandingUrlOnNonRedirect() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200))

            val target = server.url("/page").toString()
            val fetcher = OkHttpLiveLinkFetcher(OkHttpLiveLinkFetcher.defaultClient())

            assertEquals(target, fetcher.followRedirects(target))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun okHttpFetcherStopsAtRedirectLimit() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            // 6 个各不相同的 302：第 5 跳后仍未落地，必须放弃（不能把第 5 跳当地址）
            repeat(OkHttpLiveLinkFetcher.MAX_REDIRECTS + 1) { index ->
                server.enqueue(
                    MockResponse().setResponseCode(302)
                        .addHeader("Location", server.url("/hop$index").toString()),
                )
            }

            val fetcher = OkHttpLiveLinkFetcher(OkHttpLiveLinkFetcher.defaultClient())
            val result = fetcher.followRedirects(server.url("/start").toString())

            assertEquals("", result)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun okHttpFetcherDetectsRedirectLoop() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val loop = server.url("/loop").toString()
            repeat(3) {
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", loop))
            }

            val fetcher = OkHttpLiveLinkFetcher(OkHttpLiveLinkFetcher.defaultClient())

            assertEquals("", fetcher.followRedirects(loop))
        } finally {
            server.shutdown()
        }
    }
}
