package rs.ruffle

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Keep QQ authorization in the original WebView's official QR polling session. */
internal object RocoQqLaunch {
    fun fromOfficialQr(raw: String, callbackScheme: String): String? = runCatching {
        val uri = URI(raw)
        if (uri.scheme != "wtloginmqq" || uri.host != "ptlogin" || uri.path != "/qlogin") {
            return null
        }
        val fields = uri.rawQuery.orEmpty().split('&').associate {
            val parts = it.split('=', limit = 2)
            URLDecoder.decode(parts[0], "UTF-8") to
                URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        }
        val qr = fields["qrcode"]?.takeIf { it.isNotBlank() } ?: return null
        // QQ requires a return target; it only resumes the original polling WebView.
        // No p= URL is supplied, so game authorization stays in the original session.
        "wtloginmqq://ptlogin/qlogin?qrcode=${URLEncoder.encode(qr, "UTF-8")}" +
            "&schemacallback=${URLEncoder.encode("$callbackScheme://", "UTF-8")}"
    }.getOrNull()
}
