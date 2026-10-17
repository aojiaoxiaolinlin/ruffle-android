package rs.ruffle

import java.io.IOException
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class KabuLoginException(message: String) : IOException(message)

/** The same 4399 login protocol as kabu-login, without credential/key files. */
class KabuLoginClient internal constructor(private val hosts: List<String> = HOSTS) {
    private val cookies = createCookieManager()

    fun login(username: String, password: String): String {
        require(username.isNotBlank() && password.isNotEmpty()) { "请输入账号和密码" }
        val host = hosts.firstOrNull { host ->
            try {
                val response =
                    request(
                        "http://$host/ptlogin/loginFrame.do?displayMode=popup&bizId=$BIZ_ID&appId=$APP_ID"
                    )
                response.status == 200 && response.body.contains("login_form")
            } catch (_: IOException) {
                false
            }
        } ?: throw KabuLoginException("无法连接4399登录服务器，请检查网络后重试")

        val frameUrl = "http://$host/ptlogin/loginFrame.do?postLoginHandler=default" +
            "&displayMode=popup&bizId=$BIZ_ID&appId=$APP_ID"
        if (request("$frameUrl&autoLogin=true&qrLogin=true").status != 200) {
            throw KabuLoginException("无法获取登录会话，请稍后重试")
        }
        val fields = linkedMapOf(
            "loginFrom" to "uframe",
            "postLoginHandler" to "default",
            "layoutSelfAdapting" to "true",
            "externalLogin" to "",
            "displayMode" to "popup",
            "layout" to "vertical",
            "bizId" to BIZ_ID,
            "appId" to APP_ID,
            "gameId" to "",
            "css" to "",
            "redirectUrl" to "",
            "sessionId" to "",
            "mainDivId" to "embed_login_div",
            "includeFcmInfo" to "false",
            "level" to "0",
            "regLevel" to "0",
            "userNameLabel" to "4399用户名",
            "userNameTip" to "请输入4399用户名",
            "welcomeTip" to "欢迎回到4399",
            "sec" to "1",
            "password" to encryptPassword(password),
            "iframeId" to "",
            "username" to username.trim()
        )
        val login = request(
            "http://$host/ptlogin/login.do?v=1",
            body = fields.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" },
            headers = mapOf("Referer" to frameUrl, "Origin" to "http://$host")
        )
        if (login.status != 200) {
            throw KabuLoginException("登录失败（HTTP ${login.status}），请检查账号密码；若触发验证码，当前快捷登录无法完成验证")
        }
        val returnUrl = "http://news.4399.com/login/kbxy.html?reg=0&pass=1&v=" +
            System.currentTimeMillis()
        val checkUrl = "http://$host/ptlogin/checkKidLoginUserCookie.do?appId=$APP_ID" +
            "&level=8&loginLevel=8&regLevel=8&bizId=$BIZ_ID&canBack=true" +
            "&gameUrl=${encode(GAME_PAGE)}&retUrl=${encode(returnUrl)}"
        val redirect = request(
            checkUrl,
            headers = mapOf("Referer" to "http://www.4399.com/flash/48399.htm"),
            followRedirects = false
        )
        if (redirect.status !in 300..399) throw KabuLoginException("未取得游戏登录凭据，请检查账号密码或稍后重试")
        return authFromRedirect(redirect.location.orEmpty())
    }

    private data class Response(val status: Int, val body: String, val location: String?)

    private fun request(
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        followRedirects: Boolean = true
    ): Response {
        var uri = URI(url)
        var requestBody = body
        repeat(6) { attempt ->
            val connection = uri.toURL().openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 25_000
                connection.readTimeout = 25_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", USER_AGENT)
                connection.setRequestProperty("Accept", "*/*")
                connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                cookies.get(uri, emptyMap()).forEach { (name, values) ->
                    connection.setRequestProperty(name, values.joinToString("; "))
                }
                headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                requestBody?.let {
                    connection.requestMethod = "POST"
                    connection.doOutput = true
                    connection.setRequestProperty(
                        "Content-Type",
                        "application/x-www-form-urlencoded"
                    )
                    connection.outputStream.use { stream ->
                        stream.write(it.toByteArray(Charsets.UTF_8))
                    }
                }
                val status = connection.responseCode
                cookies.put(uri, connection.headerFields)
                val location = connection.getHeaderField("Location")
                if (followRedirects && status in REDIRECT_CODES && location != null) {
                    if (attempt == 5) throw KabuLoginException("登录服务器跳转次数过多")
                    val next = uri.resolve(location)
                    if (next.scheme != "http" &&
                        next.scheme != "https"
                    ) {
                        throw KabuLoginException("登录服务器返回了无效地址")
                    }
                    if (status == 307 || status == 308) {
                        if (requestBody != null &&
                            next.authority != uri.authority
                        ) {
                            throw KabuLoginException("登录服务器返回了异常跳转")
                        }
                    } else {
                        requestBody = null
                    }
                    uri = next
                } else {
                    val stream = if (status >=
                        400
                    ) {
                        connection.errorStream
                    } else {
                        connection.inputStream
                    }
                    val text = stream?.bufferedReader(Charsets.UTF_8)?.use {
                        it.readText()
                    }.orEmpty()
                    return Response(status, text, location)
                }
            } finally {
                connection.disconnect()
            }
        }
        throw KabuLoginException("登录服务器跳转次数过多")
    }

    companion object {
        const val GAME_SWF = "http://enter.wanwan4399.com/bin-debug/GreenGame.swf"
        const val BASE_URL = "http://enter.wanwan4399.com/bin-debug/"
        private const val GAME_PAGE = "${BASE_URL}KBgameindex.html"
        private const val APP_ID = "kabu_xy"
        private const val BIZ_ID = "1202000651"
        private const val AES_PASS = "lzYW5qaXVqa"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        private val HOSTS = listOf("ptlogin.4399.com", "ptlogin.3304399.net")
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        internal fun createCookieManager(): CookieManager = CookieManager(
            null,
            CookiePolicy { uri, cookie ->
                // 4399 uses Domain=4399.com. Java's legacy policy rejects this
                // for ptlogin.4399.com unless the domain has a leading dot.
                val host = uri.host.orEmpty().lowercase()
                val domain = cookie.domain.orEmpty().trimStart('.').lowercase()
                val accepted = domain.isNotEmpty() && (host == domain || host.endsWith(".$domain"))
                if (accepted && host != domain) cookie.domain = ".$domain"
                accepted
            }
        )

        // CryptoJS passphrase mode: EVP_BytesToKey(MD5), AES-256-CBC, Salted__.
        internal fun encryptPassword(
            password: String,
            salt: ByteArray = ByteArray(8).also { SecureRandom().nextBytes(it) }
        ): String {
            require(salt.size == 8)
            var previous = byteArrayOf()
            var material = byteArrayOf()
            while (material.size < 48) {
                val digest = MessageDigest.getInstance("MD5")
                digest.update(previous)
                digest.update(AES_PASS.toByteArray(Charsets.UTF_8))
                digest.update(salt)
                previous = digest.digest()
                material += previous
            }
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(material.copyOfRange(0, 32), "AES"),
                IvParameterSpec(material.copyOfRange(32, 48))
            )
            return Base64.getEncoder().encodeToString(
                "Salted__".toByteArray(Charsets.US_ASCII) + salt +
                    cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            )
        }

        internal fun authFromRedirect(location: String): String {
            val uri = try {
                URI(location)
            } catch (_: Exception) {
                throw KabuLoginException("游戏登录返回了无效地址")
            }
            val query = uri.rawQuery.orEmpty()
            if (uri.scheme !in setOf("http", "https") || uri.host != "enter.wanwan4399.com" ||
                uri.path != "/bin-debug/KBgameindex.html" ||
                query.split('&').none { it.startsWith("sig=") && it.length > 4 }
            ) {
                throw KabuLoginException("未取得有效游戏凭据，请检查账号密码；若触发验证码，当前快捷登录无法完成验证")
            }
            return query
        }
    }
}
