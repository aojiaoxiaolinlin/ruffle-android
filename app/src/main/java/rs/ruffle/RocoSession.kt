package rs.ruffle

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Validates and serializes a session without logging its tickets. */
object RocoSession {
    const val BASE_URL = "https://res.17roco.qq.com/"
    const val GAME_SWF = "${BASE_URL}main.swf"
    private val tickets = listOf("angel_uin", "angel_key", "skey", "pskey")

    fun isLoginUrl(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        uri.scheme in listOf("https", "http") &&
            (
                uri.host == "17roco.qq.com" &&
                    uri.path in listOf("/login.html", "/logintarget.html") ||
                    uri.host == "web2.17roco.qq.com" &&
                    uri.path == "/fcgi-bin/login3"
                )
    }.getOrDefault(false)

    /** Works for object/embed markup and the official template's JS string fragments. */
    fun fromHtml(html: String): String? {
        val source = html.replace("&amp;", "&", ignoreCase = true)
            .replace("\\x26", "&", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
        val fields = Regex(
            """(?:[&?]|^)(angel_uin|angel_key|skey|pskey)=([^&"'<>\s]*)"""
        ).findAll(source).associate { it.groupValues[1] to it.groupValues[2] }
        val raw = tickets.joinToString("&") { "$it=${fields[it].orEmpty()}" }
        return runCatching { parse(raw) }.getOrNull()
    }

    fun parse(raw: String): String {
        val values = raw.split('&').associate { entry ->
            val parts = entry.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to
                URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
        require(
            tickets.all { key ->
                val value = values[key]
                !value.isNullOrBlank() && !value.contains("{{") && value.length <= 16384
            }
        ) { "未取得完整游戏登录参数" }
        return (
            mapOf("config" to "${BASE_URL}Global.xml") +
                tickets.associateWith { values.getValue(it) }
            )
            .entries.joinToString("&") { (key, value) ->
                "$key=${URLEncoder.encode(value, "UTF-8")}"
            }
    }
}
