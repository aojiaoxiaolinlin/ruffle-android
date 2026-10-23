package rs.ruffle

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset

/** Exchange once using the WebView's UA and cookie jar; never log codes, HTML or tickets. */
internal class RocoLoginClient(
    private val userAgent: String,
    private val getCookie: (String) -> String?,
    private val setCookie: (String, String) -> Unit
) {
    data class Result(val vars: String?, val url: String, val html: String)

    fun exchange(code: String): Result {
        var uri = URI(
            "https://web2.17roco.qq.com/fcgi-bin/login3?code=" +
                URLEncoder.encode(code, "UTF-8") + "&platfrom_src=2"
        )
        repeat(6) {
            require(uri.scheme == "https" && uri.host in GAME_HOSTS)
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 20_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("User-Agent", userAgent)
                connection.setRequestProperty("Referer", "https://17roco.qq.com/logintarget.html")
                getCookie(uri.toString())?.let { connection.setRequestProperty("Cookie", it) }
                val status = connection.responseCode
                connection.headerFields.forEach { (name, values) ->
                    if (name.equals("Set-Cookie", ignoreCase = true)) {
                        values.forEach { setCookie(uri.toString(), it) }
                    }
                }
                if (status in listOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException()
                    val next = uri.resolve(location)
                    uri = if (next.scheme == "http" && next.host in GAME_HOSTS) {
                        URI("https" + next.toString().removePrefix("http"))
                    } else {
                        next
                    }
                } else {
                    if (status !in 200..299) throw IOException()
                    val charset = Regex("charset=([^;\\s]+)", RegexOption.IGNORE_CASE)
                        .find(connection.contentType.orEmpty())?.groupValues?.get(1)
                        ?.trim('"', '\'')?.let {
                            runCatching { Charset.forName(it) }.getOrNull()
                        } ?: Charset.forName("GB18030")
                    val bytes = connection.inputStream.use { stream ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > 2 * 1024 * 1024) throw IOException()
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    if (bytes.size > 2 * 1024 * 1024) throw IOException()
                    val html = bytes.toString(charset)
                    // Use a query-free base URL for the rendering fallback.
                    val base = URI(uri.scheme, uri.authority, uri.path, null, null).toString()
                    return Result(RocoSession.fromHtml(html), base, html)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException()
    }

    companion object {
        private val GAME_HOSTS = setOf("17roco.qq.com", "web2.17roco.qq.com")
    }
}
