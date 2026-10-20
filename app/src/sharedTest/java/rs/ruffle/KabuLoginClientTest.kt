package rs.ruffle

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KabuLoginClientTest {
    @Test
    fun accepts4399ParentDomainCookieAndRejectsUnrelatedDomains() {
        val cookies = KabuLoginClient.createCookieManager()
        val uri = URI("http://ptlogin.4399.com/ptlogin/loginFrame.do")
        cookies.put(
            uri,
            mapOf(
                "Set-Cookie" to listOf(
                    "session=mock; Path=/; Domain=4399.com",
                    "unrelated=mock; Path=/; Domain=example.com"
                )
            )
        )
        val header = cookies.get(uri, emptyMap()).values.flatten().joinToString("; ")
        assertTrue(header.contains("session=mock"))
        assertFalse(header.contains("unrelated="))
        val unrelated = cookies.get(URI("http://example.com/"), emptyMap()).values.flatten()
        assertFalse(unrelated.any { it.contains("session=mock") })
    }

    @Test
    fun encryptionMatchesIndependentOpenSslVector() {
        assertEquals(
            "U2FsdGVkX18AAQIDBAUGBx3fzKjTUFY6GXZTEb7Qsro=",
            KabuLoginClient.encryptPassword("test-password", ByteArray(8) { it.toByte() })
        )
    }

    @Test
    fun preservesOpaqueAuthQueryWithoutDecodingOrDoubleEncoding() {
        val query = "user=test&sig=abc%2Bdef%3D&other=a+b%26c"
        assertEquals(
            query,
            KabuLoginClient.authFromRedirect("${KabuLoginClient.BASE_URL}KBgameindex.html?$query")
        )
    }

    @Test
    fun rejectsLoginPageOrUntrustedRedirect() {
        for (location in listOf(
            "http://example.com/bin-debug/KBgameindex.html?sig=abc",
            "${KabuLoginClient.BASE_URL}KBgameindex.html?sig=",
            "${KabuLoginClient.BASE_URL}KBgameindex.html?user=test",
            "${KabuLoginClient.BASE_URL}other.html?sig=abc",
            "not a URL"
        )) {
            assertThrows(KabuLoginException::class.java) {
                KabuLoginClient.authFromRedirect(location)
            }
        }
    }

    @Test
    fun rejectsEmptyCredentialsBeforeNetworking() {
        assertThrows(IllegalArgumentException::class.java) {
            KabuLoginClient(emptyList()).login(" ", "test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            KabuLoginClient(emptyList()).login("test", "")
        }
    }

    @Test
    fun completesProtocolWithSessionCookieAndEncryptedForm() {
        FakeLoginServer().use { server ->
            val result = KabuLoginClient(listOf(server.host)).login("test+账号", "test-password")
            assertEquals("user=test&sig=mock%2Bsignature%3D", result)
            val login = server.requests.single { it.path.startsWith("/ptlogin/login.do") }
            assertTrue(login.headers["cookie"].orEmpty().contains("sid=mock"))
            assertTrue(
                login.headers["referer"].orEmpty().startsWith(
                    "http://${server.host}/ptlogin/loginFrame.do"
                )
            )
            assertEquals("http://${server.host}", login.headers["origin"])
            val fields = login.body.split('&').associate {
                val parts = it.split('=', limit = 2)
                URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
            }
            assertEquals("test+账号", fields["username"])
            assertTrue(fields["password"].orEmpty().startsWith("U2FsdGVkX1"))
            assertFalse(login.body.contains("test-password"))
            val check = server.requests.single {
                it.path.startsWith("/ptlogin/checkKidLoginUserCookie.do")
            }
            assertTrue(check.headers["cookie"].orEmpty().contains("sid=mock"))
            assertEquals(4, server.requests.size)
        }
    }

    @Test
    fun stopsOnRejectedCredentialsAndDoesNotExposePassword() {
        FakeLoginServer(loginStatus = 401).use { server ->
            val error = assertThrows(KabuLoginException::class.java) {
                KabuLoginClient(listOf(server.host)).login("test", "private-test-password")
            }
            assertFalse(error.message.orEmpty().contains("private-test-password"))
            assertTrue(error.message.orEmpty().contains("401"))
            assertEquals(3, server.requests.size)
            assertTrue(
                server.requests.none {
                    it.path.startsWith("/ptlogin/checkKidLoginUserCookie.do")
                }
            )
        }
    }

    private data class Request(val path: String, val headers: Map<String, String>, val body: String)
    private data class Reply(val status: Int, val body: String, val headers: String = "")

    private class FakeLoginServer(private val loginStatus: Int = 200) : AutoCloseable {
        private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val failure = AtomicReference<Throwable?>()
        val host = "127.0.0.1:${socket.localPort}"
        val requests = CopyOnWriteArrayList<Request>()
        private val worker = Thread {
            try {
                while (!socket.isClosed) {
                    socket.accept().use { client ->
                        client.soTimeout = 5000
                        val reader = client.getInputStream().bufferedReader(Charsets.UTF_8)
                        val path = reader.readLine().split(' ')[1]
                        val headers = mutableMapOf<String, String>()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            val colon = line.indexOf(':')
                            headers[line.substring(0, colon).lowercase()] =
                                line.substring(colon + 1).trim()
                        }
                        val chars = CharArray(headers["content-length"]?.toInt() ?: 0)
                        var offset = 0
                        while (offset < chars.size) {
                            val count = reader.read(chars, offset, chars.size - offset)
                            check(count > 0)
                            offset += count
                        }
                        requests.add(Request(path, headers, String(chars)))
                        val reply = when {
                            path.startsWith("/ptlogin/loginFrame.do") -> Reply(
                                200,
                                "<form id=\"login_form\"></form>",
                                "Set-Cookie: sid=mock; Path=/ptlogin\r\n"
                            )
                            path.startsWith(
                                "/ptlogin/login.do"
                            ) -> Reply(loginStatus, "test response")
                            path.startsWith("/ptlogin/checkKidLoginUserCookie.do") -> Reply(
                                302,
                                "",
                                "Location: ${KabuLoginClient.BASE_URL}KBgameindex.html?" +
                                    "user=test&sig=mock%2Bsignature%3D\r\n"
                            )
                            else -> error("Unexpected request path")
                        }
                        val response = "HTTP/1.1 ${reply.status} Test\r\n${reply.headers}" +
                            "Content-Length: ${reply.body.toByteArray().size}\r\n" +
                            "Connection: close\r\n\r\n${reply.body}"
                        client.getOutputStream().write(response.toByteArray())
                    }
                }
            } catch (error: Throwable) {
                if (error !is SocketException || !socket.isClosed) failure.set(error)
            }
        }.apply {
            isDaemon = true
            start()
        }

        override fun close() {
            socket.close()
            worker.join(5000)
            failure.get()?.let { throw AssertionError("Mock server failed", it) }
        }
    }
}
