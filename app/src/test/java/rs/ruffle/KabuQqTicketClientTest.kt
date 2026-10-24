package rs.ruffle

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KabuQqTicketClientTest {
    @Test
    fun sendsWebViewCookiesAndPreservesTheRawTicketQuery() {
        val server = ServerSocket(0).apply { soTimeout = 5_000 }
        var request = ""
        val thread = Thread {
            server.accept().use { socket ->
                request = readRequest(socket.getInputStream())
                val response = "HTTP/1.1 302 Found\r\n" +
                    "Location: http://enter.wanwan4399.com/bin-debug/" +
                    "KBgameindex.html?user=a%26b&sig=abc%2B1\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(response.toByteArray())
                socket.getOutputStream().flush()
            }
        }
        thread.start()
        try {
            val auth = KabuQqTicketClient(
                userAgent = "test-UA",
                cookieHeader = "Pauth=abc; Puser=name",
                host = "127.0.0.1:${server.localPort}"
            ).fetch()
            thread.join(5_000)
            assertEquals("user=a%26b&sig=abc%2B1", auth)
            assertTrue(request.contains("GET /ptlogin/checkKidLoginUserCookie.do?"))
            assertTrue(request.contains("Cookie: Pauth=abc; Puser=name"))
            assertTrue(request.contains("User-Agent: test-UA"))
            assertTrue(request.contains("Referer: http://www.4399.com/flash/48399.htm"))
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsAnUnauthenticatedResponse() {
        val server = ServerSocket(0).apply { soTimeout = 5_000 }
        val thread = Thread {
            server.accept().use { socket ->
                readRequest(socket.getInputStream())
                val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Length: 0\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().write(response.toByteArray())
                socket.getOutputStream().flush()
            }
        }
        thread.start()
        try {
            KabuQqTicketClient(
                userAgent = "test-UA",
                cookieHeader = "Pauth=abc",
                host = "127.0.0.1:${server.localPort}"
            ).fetch()
            fail("expected KabuLoginException")
        } catch (failure: KabuLoginException) {
            assertTrue(failure.message.orEmpty().contains("状态无效"))
        } finally {
            server.close()
        }
    }

    private fun readRequest(input: java.io.InputStream): String {
        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        return buildString {
            while (true) {
                val line = reader.readLine() ?: break
                appendLine(line)
                if (line.isEmpty()) break
            }
        }
    }
}
