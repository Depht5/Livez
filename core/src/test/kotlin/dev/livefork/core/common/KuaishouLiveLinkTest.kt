package dev.livefork.core.common

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KuaishouLiveLinkTest {

    @Test
    fun publicRoomUri_buildsLiveUrl() {
        val uri = KuaishouLiveLink.publicRoomUri("abc123")
        assertNotNull(uri)
        assertEquals("https://live.kuaishou.com/u/abc123", uri.toString())
    }

    @Test
    fun publicRoomUri_rejectsInvalidRoomId() {
        assertNull(KuaishouLiveLink.publicRoomUri("bad room id"))
        assertNull(KuaishouLiveLink.publicRoomUri(""))
    }

    @Test
    fun parseHttpUrl_acceptsKnownHosts() {
        assertNotNull(KuaishouLiveLink.parseHttpUrl("https://live.kuaishou.com/u/abc123"))
        assertNotNull(KuaishouLiveLink.parseHttpUrl("https://v.kuaishou.com/123"))
        assertNotNull(KuaishouLiveLink.parseHttpUrl("https://m.chenzhongtech.com/fw/live/123"))
        assertNotNull(KuaishouLiveLink.parseHttpUrl("https://xyz.m.chenzhongtech.com/fw/live/123"))
    }

    @Test
    fun parseHttpUrl_rejectsUnknownHostAndBareText() {
        assertNull(KuaishouLiveLink.parseHttpUrl("https://example.com/u/abc"))
        assertNull(KuaishouLiveLink.parseHttpUrl("not a url"))
    }

    @Test
    fun roomIdFromUri_liveHostTwoSegments() {
        val uri = URI("https://live.kuaishou.com/u/abc123")
        assertEquals("abc123", KuaishouLiveLink.roomIdFromUri(uri))
    }

    @Test
    fun roomIdFromUri_mobileHostThreeSegments() {
        val uri = URI("https://m.chenzhongtech.com/fw/live/def456")
        assertEquals("def456", KuaishouLiveLink.roomIdFromUri(uri))
    }

    @Test
    fun roomIdFromUri_wrongShapeReturnsNull() {
        assertNull(KuaishouLiveLink.roomIdFromUri(URI("https://live.kuaishou.com/u")))
        assertNull(KuaishouLiveLink.roomIdFromUri(URI("https://m.chenzhongtech.com/fw/xyz/123")))
    }

    @Test
    fun isShortLink_detectsShortHost() {
        assertTrue(KuaishouLiveLink.isShortLink(URI("https://v.kuaishou.com/abc")))
        assertFalse(KuaishouLiveLink.isShortLink(URI("https://live.kuaishou.com/u/abc")))
    }

    @Test
    fun isTrustedRedirectTarget_acceptsShortAndRoomLinks() {
        assertTrue(KuaishouLiveLink.isTrustedRedirectTarget(URI("https://v.kuaishou.com/abc")))
        assertTrue(KuaishouLiveLink.isTrustedRedirectTarget(URI("https://live.kuaishou.com/u/abc")))
        assertFalse(KuaishouLiveLink.isTrustedRedirectTarget(URI("https://example.com/u/abc")))
    }
}