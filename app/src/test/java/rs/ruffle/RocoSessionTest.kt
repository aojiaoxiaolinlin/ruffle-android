package rs.ruffle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RocoSessionTest {
    @Test
    fun onlyOfficialLoginRedirectsInvalidateSavedSessions() {
        assertTrue(RocoSession.isLoginUrl("https://17roco.qq.com/login.html"))
        assertTrue(RocoSession.isLoginUrl("http://web2.17roco.qq.com/fcgi-bin/login3"))
        assertFalse(RocoSession.isLoginUrl("https://17roco.qq.com/main.html"))
        assertFalse(RocoSession.isLoginUrl("https://17roco.qq.com.evil.test/login.html"))
        assertFalse(RocoSession.isLoginUrl("javascript:location.reload()"))
    }

    @Test
    fun encodesTicketsWithoutLosingReservedCharacters() {
        val result = RocoSession.parse(
            "angel_uin=123&angel_key=a%2Bb%3Dc&skey=x%26y&pskey=z&config=http://old/"
        )
        assertEquals(
            "config=https%3A%2F%2Fres.17roco.qq.com%2FGlobal.xml" +
                "&angel_uin=123&angel_key=a%2Bb%3Dc&skey=x%26y&pskey=z",
            result
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingTicket() {
        RocoSession.parse("angel_uin=123&angel_key=a&skey=b")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnexpandedServerTemplate() {
        RocoSession.parse("angel_uin={{angel_uin}}&angel_key=a&skey=b&pskey=c")
    }

    @Test
    fun parsesOfficialJavascriptFragmentsBeforeThePageRenders() {
        val html = """
            var swfurl = '<object><param name="FlashVars" value="config=http://old/Global.xml'
              + '&angel_uin=123'
              + '&angel_key=a%2Bb%3Dc'
              + '&skey=x%26y'
              + '&pskey=z" /></object>';
        """.trimIndent()
        assertEquals(
            RocoSession.parse("angel_uin=123&angel_key=a%2Bb%3Dc&skey=x%26y&pskey=z"),
            RocoSession.fromHtml(html)
        )
    }

    @Test
    fun parsesHtmlEntitiesAndRejectsAnIncompleteResponse() {
        assertEquals(
            RocoSession.parse("angel_uin=123&angel_key=a&skey=b&pskey=c"),
            RocoSession.fromHtml(
                "<embed flashvars=\"config=x&amp;angel_uin=123&amp;angel_key=a&amp;skey=b&amp;pskey=c\">"
            )
        )
        org.junit.Assert.assertNull(RocoSession.fromHtml("&angel_uin=123&angel_key=a&skey=b"))
    }

    @Test
    fun recognizesNightProtectionResponses() {
        assertTrue(
            RocoSession.isNightNotice(
                """<object data="//res.17roco.qq.com/swf/night.swf"></object>"""
            )
        )
        assertTrue(
            RocoSession.isNightNotice("", "https://res.17roco.qq.com/swf/night.swf")
        )
        assertFalse(
            RocoSession.isNightNotice(
                """<embed flashvars="angel_uin=1&angel_key=a&skey=b&pskey=c">""",
                "https://web2.17roco.qq.com/fcgi-bin/login3"
            )
        )
    }

    @Test
    fun nightNoticeUsesResourceBaseUrl() {
        assertEquals("https://res.17roco.qq.com/swf/night.swf", RocoSession.NIGHT_SWF)
    }
}
