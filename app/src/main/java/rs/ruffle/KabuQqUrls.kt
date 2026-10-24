package rs.ruffle

import java.net.URI

/** Pure URL/cookie rules shared by the QQ WebView, ticket client and tests. */
internal object KabuQqUrls {
    private const val LOGIN_HOST = "news.4399.com"
    private const val LOGIN_PATH = "/login/kbxy.html"
    private const val CALLBACK_HOST = "extlogin.4399.com"
    private const val CALLBACK_PATH = "/qzone/callback.do"
    private val QQ_HOSTS = setOf(
        "graph.qq.com",
        "xui.ptlogin2.qq.com",
        "open.mobile.qq.com",
        "jsapi.qq.com"
    )

    fun callbackScheme(packageName: String): String = "$packageName.qqlogin"

    fun isLoginPage(uri: URI): Boolean =
        uri.scheme in setOf("http", "https") &&
            uri.host.orEmpty() == LOGIN_HOST &&
            uri.path == LOGIN_PATH

    fun isCallbackPage(uri: URI): Boolean =
        uri.scheme in setOf("http", "https") &&
            uri.host.orEmpty() == CALLBACK_HOST &&
            uri.path == CALLBACK_PATH

    fun isQqPage(uri: URI): Boolean = uri.host.orEmpty() in QQ_HOSTS

    fun isCallback(uri: URI, packageName: String): Boolean =
        uri.scheme == callbackScheme(packageName)

    /** CookieManager returns "name=value; name=value"; only Pauth matters here. */
    fun hasPauth(cookieHeader: String?): Boolean =
        cookieHeader.orEmpty().split(';').any { part ->
            val trimmed = part.trim()
            val name = trimmed.substringBefore('=', "")
            val value = trimmed.substringAfter('=', "").trim()
            name == "Pauth" && value.isNotEmpty()
        }
}
