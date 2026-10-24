package rs.ruffle

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Keep QQ authorization inside the original WebView's official QR polling
 * session. The p= form returns the result to an external browser, so it is
 * deliberately dropped.
 */
internal object KabuQqLaunch {
    fun fromOfficialQr(raw: String, callbackScheme: String): String? = runCatching {
        val uri = URI(raw)
        if (uri.scheme != "wtloginmqq" || uri.host != "ptlogin" || uri.path != "/qlogin") {
            return null
        }
        val fields = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            if (part.isBlank()) {
                null
            } else {
                val name = URLDecoder.decode(part.substringBefore('='), "UTF-8")
                val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                name to value
            }
        }.toMap()
        val qr = fields["qrcode"]?.takeIf {
            it.isNotBlank() && it.length <= 4096 && it.none { ch -> ch.isISOControl() }
        } ?: return null
        "wtloginmqq://ptlogin/qlogin?qrcode=${URLEncoder.encode(qr, "UTF-8")}" +
            "&schemacallback=${URLEncoder.encode("$callbackScheme://", "UTF-8")}"
    }.getOrNull()

    /**
     * 4399's mobile one-key form uses p= instead of a QR token. QQ's JumpActivity
     * can still accept it; the schemacallback lets us at least resume the app.
     */
    fun fromOneKey(raw: String, callbackScheme: String): String? = runCatching {
        val uri = URI(raw)
        if (uri.scheme != "wtloginmqq" || uri.host != "ptlogin" || uri.path != "/qlogin") {
            return null
        }
        val fields = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
            if (part.isBlank()) {
                null
            } else {
                val name = URLDecoder.decode(part.substringBefore('='), "UTF-8")
                val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                name to value
            }
        }.toMap()
        val p = fields["p"]?.takeIf {
            it.isNotBlank() && it.length <= 16_384 && it.none { ch -> ch.isISOControl() }
        } ?: return null
        "wtloginmqq://ptlogin/qlogin?p=${URLEncoder.encode(p, "UTF-8")}" +
            "&schemacallback=${URLEncoder.encode("$callbackScheme://", "UTF-8")}"
    }.getOrNull()

    fun isCallback(uri: URI, packageName: String): Boolean =
        uri.scheme == "$packageName.qqlogin"
}
