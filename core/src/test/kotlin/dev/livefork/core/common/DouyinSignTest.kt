package dev.livefork.core.common

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

    private fun md5(input: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}