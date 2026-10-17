package rs.ruffle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseUrlTest {
    @Test
    fun acceptsOptionalAndHttpServerAddresses() {
        val urls = listOf(
            "",
            " ",
            "http://192.168.1.100:8000/game/",
            "http://localhost:8080/",
            "https://example.com/game/",
            "http://[::1]:8000/"
        )
        for (url in urls) {
            assertTrue(url, isBaseUrlValid(url))
        }
    }

    @Test
    fun rejectsRelativeAndUnsupportedAddresses() {
        val urls = listOf(
            "game/",
            "192.168.1.100:8000",
            "ftp://example.com/",
            "file:///game/",
            "http://",
            "https:///game/",
            "http://example.com:65536/",
            "http://example.com/a b"
        )
        for (url in urls) {
            assertFalse(url, isBaseUrlValid(url))
        }
    }
}
