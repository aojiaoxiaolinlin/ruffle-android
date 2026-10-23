package rs.ruffle

import java.net.URI

/** The auth query is opaque: preserve its escaping and let the game decide expiry. */
internal object KabuSession {
    const val REAUTHORIZE = "reauthorize"
    const val EXPIRED = "sessionExpired"

    fun parse(raw: String): String {
        require(raw.isNotBlank() && raw.length <= 65536) { "未取得有效游戏凭据" }
        val validated = KabuLoginClient.authFromRedirect(
            "${KabuLoginClient.BASE_URL}KBgameindex.html?$raw"
        )
        require(validated == raw) { "游戏凭据格式无效" }
        return raw
    }

    /** Recognize the game's fixed return-to-login destinations; never execute script. */
    fun isLoginUrl(raw: String): Boolean = runCatching {
        val destination = if (raw.startsWith("javascript:")) {
            Regex("""^javascript:window\.parent\.location\.href=(['"])([^'"]+)\1;?$""")
                .matchEntire(raw)?.groupValues?.get(2) ?: return false
        } else {
            raw
        }
        val uri = URI(destination)
        uri.scheme in listOf("http", "https") &&
            uri.userInfo == null &&
            uri.port in listOf(-1, 80, 443) &&
            uri.rawQuery.isNullOrEmpty() &&
            uri.rawFragment == null &&
            (
                uri.host == "enter.wanwan4399.com" &&
                    uri.path == "/bin-debug/KBgameindex.html" ||
                    uri.host == "www.4399.com" &&
                    uri.path == "/flash/48399.htm"
                )
    }.getOrDefault(false)
}
