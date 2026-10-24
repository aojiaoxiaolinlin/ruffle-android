package rs.ruffle

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KabuQqLaunchTest {
    private val callback = "rs.ruffle.kabu.qqlogin"

    @Test
    fun rewritesTheOfficialQrFormAndDropsP() {
        val raw = "wtloginmqq://ptlogin/qlogin" +
            "?qrcode=abc%3D123%2Bx&schemacallback=weixin%3A%2F%2F" +
            "&p=https%3A%2F%2Fexample.invalid%2Flogin"
        val result = KabuQqLaunch.fromOfficialQr(raw, callback)
        assertNotNull(result)
        assertTrue(result!!.startsWith("wtloginmqq://ptlogin/qlogin?qrcode="))
        assertTrue(result.contains("qrcode=abc%3D123%2Bx"))
        assertTrue(result.contains("schemacallback=rs.ruffle.kabu.qqlogin%3A%2F%2F"))
        assertFalse(result.contains("p="))
        assertFalse(result.contains("weixin"))
    }

    @Test
    fun rejectsOneKeyPForm() {
        assertNull(
            KabuQqLaunch.fromOfficialQr(
                "wtloginmqq://ptlogin/qlogin?p=https%3A%2F%2Fexample.invalid",
                callback
            )
        )
    }

    @Test
    fun rejectsWrongSchemeHostOrPath() {
        assertNull(KabuQqLaunch.fromOfficialQr("mqqapi://ptlogin/qlogin?qrcode=x", callback))
        assertNull(KabuQqLaunch.fromOfficialQr("wtloginmqq://other/qlogin?qrcode=x", callback))
        assertNull(KabuQqLaunch.fromOfficialQr("wtloginmqq://ptlogin/other?qrcode=x", callback))
    }

    @Test
    fun rejectsEmptyOversizedAndControlQr() {
        assertNull(KabuQqLaunch.fromOfficialQr("wtloginmqq://ptlogin/qlogin?qrcode=", callback))
        assertNull(
            KabuQqLaunch.fromOfficialQr(
                "wtloginmqq://ptlogin/qlogin?qrcode=${"x".repeat(4097)}",
                callback
            )
        )
        assertNull(
            KabuQqLaunch.fromOfficialQr(
                "wtloginmqq://ptlogin/qlogin?qrcode=a%0Ab",
                callback
            )
        )
    }

    @Test
    fun callbackMatchesPackageSchemeOnly() {
        assertTrue(
            KabuQqLaunch.isCallback(
                URI("rs.ruffle.kabu.qqlogin://anything?code=secret"),
                "rs.ruffle.kabu"
            )
        )
        assertFalse(
            KabuQqLaunch.isCallback(
                URI("rs.ruffle.kabu.other://anything"),
                "rs.ruffle.kabu"
            )
        )
    }
}
