package com.kotlin_livez.core.danmaku

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [DouyinSocketUrl] 回归测试。
 *
 * 每条用例都锚定真机 2026-11-11 的故障：`wss://` 地址被交给 OkHttp 的 HttpUrl 解析 →
 * `IllegalArgumentException` → 弹幕全断。修复后必须能产出可交给 OkHttp 的 ws(s) 地址。
 */
class DouyinSocketUrlTest {

    private val base = "wss://webcast3-ws-web-lq.douyin.com/webcast/im/push/v2/"

    @Test
    fun wssBaseIsAcceptedAndRoundTripped() {
        val url = DouyinSocketUrl.build(base, mapOf("room_id" to "7706701179086162730"), "sig")

        assertTrue(url.startsWith("wss://"), "必须还原成加密 ws 地址：$url")
        assertTrue(url.contains("webcast3-ws-web-lq.douyin.com"), "host 不应丢失：$url")
        assertTrue(url.contains("/webcast/im/push/v2/"), "path 不应丢失：$url")
        assertTrue(url.contains("room_id=7706701179086162730"), "查询参数应保留：$url")
        assertTrue(url.contains("signature=sig"), "签名应作为查询参数：$url")
    }

    @Test
    fun wsBaseStaysPlainText() {
        assertFalse(DouyinSocketUrl.isSecure("ws://example.com/path"))
        val url = DouyinSocketUrl.build("ws://example.com/path", emptyMap(), "s")
        assertTrue(url.startsWith("ws://"), "明文地址不得被升级为 wss：$url")
    }

    @Test
    fun baseWithoutSchemeIsTreatedAsSecure() {
        val url = DouyinSocketUrl.build("webcast.example.com/webcast/im/push/v2/", emptyMap(), "")
        assertTrue(url.startsWith("wss://"), "无协议头默认按加密处理：$url")
        assertTrue(DouyinSocketUrl.isSecure("webcast.example.com/path"))
    }

    @Test
    fun queryValuesAreEncodedExactlyOnce() {
        val url = DouyinSocketUrl.build(
            base,
            mapOf("browser_version" to "5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36"),
            "sig",
        )

        assertTrue(url.contains("browser_version=5.0%20%28Windows%20NT%2010.0%3B%20WOW64%29"),
            "参数应只编码一次：$url")
        assertFalse(url.contains("%2520"), "不得出现二次编码：$url")
    }

    /** 用 OkHttp 自己的解析器验证产物可被接受（这正是此前崩掉的那一步）。 */
    @Test
    fun producedUrlIsUsableByOkHttpRequestBuilder() {
        val url = DouyinSocketUrl.build(base, mapOf("cursor" to "h-1_t-1_r-1_d-1_u-1"), "sig")

        val request = okhttp3.Request.Builder().url(url).build()
        // Request.Builder().url(String) 会把 wss 归一成 https（OkHttp 内部约定），
        // 关键是「不再抛 IllegalArgumentException」且 host/path/查询串完整。
        assertEquals("https", request.url.scheme)
        assertEquals("webcast3-ws-web-lq.douyin.com", request.url.host)
        assertEquals("/webcast/im/push/v2/", request.url.encodedPath)
        assertEquals("sig", request.url.queryParameter("signature"))
    }

    @Test
    fun existingQueryOnBaseIsReplaced() {
        val url = DouyinSocketUrl.build("$base?stale=1", mapOf("cursor" to "c"), "sig")

        assertFalse(url.contains("stale=1"), "基底上的旧查询串应被丢弃：$url")
        assertTrue(url.contains("cursor=c"), "新查询参数应在位：$url")
    }
}
