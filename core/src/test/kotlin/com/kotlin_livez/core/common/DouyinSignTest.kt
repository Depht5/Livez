package com.kotlin_livez.core.common

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun md5(input: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}