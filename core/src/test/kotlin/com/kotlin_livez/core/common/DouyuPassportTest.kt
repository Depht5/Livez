package com.kotlin_livez.core.common

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 斗鱼 Passport（扫码登录 + safeAuth 续期）回归测试。
 *
 * 网络层用 [MockWebServer] + 一个把请求 URL 改写到本地回环的拦截器：
 * 被测代码里的 URL 仍是 `passport.douyu.com` / `www.douyu.com`（域名白名单校验照常生效），
 * 实际报文由本地服务器应答 —— 因此 302 链路、Set-Cookie 累积、JSONP 拆包、状态码映射
 * 都是真跑出来的，而不是只测纯函数。
 */
class DouyuPassportTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    /** 注入 client：把 douyu 域名请求改写到本地 MockWebServer（校验仍按原 URL 走）。 */
    private fun mockClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            val target = server.url("/").newBuilder()
                .encodedPath(request.url.encodedPath)
                .encodedQuery(request.url.encodedQuery)
                .build()
            chain.proceed(request.newBuilder().url(target).build())
        }
        .build()

    private fun jwtWithExp(expSeconds: Long): String {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"exp":$expSeconds}""".toByteArray())
        return "eyJhbGciOiJIUzI1NiJ9.$payload.sig"
    }

    private fun qrGenerateResponses() {
        // 1) 设备号（JSONP），2) 二维码内容
        server.enqueue(
            MockResponse().setBody("""slive_did({"error":0,"msg":"ok","data":{"did":"$kDid"}})"""),
        )
        server.enqueue(
            MockResponse().setBody(
                """{"error":0,"data":{"expire":300,"code":"$kCode",""" +
                    """"url":"https://m.douyu.com/topic/scan-login-middle-page?scan_code=$kCode"}}""",
            ),
        )
    }

    /* ------------------------------- cookieValue ------------------------------- */

    @Test
    fun cookieValue_isCaseInsensitiveAndTrimsSegments() {
        val cookie = " SESSDATA=a ; acf_jwt_token=$kToken ; novalue; =bad; acf_did=did1"
        assertEquals("a", DouyuPassport.cookieValue(cookie, "SESSDATA"))
        assertEquals("a", DouyuPassport.cookieValue(cookie, "sessdata"))
        assertEquals("did1", DouyuPassport.cookieValue(cookie, "ACF_DID"))
        assertEquals("", DouyuPassport.cookieValue(cookie, "missing"))
        assertEquals("", DouyuPassport.cookieValue("", "acf_did"))
    }

    @Test
    fun cookieValue_keepsEqualsInsideValue() {
        assertEquals("a=b=c", DouyuPassport.cookieValue("k=a=b=c", "k"))
    }

    /* ------------------------------ isCookieExpired ----------------------------- */

    @Test
    fun isCookieExpired_falseForFutureJwt() {
        val exp = System.currentTimeMillis() / 1000 + 3600
        assertFalse(DouyuPassport.isCookieExpired("acf_jwt_token=${jwtWithExp(exp)}"))
    }

    @Test
    fun isCookieExpired_trueForPastJwt() {
        val exp = System.currentTimeMillis() / 1000 - 3600
        assertTrue(DouyuPassport.isCookieExpired("acf_jwt_token=${jwtWithExp(exp)}"))
    }

    @Test
    fun isCookieExpired_trueWhenJwtMissingOrMalformed() {
        assertTrue(DouyuPassport.isCookieExpired("acf_uid=1; acf_auth=2"))
        assertTrue(DouyuPassport.isCookieExpired(""))
        assertTrue(DouyuPassport.isCookieExpired("acf_jwt_token=not-a-jwt"))
        assertTrue(DouyuPassport.isCookieExpired("acf_jwt_token=e30.sign"))
    }

    /* ------------------------------ generateQrCode ------------------------------ */

    @Test
    fun generateQrCode_parsesJsonpDeviceAndReturnsQrUrl() = runBlocking {
        qrGenerateResponses()
        val passport = DouyuPassport(mockClient())
        try {
            val url = passport.generateQrCode()
            assertEquals("https://m.douyu.com/topic/scan-login-middle-page?scan_code=$kCode", url)
            // 设备号写进会话 Cookie（确认链路会带上），此时还没有续期凭据
            assertEquals(kDid, passport.did)
            assertEquals("", passport.ltp0)
        } finally {
            passport.close()
        }
    }

    @Test
    fun generateQrCode_throwsOnInvalidPayload() = runBlocking {
        server.enqueue(MockResponse().setBody("not-json-at-all"))
        val passport = DouyuPassport(mockClient())
        try {
            val error = assertFailsWith<CoreError> { passport.generateQrCode() }
            assertTrue(error.message.orEmpty().contains("响应无效"), "实际: ${error.message}")
        } finally {
            passport.close()
        }
    }

    @Test
    fun generateQrCode_throwsWhenFieldsMissing() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"error":0,"data":{"did":"$kDid"}}"""))
        server.enqueue(MockResponse().setBody("""{"error":0,"data":{"code":"$kCode"}}"""))
        val passport = DouyuPassport(mockClient())
        try {
            val error = assertFailsWith<CoreError> { passport.generateQrCode() }
            assertTrue(error.message.orEmpty().contains("二维码生成失败"), "实际: ${error.message}")
        } finally {
            passport.close()
        }
    }

    @Test
    fun generateQrCode_rejectsRedirectOutsideDouyu() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", "https://evil.example.com/steal"),
        )
        val passport = DouyuPassport(mockClient())
        try {
            val error = assertFailsWith<CoreError> { passport.generateQrCode() }
            assertTrue(error.message.orEmpty().contains("地址无效"), "实际: ${error.message}")
        } finally {
            passport.close()
        }
    }

    @Test
    fun generateQrCode_givesUpAfterTooManyRedirects() = runBlocking {
        repeat(5) {
            server.enqueue(
                MockResponse().setResponseCode(302)
                    .setHeader("Location", "https://passport.douyu.com/lapi/did/api/get"),
            )
        }
        val passport = DouyuPassport(mockClient())
        try {
            val error = assertFailsWith<CoreError> { passport.generateQrCode() }
            assertTrue(error.message.orEmpty().contains("跳转次数过多"), "实际: ${error.message}")
        } finally {
            passport.close()
        }
    }

    /* ---------------------------------- poll ---------------------------------- */

    @Test
    fun poll_withoutCodeIsExpired() = runBlocking {
        val passport = DouyuPassport(mockClient())
        try {
            assertEquals(DouyuQrStatus.Expired, passport.poll())
        } finally {
            passport.close()
        }
    }

    @Test
    fun poll_mapsWaitingScannedExpired() = runBlocking {
        qrGenerateResponses()
        val passport = DouyuPassport(mockClient())
        try {
            passport.generateQrCode()
            server.enqueue(MockResponse().setBody("""{"error":-2,"msg":"客户端还未扫码"}"""))
            assertEquals(DouyuQrStatus.Waiting, passport.poll())
            server.enqueue(MockResponse().setBody("""{"error":1,"msg":"已扫码"}"""))
            assertEquals(DouyuQrStatus.Scanned, passport.poll())
            server.enqueue(MockResponse().setBody("""{"error":-1,"msg":"二维码已失效"}"""))
            assertEquals(DouyuQrStatus.Expired, passport.poll())
        } finally {
            passport.close()
        }
    }

    @Test
    fun poll_unknownCodeThrows() = runBlocking {
        qrGenerateResponses()
        val passport = DouyuPassport(mockClient())
        try {
            passport.generateQrCode()
            server.enqueue(MockResponse().setBody("""{"error":42,"msg":"???"}"""))
            val error = assertFailsWith<CoreError> { passport.poll() }
            assertTrue(error.message.orEmpty().contains("暂时无法确认登录"), "实际: ${error.message}")
        } finally {
            passport.close()
        }
    }

    @Test
    fun poll_authorizedCollectsLtp0AndDidFromConfirmSetCookie() = runBlocking {
        qrGenerateResponses()
        val passport = DouyuPassport(mockClient())
        try {
            passport.generateQrCode()
            server.enqueue(
                MockResponse().setBody(
                    """{"error":0,"data":{"url":"/lapi/passport/iframe/confirm?code=$kCode"}}""",
                ),
            )
            server.enqueue(
                MockResponse()
                    .addHeader("Set-Cookie", "LTP0=ltpval; Path=/; HttpOnly")
                    .addHeader("Set-Cookie", "dy_did=didval; Path=/; HttpOnly")
                    .setBody("""{"error":0,"msg":"ok"}"""),
            )
            assertEquals(DouyuQrStatus.Authorized, passport.poll())
            assertEquals("ltpval", passport.ltp0)
            assertEquals("didval", passport.did)
        } finally {
            passport.close()
        }
    }

    @Test
    fun poll_authorizedWithoutCredentialsThrows() = runBlocking {
        qrGenerateResponses()
        val passport = DouyuPassport(mockClient())
        try {
            passport.generateQrCode()
            server.enqueue(
                MockResponse().setBody(
                    """{"error":0,"data":{"url":"/lapi/passport/iframe/confirm?code=$kCode"}}""",
                ),
            )
            // 确认响应只回 error，没有 LTP0/dy_did
            server.enqueue(MockResponse().setBody("""{"error":0}"""))
            val error = assertFailsWith<CoreError> { passport.poll() }
            assertTrue(error.message.orEmpty().contains("续期凭据"), "实际: ${error.message}")
        } finally {
            passport.close()
        }
    }

    @Test
    fun poll_authorizedWithoutConfirmUrlThrows() = runBlocking {
        qrGenerateResponses()
        val passport = DouyuPassport(mockClient())
        try {
            passport.generateQrCode()
            server.enqueue(MockResponse().setBody("""{"error":0,"data":{}}"""))
            val error = assertFailsWith<CoreError> { passport.poll() }
            assertTrue(error.message.orEmpty().contains("确认地址"), "实际: ${error.message}")
        } finally {
            passport.close()
        }
    }

    /* ---------------------------- refreshPlayCookie ---------------------------- */

    @Test
    fun refreshPlayCookie_follows302AndAccumulatesAcfFields() = runBlocking {
        val exp = System.currentTimeMillis() / 1000 + 7 * 24 * 3600
        val jwt = jwtWithExp(exp)
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", "https://www.douyu.com/api/passport/login?loginType=safeAuth")
                .addHeader("Set-Cookie", "acf_uid=1001; Path=/"),
        )
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "acf_jwt_token=$jwt; Path=/")
                .addHeader("Set-Cookie", "acf_auth=authval; Path=/")
                .setBody("<html>ok</html>"),
        )

        val cookie = DouyuPassport.refreshPlayCookie("didval", "ltpval", mockClient())
        assertEquals("1001", DouyuPassport.cookieValue(cookie, "acf_uid"))
        assertEquals("authval", DouyuPassport.cookieValue(cookie, "acf_auth"))
        assertFalse(DouyuPassport.isCookieExpired(cookie))

        // 每一跳都必须带原始续期凭据（换票链后段靠 LTP0 认人）
        repeat(2) {
            val recorded = server.takeRequest()
            assertEquals("dy_did=didval; LTP0=ltpval", recorded.getHeader("Cookie"))
        }
    }

    @Test
    fun refreshPlayCookie_treatsMaxAgeZeroAndPastExpiresAsDeletion() = runBlocking {
        val exp = System.currentTimeMillis() / 1000 + 3600
        val jwt = jwtWithExp(exp)
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", "https://www.douyu.com/api/passport/login?loginType=safeAuth")
                .addHeader("Set-Cookie", "acf_uid=1001; Path=/")
                .addHeader("Set-Cookie", "acf_auth=old; Path=/"),
        )
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "acf_uid=; Max-Age=0; Path=/")
                .addHeader("Set-Cookie", "acf_auth=gone; Expires=Wed, 21 Oct 2015 07:28:00 GMT")
                .addHeader("Set-Cookie", "acf_jwt_token=$jwt; Path=/")
                .setBody(""),
        )

        val cookie = DouyuPassport.refreshPlayCookie("didval", "ltpval", mockClient())
        assertEquals("", DouyuPassport.cookieValue(cookie, "acf_uid"), "Max-Age=0 应删除该字段")
        assertEquals("", DouyuPassport.cookieValue(cookie, "acf_auth"), "过期 Expires 应删除该字段")
        assertFalse(DouyuPassport.isCookieExpired(cookie))
    }

    @Test
    fun refreshPlayCookie_rejectsCookieWithoutAcfIdentity() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(""))
        val error = assertFailsWith<CoreError> {
            DouyuPassport.refreshPlayCookie("didval", "ltpval", mockClient())
        }
        assertTrue(error.message.orEmpty().contains("续期失败"), "实际: ${error.message}")
    }

    @Test
    fun refreshPlayCookie_requiresCredentials() = runBlocking {
        assertFailsWith<CoreError> {
            DouyuPassport.refreshPlayCookie("", "ltpval", mockClient())
        }
        assertFailsWith<CoreError> {
            DouyuPassport.refreshPlayCookie("didval", "", mockClient())
        }
        Unit
    }

    @Test
    fun refreshPlayCookie_rejectsRedirectOutsideDouyu() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", "https://evil.example.com/x"),
        )
        val error = assertFailsWith<CoreError> {
            DouyuPassport.refreshPlayCookie("didval", "ltpval", mockClient())
        }
        assertTrue(error.message.orEmpty().contains("地址无效"), "实际: ${error.message}")
    }

    private companion object {
        const val kDid = "d93d3b8561996f428c2da2fd00081701"
        const val kCode = "362c552c9c56b10201edd89474bc7d32"
        const val kToken = "header.payload.sig"
    }
}
