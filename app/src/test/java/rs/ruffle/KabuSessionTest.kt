package rs.ruffle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KabuSessionTest {
    @Test
    fun preservesOpaqueTicketsWithoutInventingAnExpiry() {
        val auth = "uid=123&username=test&sig=a%2Bb%3Dc&time=1&other=a+b%26c"
        assertEquals(auth, KabuSession.parse(auth))
    }

    @Test
    fun rejectsMissingSignatureAndFragmentTruncation() {
        for (auth in listOf("", "uid=123", "sig=", "sig=abc#fragment", "sig=abc\n")) {
            assertThrows(Exception::class.java) { KabuSession.parse(auth) }
        }
    }

    @Test
    fun recognizesOnlyTheOfficialReturnToLoginCommands() {
        for (url in listOf(
            "http://www.4399.com/flash/48399.htm",
            "https://enter.wanwan4399.com/bin-debug/KBgameindex.html",
            "javascript:window.parent.location.href='http://www.4399.com/flash/48399.htm'",
            "javascript:window.parent.location.href=\"http://enter.wanwan4399.com/bin-debug/KBgameindex.html\";"
        )) {
            assertTrue(url, KabuSession.isLoginUrl(url))
        }
    }

    @Test
    fun unrelatedNavigationDoesNotDiscardTheSession() {
        for (url in listOf(
            KabuLoginClient.GAME_SWF,
            "http://enter.wanwan4399.com/bin-debug/KBgameindex.html?sig=valid",
            "http://cz.4399.com/kbxy/",
            "http://www.4399.com.evil.test/flash/48399.htm",
            "http://www.4399.com@evil.test/flash/48399.htm",
            "http://www.4399.com:8080/flash/48399.htm",
            "javascript:alert('expired')",
            "javascript:window.parent.location.href='http://www.4399.com/flash/48399.htm';evil()",
            "not a URL"
        )) {
            assertFalse(url, KabuSession.isLoginUrl(url))
        }
    }
}
