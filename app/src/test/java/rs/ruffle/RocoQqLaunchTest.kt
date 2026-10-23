package rs.ruffle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RocoQqLaunchTest {
    private val scheme = "rs.ruffle.roco.local.qqlogin"

    @Test
    fun preservesOfficialQrAndRemovesExternalBrowserReturns() {
        assertEquals(
            "wtloginmqq://ptlogin/qlogin?qrcode=fixture%3Fkey%3Da%2Bb" +
                "&schemacallback=rs.ruffle.roco.local.qqlogin%3A%2F%2F",
            RocoQqLaunch.fromOfficialQr(
                "wtloginmqq://ptlogin/qlogin?qrcode=fixture%3Fkey%3Da%2Bb" +
                    "&schemacallback=googlechrome%3A%2F%2F&p=https%3A%2F%2Fexample.com",
                scheme
            )
        )
    }

    @Test
    fun rejectsBrowserBasedLoginAndUnrelatedSchemes() {
        assertNull(RocoQqLaunch.fromOfficialQr("wtloginmqq://ptlogin/qlogin?p=fixture", scheme))
        assertNull(RocoQqLaunch.fromOfficialQr("wtloginmqq://ptlogin/qlogin?qrcode=", scheme))
        assertNull(RocoQqLaunch.fromOfficialQr("other://ptlogin/qlogin?qrcode=fixture", scheme))
        assertNull(RocoQqLaunch.fromOfficialQr("wtloginmqq://other/qlogin?qrcode=fixture", scheme))
    }
}
