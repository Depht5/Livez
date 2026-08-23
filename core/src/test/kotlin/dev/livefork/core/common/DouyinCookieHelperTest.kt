package dev.livefork.core.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DouyinCookieHelperTest {

    @Test
    fun extractTtwid_simpleCookie() {
        assertEquals("ttwid=abc", DouyinCookieHelper.extractTtwid("ttwid=abc"))
    }

    @Test
    fun extractTtwid_fullHeaderLine() {
        val header = "Cookie: ttwid=abc; sessionid=xyz"
        assertEquals("ttwid=abc", DouyinCookieHelper.extractTtwid(header))
    }

    @Test
    fun extractTtwid_multipart() {
        assertEquals("ttwid=abc", DouyinCookieHelper.extractTtwid("sessionid=xyz; ttwid=abc; odin=1"))
    }

    @Test
    fun extractTtwid_absentReturnsNull() {
        assertNull(DouyinCookieHelper.extractTtwid("sessionid=xyz"))
        assertNull(DouyinCookieHelper.extractTtwid(""))
    }

    @Test
    fun isOnlyTtwid_detectsBareTtwid() {
        assertTrue(DouyinCookieHelper.isOnlyTtwid("ttwid=abc"))
        assertFalse(DouyinCookieHelper.isOnlyTtwid("ttwid=abc; sessionid=xyz"))
        assertFalse(DouyinCookieHelper.isOnlyTtwid("sessionid=xyz"))
    }

    @Test
    fun hasFullCookie_invertsOnlyTtwid() {
        assertTrue(DouyinCookieHelper.hasFullCookie("ttwid=abc; sessionid=xyz"))
        assertFalse(DouyinCookieHelper.hasFullCookie("ttwid=abc"))
        assertFalse(DouyinCookieHelper.hasFullCookie(""))
    }

    @Test
    fun normalizeInput_addsTtwidPrefixWhenBare() {
        assertEquals("ttwid=abc", DouyinCookieHelper.normalizeInput("abc"))
        assertEquals("ttwid=abc", DouyinCookieHelper.normalizeInput("ttwid=abc"))
        assertEquals("ttwid=abc", DouyinCookieHelper.normalizeInput("Cookie: ttwid=abc"))
    }

    @Test
    fun cookieCompletenessHint_returnsChineseHints() {
        assertTrue(DouyinCookieHelper.cookieCompletenessHint("").contains("未配置"))
        assertTrue(DouyinCookieHelper.cookieCompletenessHint("ttwid=abc").contains("ttwid"))
        assertTrue(DouyinCookieHelper.cookieCompletenessHint("sessionid=abc").contains("登录态"))
    }
}