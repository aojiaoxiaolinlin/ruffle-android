package rs.ruffle

import java.net.URI
import java.net.URISyntaxException

fun isBaseUrlValid(value: String): Boolean {
    val text = value.trim()
    if (text.isEmpty()) return true
    return try {
        val uri = URI(text)
        (
            uri.scheme.equals("http", ignoreCase = true) ||
                uri.scheme.equals("https", ignoreCase = true)
            ) &&
            !uri.host.isNullOrEmpty() &&
            uri.port in -1..65535
    } catch (_: URISyntaxException) {
        false
    }
}
