package com.kotlin_livez.core.common

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DouyinSignTest {

    @Test
    fun generateMsToken_usesAlphanumeric() {
        val token = DouyinSign.generateMsToken(107)
        assertEquals(107, token.length)
        assertTrue(token.all { it.isLetterOrDigit() })
    }

    @Test
    fun generateMsToken_isRandom() {
        assertNotEquals(DouyinSign.generateMsToken(32), DouyinSign.generateMsToken(32))
    }

    @Test
    fun msStubFromParams_matchesDartMd5() {
        // 对应 Dart：sigParams = "a=1,b=2" 后取 md5
        val params = linkedMapOf("a" to "1", "b" to "2")
        val stub = DouyinSign.getMsStubFromParams(params)
        // Java 侧计算预期：md5("a=1,b=2")
        val expected = md5("a=1,b=2")
        assertEquals(expected, stub)
    }

    @Test
    fun getSignatures_paramsIncludeDefaults() {
        val params = DouyinSign.getDefaultSignatureParams("123", "456")
        assertEquals("1", params["live_id"])
        assertEquals("6383", params["aid"])
        assertEquals("123", params["room_id"])
        assertEquals("456", params["user_unique_id"])
        assertEquals("web", params["device_platform"])
    }

    /**
     * 关键回归：Rhino 必须能实际执行 abogus.js 产出 a_bogus。
     * 抖音首页推荐接口依赖此签名；若此测试失败（抛异常），首页将无法加载内容。
     */
    @Test
    fun abogus_script_producesSignature() = runBlocking {
        val uri = "https://live.douyin.com/webcast/web/partition/detail/room/v2/" +
            "?aid=6383&app_name=douyin_web&live_id=1&device_platform=web&language=zh-CN" +
            "&enter_from=link_share&cookie_enabled=true&screen_width=1980&screen_height=1080" +
            "&browser_language=zh-CN&browser_platform=Win32&browser_name=Edge&browser_version=125.0.0.0" +
            "&browser_online=true&count=15&offset=0&partition=720&partition_type=1&req_from=2"
        val signed = DouyinSign.getAbogusUrlWithMsToken(uri, DouyinSign.defaultUserAgent)
        assertTrue(signed.contains("a_bogus="), "签名结果应包含 a_bogus，实际: $signed")
        assertTrue(signed.contains("msToken="), "签名结果应包含 msToken，实际: $signed")
    }

    /**
     * 关键回归：Rhino 必须能执行 webms_sdk.js 产出 webcast signature。
     *
     * 抖音弹幕的 IM 预取与 WebSocket 连接都依赖此签名；脚本一旦在 Rhino 里抛
     * `ReferenceError`（2026-11-11 真机日志：`w_0x42f5 is not defined`），弹幕就完全不显示。
     */
    @Test
    fun getSignature_webmsSdkProducesSignature() = runBlocking {
        val signature = DouyinSign.getSignature("7706514266362546954", "1234567890")
        assertTrue(signature.isNotBlank(), "签名不应为空")
        assertTrue(signature.length > 5, "签名长度异常: $signature")
    }

    /**
     * 回归（2026-11-11 抖音弹幕 IM 预取「空响应」）：查询参数只能编码一次。
     *
     * 旧实现取 `rawQuery`（已编码）后交给 `java.net.URI.replaceQuery`（再编码一次），
     * 真机请求日志里 `host=https%253A%252F%252F…`、`im_path=%252Fwebcast…`、
     * `browser_version=5.0%2520%2528Windows…` —— 服务端据此返回空体，弹幕上下文永远拿不到。
     */
    @Test
    fun abogusUrl_doesNotDoubleEncodeQuery() = runBlocking {
        val base = "https://live.douyin.com/webcast/im/fetch/"
        val builder = okhttp3.HttpUrl.Companion.run { base.toHttpUrl() }.newBuilder()
        mapOf(
            "host" to "https://live.douyin.com",
            "im_path" to "/webcast/im/fetch/",
            "browser_version" to "5.0 (Windows NT 10.0; WOW64)",
        ).forEach { (k, v) -> builder.addQueryParameter(k, v) }
        val unsigned = builder.build().toString()

        val signed = DouyinSign.getAbogusUrlWithMsToken(unsigned, DouyinSign.defaultUserAgent)

        assertTrue(signed.contains("host=https%3A%2F%2Flive.douyin.com"), "参数应只编码一次：$signed")
        assertTrue(signed.contains("im_path=%2Fwebcast%2Fim%2Ffetch%2F"), "im_path 被改写：$signed")
        assertTrue(signed.contains("browser_version=5.0%20%28Windows%20NT%2010.0%3B%20WOW64%29"),
            "browser_version 被改写：$signed")
        assertFalse(signed.contains("%253A"), "出现双重编码：$signed")
        assertFalse(signed.contains("%2520"), "出现双重编码：$signed")
        assertTrue(signed.contains("msToken="), "应追加 msToken：$signed")
        assertTrue(signed.contains("a_bogus="), "应追加 a_bogus：$signed")
    }

    private fun md5(input: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}