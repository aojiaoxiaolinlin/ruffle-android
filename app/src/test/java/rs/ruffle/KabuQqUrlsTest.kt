package rs.ruffle

import java.net.URI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KabuQqUrlsTest {
    @Test
    fun recognizesOnlyTheOfficialLoginAndCallbackPages() {
        assertTrue(
            KabuQqUrls.isLoginPage(
                URI("http://news.4399.com/login/kbxy.html?reg=0&pass=1")
            )
        )
        assertFalse(
            KabuQqUrls.isLoginPage(URI("http://news.4399.com/login/other.html"))
        )
        assertTrue(
            KabuQqUrls.isCallbackPage(
                URI("https://extlogin.4399.com/qzone/callback.do?code=x&state=y")
            )
        )
        assertFalse(
            KabuQqUrls.isCallbackPage(
                URI("https://extlogin.4399.com/qzone/other.do")
            )
        )
        assertTrue(KabuQqUrls.isQqPage(URI("https://graph.qq.com/oauth2.0/authorize")))
        assertTrue(KabuQqUrls.isQqPage(URI("https://xui.ptlogin2.qq.com/cgi-bin/xlogin")))
        assertFalse(KabuQqUrls.isQqPage(URI("https://example.com/")))
    }

    @Test
    fun parsesPauthExactly() {
        assertTrue(KabuQqUrls.hasPauth("USESSIONID=1; Pauth=abc; Puser=name"))
        assertTrue(KabuQqUrls.hasPauth("Pauth=abc"))
        assertFalse(KabuQqUrls.hasPauth("XPauth=abc"))
        assertFalse(KabuQqUrls.hasPauth("Pauth="))
        assertFalse(KabuQqUrls.hasPauth("Pauth = "))
        assertFalse(KabuQqUrls.hasPauth(null))
        assertFalse(KabuQqUrls.hasPauth(""))
    }

    @Test
    fun callbackSchemeFollowsThePackage() {
        assertTrue(
            KabuQqUrls.isCallback(
                URI("rs.ruffle.kabu.local.qqlogin://return"),
                "rs.ruffle.kabu.local"
            )
        )
        assertFalse(
            KabuQqUrls.isCallback(
                URI("rs.ruffle.kabu.local.qqlogin://return"),
                "rs.ruffle.kabu"
            )
        )
    }
}
