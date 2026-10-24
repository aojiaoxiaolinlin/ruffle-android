package rs.ruffle

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Uses the WebView's 4399 cookies to obtain the same opaque game ticket the
 * password flow gets. The QQ code itself is never handled here; 4399 already
 * exchanged it server-side.
 */
internal class KabuQqTicketClient(
    private val userAgent: String,
    private val cookieHeader: String,
    private val host: String = "ptlogin.4399.com"
) {
    fun fetch(): String {
        val cookie = cookieHeader.replace("\r", "").replace("\n", "").trim()
        if (cookie.isEmpty()) {
            throw KabuLoginException("QQ 登录状态无效，请重新登录")
        }
        val url = KabuLoginClient.checkKidLoginUrl(host)
        val connection = try {
            URI(url).toURL().openConnection() as HttpURLConnection
        } catch (_: Exception) {
            throw KabuLoginException("获取游戏登录凭据失败，请稍后重试")
        }
        try {
            connection.connectTimeout = 25_000
            connection.readTimeout = 25_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"
            connection.setRequestProperty(
                "User-Agent",
                userAgent.ifBlank { "Mozilla/5.0" }
            )
            connection.setRequestProperty("Cookie", cookie)
            connection.setRequestProperty("Referer", "http://www.4399.com/flash/48399.htm")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")

            val status = connection.responseCode
            val location = connection.getHeaderField("Location")
            if (status in 300..399 && !location.isNullOrBlank()) {
                val resolved = URI(url).resolve(location).toString()
                return try {
                    KabuLoginClient.authFromRedirect(resolved)
                } catch (_: KabuLoginException) {
                    throw KabuLoginException("QQ 登录未完成或已过期，请重新登录")
                }
            }
            if (status == 200) {
                throw KabuLoginException("QQ 登录状态无效，请重新登录")
            }
            throw KabuLoginException("获取游戏登录凭据失败（HTTP $status），请稍后重试")
        } catch (failure: KabuLoginException) {
            throw failure
        } catch (failure: IOException) {
            throw KabuLoginException(KabuLoginClient.connectionProblem(failure))
        } finally {
            connection.disconnect()
        }
    }
}
