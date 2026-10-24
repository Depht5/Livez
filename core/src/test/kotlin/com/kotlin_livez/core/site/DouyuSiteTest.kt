package com.kotlin_livez.core.site

import com.kotlin_livez.core.scripts.DouyuSign
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 斗鱼设备身份（签名 `did` 与请求头 Cookie 的 `dy_did`/`acf_did`）回归测试。
 *
 * 注意本仓既有教训（抖音 Rhino 条目）：只测纯 Kotlin 工具函数、不真正执行 JS 的测试会
 * "全绿但线上全挂"，因此这里用合成 crptext 真跑 [DouyuSign.getSign] 的 Rhino + CryptoJS
 * 路径。线上 crptext 由 `swf_api/homeH5Enc` 动态下发（无法入库固定），合成脚本保持与线上
 * 相同形态：顶层 `ub98484234(rid, did, time)`，且签名值取自内置 CryptoJS 的 MD5。
 */
class DouyuSiteTest {

    /* ------------------------------- DouyuSign ------------------------------- */

    /**
     * 合成 crptext：输出只依赖 `rid`/`did`（**不含 `time`**）。
     * 这样"自定义 did 与默认 did 的签名不同"才是"did 确实传进 JS"的有效断言
     * （若输出含秒级时间戳，任何两次调用都不同，断言会变成永真）。
     */
    private val fakeCrptext = """
        function ub98484234(rid, did, time) {
            return 'v=2201224&rid=' + rid + '&did=' + did +
                '&sign=' + CryptoJS.MD5(rid + '|' + did).toString(CryptoJS.enc.Hex);
        }
    """.trimIndent()

    @Test
    fun getSign_withoutDid_reallyRunsJsAndFallsBackToDefaultDid() {
        val sign = DouyuSign.getSign(fakeCrptext, kRoomId)
        assertTrue(sign.isNotEmpty(), "签名不应为空（Rhino/JS 执行失败时会返回空串），实际: $sign")
        assertTrue(sign.contains("rid=$kRoomId"), "签名应带回房间号，实际: $sign")
        assertTrue(sign.contains("did=$kDefaultDid"), "缺省 did 应回退默认值，实际: $sign")
        assertTrue(
            kSignatureSign.containsMatchIn(sign),
            "签名片段应为 32 位小写 hex（证明 CryptoJS 真的执行了），实际: $sign",
        )
    }

    @Test
    fun getSign_withCustomDid_producesDifferentSignature() {
        val defaultSign = DouyuSign.getSign(fakeCrptext, kRoomId)
        val customSign = DouyuSign.getSign(fakeCrptext, kRoomId, kCustomDid)
        assertTrue(customSign.contains("did=$kCustomDid"), "自定义 did 应传入 JS，实际: $customSign")
        assertNotEquals(
            defaultSign,
            customSign,
            "did 不同时签名必须不同（相同则说明 did 未真正参与签名）",
        )
    }

    @Test
    fun getSign_blankDid_fallsBackToDefaultDid() {
        assertEquals(
            DouyuSign.getSign(fakeCrptext, kRoomId),
            DouyuSign.getSign(fakeCrptext, kRoomId, "   "),
        )
    }

    @Test
    fun getSign_trimsDidWhitespace() {
        assertEquals(
            DouyuSign.getSign(fakeCrptext, kRoomId, kCustomDid),
            DouyuSign.getSign(fakeCrptext, kRoomId, "  $kCustomDid  "),
        )
    }

    /* --------------------------- DouyuSite.cookie 解析 --------------------------- */

    @Test
    fun cookieValue_extractsTrimmedValueAndSkipsMalformedSegments() {
        val site = DouyuSite()
        site.cookie = " a=1 ; acf_did=xyz ; novalue; =broken; b=2"
        assertEquals("1", site.cookieValue("a"))
        assertEquals("xyz", site.cookieValue("acf_did"))
        assertEquals("2", site.cookieValue("b"))
        assertEquals("", site.cookieValue("missing"))

        site.cookie = ""
        assertEquals("", site.cookieValue("acf_did"))
    }

    /* ---------------------------- deviceDid 三级优先级 ---------------------------- */

    @Test
    fun deviceDid_prefersCookieAcfDid() {
        val site = DouyuSite()
        try {
            site.cookie = "dy_did=cookiedy; acf_did=cookieacf"
            site.deviceId = kCustomDid
            assertEquals("cookieacf", site.deviceDid, "Cookie 的 acf_did 优先级最高")
        } finally {
            site.deviceId = ""
        }
    }

    @Test
    fun deviceDid_usesInjectedDeviceIdWhenCookieHasNoAcfDid() {
        val site = DouyuSite()
        try {
            site.cookie = "dy_did=cookiedy"
            site.deviceId = kCustomDid
            assertEquals(kCustomDid, site.deviceDid, "Cookie 无 acf_did 时应退回注入的 deviceId")
        } finally {
            site.deviceId = ""
        }
    }

    @Test
    fun deviceDid_trimsInjectedDeviceId() {
        val site = DouyuSite()
        try {
            site.cookie = ""
            site.deviceId = "  $kCustomDid  "
            assertEquals(kCustomDid, site.deviceDid)
        } finally {
            site.deviceId = ""
        }
    }

    @Test
    fun deviceDid_fallsBackToProcessFallbackDid() {
        val site = DouyuSite()
        try {
            site.cookie = ""
            site.deviceId = ""
            val did = site.deviceDid
            assertTrue(kHex32.matches(did), "兜底设备号应为 32 位小写 hex，实际: $did")
            assertEquals(did, site.deviceDid, "进程内兜底设备号只初始化一次，多次读取应稳定")
        } finally {
            site.deviceId = ""
        }
    }

    /* ------------------------- deviceCookieHeader 组装 ------------------------- */

    @Test
    fun deviceCookieHeader_anonymousAddsBothDids() {
        val site = DouyuSite()
        try {
            site.cookie = ""
            site.deviceId = ""
            val did = site.deviceDid
            assertEquals("dy_did=$did; acf_did=$did", site.deviceCookieHeader)
        } finally {
            site.deviceId = ""
        }
    }

    @Test
    fun deviceCookieHeader_usesInjectedDeviceIdWhenAnonymous() {
        val site = DouyuSite()
        try {
            site.cookie = "sessionid=abc"
            site.deviceId = kCustomDid
            assertEquals(
                "sessionid=abc; dy_did=$kCustomDid; acf_did=$kCustomDid",
                site.deviceCookieHeader,
                "用户 Cookie 必须保留，缺失的 dy_did/acf_did 用注入设备号补齐",
            )
        } finally {
            site.deviceId = ""
        }
    }

    @Test
    fun deviceCookieHeader_keepsUserCookieAndDoesNotDuplicateDids() {
        val site = DouyuSite()
        try {
            site.cookie = "dy_did=userdy; acf_did=useracf; sessionid=s"
            site.deviceId = kCustomDid
            val header = site.deviceCookieHeader
            assertEquals("dy_did=userdy; acf_did=useracf; sessionid=s", header)
            assertEquals(1, Regex("dy_did=").findAll(header).count(), "dy_did 不应重复追加")
            assertEquals(1, Regex("acf_did=").findAll(header).count(), "acf_did 不应重复追加")
        } finally {
            site.deviceId = ""
        }
    }

    @Test
    fun deviceCookieHeader_trimsTrailingSemicolonOfUserCookie() {
        val site = DouyuSite()
        try {
            site.cookie = "  dy_did=userdy; acf_did=useracf;  "
            site.deviceId = ""
            assertEquals("dy_did=userdy; acf_did=useracf", site.deviceCookieHeader)
        } finally {
            site.deviceId = ""
        }
    }

    @Test
    fun deviceCookieHeader_reusesCookieAcfDidSoHeaderMatchesSignatureDid() {
        val site = DouyuSite()
        try {
            site.cookie = "acf_did=cookieacf"
            site.deviceId = kCustomDid
            assertEquals("cookieacf", site.deviceDid, "签名 did 取 Cookie 的 acf_did")
            assertEquals(
                "acf_did=cookieacf; dy_did=cookieacf",
                site.deviceCookieHeader,
                "缺 dy_did 时用同一设备号补齐，保证 Cookie 与签名 did 同源",
            )
        } finally {
            site.deviceId = ""
        }
    }

    @Test
    fun deviceCookieHeader_addsOnlyMissingAcfDidWhenCookieHasDyDid() {
        val site = DouyuSite()
        try {
            site.cookie = "dy_did=userdy"
            site.deviceId = kCustomDid
            assertEquals("dy_did=userdy; acf_did=$kCustomDid", site.deviceCookieHeader)
        } finally {
            site.deviceId = ""
        }
    }

    /* ---------------------------- searchHeaders 形态 ---------------------------- */

    /**
     * 搜索请求头必须保持原形态（Edg/114 UA + 搜索页 referer），只把随机 did 的 Cookie
     * 换成设备身份；同时必须只有一个小写敏感意义上的 UA 键 —— 与 `DouyuSite.requestHeaders()`
     * （小写 `"user-agent"`）合并会产生两个 UA 头，这正是本用例要钉住的坑。
     * `searchHeaders()` 是 private，按本仓既有做法（如 TarsHttpClientTest 反射调
     * `tupResponseDecode`）用反射断言。
     */
    @Test
    @Suppress("UNCHECKED_CAST")
    fun searchHeaders_keepsSearchUaAndRefererWithDeviceCookie() {
        val site = DouyuSite()
        try {
            site.cookie = ""
            site.deviceId = ""
            val method = DouyuSite::class.java.getDeclaredMethod("searchHeaders")
            method.isAccessible = true
            val headers = method.invoke(site) as Map<String, String>

            assertEquals("https://www.douyu.com/search/", headers["referer"])
            assertTrue(
                headers["User-Agent"]?.endsWith("Edg/114.0.1823.51") == true,
                "搜索 UA 应保持搜索专属的 Edg/114，实际: ${headers["User-Agent"]}",
            )
            assertEquals(site.deviceCookieHeader, headers["Cookie"])
            assertEquals("dy_did=${site.deviceDid}; acf_did=${site.deviceDid}", headers["Cookie"])
            assertFalse(headers.containsKey("user-agent"), "不应混入小写的 user-agent 键")
            assertEquals(3, headers.size, "搜索头应恰好 3 个键，实际: $headers")
        } finally {
            site.deviceId = ""
        }
    }

    /* --------------------------- ensureFreshCookie 续期 --------------------------- */

    /** 构造只带 exp 的假 JWT（与 DouyuPassport.isCookieExpired 的解析形态一致）。 */
    private fun jwtWithExp(expSeconds: Long): String {
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"exp":$expSeconds}""".toByteArray())
        return "eyJhbGciOiJIUzI1NiJ9.$payload.sig"
    }

    @Test
    fun ensureFreshCookie_skipsWhenAnonymous() = kotlinx.coroutines.runBlocking {
        val site = DouyuSite()
        var called = 0
        site.onCookieExpired = { called++; "acf_uid=1" }
        site.cookie = ""
        site.ensureFreshCookie()
        assertEquals(0, called, "匿名态（无 Cookie）不应触发续期")
    }

    @Test
    fun ensureFreshCookie_skipsWhenCookieStillValid() = kotlinx.coroutines.runBlocking {
        val site = DouyuSite()
        var called = 0
        site.onCookieExpired = { called++; "acf_uid=1" }
        site.cookie = "acf_jwt_token=${jwtWithExp(System.currentTimeMillis() / 1000 + 3600)}"
        site.ensureFreshCookie()
        assertEquals(0, called, "未过期不应触发续期")
        assertTrue(site.cookie.contains("acf_jwt_token="), "Cookie 应原样保留")
    }

    @Test
    fun ensureFreshCookie_renewsExpiredCookie() = kotlinx.coroutines.runBlocking {
        val site = DouyuSite()
        site.onCookieExpired = { "acf_uid=1001; acf_auth=fresh" }
        site.cookie = "acf_jwt_token=${jwtWithExp(System.currentTimeMillis() / 1000 - 60)}"
        site.ensureFreshCookie()
        assertEquals("acf_uid=1001; acf_auth=fresh", site.cookie, "续期结果应写回 cookie")
    }

    @Test
    fun ensureFreshCookie_keepsOldCookieWhenRenewReturnsNull() = kotlinx.coroutines.runBlocking {
        val site = DouyuSite()
        site.onCookieExpired = { null }
        val old = "acf_jwt_token=${jwtWithExp(System.currentTimeMillis() / 1000 - 60)}"
        site.cookie = old
        site.ensureFreshCookie()
        assertEquals(old, site.cookie, "续期失败必须沿用旧 Cookie（由断流重试兜底）")
    }

    @Test
    fun ensureFreshCookie_swallowsRenewError() = kotlinx.coroutines.runBlocking {
        val site = DouyuSite()
        site.onCookieExpired = { throw IllegalStateException("network down") }
        val old = "acf_jwt_token=${jwtWithExp(System.currentTimeMillis() / 1000 - 60)}"
        site.cookie = old
        site.ensureFreshCookie()
        assertEquals(old, site.cookie, "续期抛错不应中断播放链路，也不应改写 Cookie")
    }

    private companion object {
        const val kRoomId = "288016"
        const val kDefaultDid = "10000000000000000000000000001501"
        const val kCustomDid = "0123456789abcdef0123456789abcdef"
        val kHex32 = Regex("^[0-9a-f]{32}$")
        val kSignatureSign = Regex("sign=[0-9a-f]{32}")
    }
}
