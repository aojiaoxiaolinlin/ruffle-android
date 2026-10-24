package rs.ruffle

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Official QQ pages own credentials and authorization; the app only receives game tickets. */
class RocoLoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var modeButton: Button
    private val handler = Handler(Looper.getMainLooper())
    private val handledCodes = mutableSetOf<String>()
    private var exchangeJob: Job? = null
    private var pendingSession: String? = null
    private var pendingNight = false
    private var mobile = true
    private var foreground = false
    private var launched = false
    private var generation = 0
    private var reading = false

    private val poll = object : Runnable {
        override fun run() {
            if (!foreground || launched || isDestroyed) return
            readResult()
            handler.postDelayed(this, 750)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessions = RocoSessionStore(this)
        val reauthorize = intent.getBooleanExtra("reauthorize", false)
        if (reauthorize) {
            sessions.clear()
        } else {
            sessions.load()?.let {
                launched = true
                openGame(it)
                return
            }
        }
        val barsController = WindowCompat.getInsetsController(window, window.decorView)
        barsController.isAppearanceLightStatusBars = true
        status = TextView(this).apply { setPadding(16, 12, 16, 12) }
        val retry = Button(this).apply {
            text = "重新登录"
            setOnClickListener { restartLogin() }
        }
        modeButton = Button(this).apply {
            setOnClickListener {
                mobile = !mobile
                restartLogin()
            }
        }
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(
                    this,
                    RocoLoginPage.useQqPolling,
                    setOf("https://xui.ptlogin2.qq.com")
                )
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val uri = request.url
                    callbackCode(uri)?.let {
                        exchangeCode(it)
                        return true
                    }
                    if (uri.scheme !in listOf("https", "http")) {
                        openQq(uri)
                        return true
                    }
                    if (mobile &&
                        uri.host == "graph.qq.com" &&
                        uri.path == "/oauth2.0/authorize" &&
                        uri.getQueryParameter("display") == null
                    ) {
                        // Adapt the official page's own OAuth URL, preserving its app and callback.
                        view.loadUrl(
                            uri.buildUpon().appendQueryParameter("display", "mobile")
                                .build().toString()
                        )
                        return true
                    }
                    if (uri.scheme == "http" && uri.host in GAME_HOSTS) {
                        view.loadUrl(uri.buildUpon().scheme("https").build().toString())
                        return true
                    }
                    return false
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {
                    // Unlike onPageFinished, this also observes iframe requests.
                    val code = callbackCode(request.url) ?: return null
                    handler.post { if (!isDestroyed) exchangeCode(code) }
                    // Stop the official callback script consuming the one-time code a second time.
                    return WebResourceResponse(
                        "text/html",
                        "UTF-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }

                override fun onPageFinished(view: WebView, url: String) {
                    callbackCode(Uri.parse(url))?.let { exchangeCode(it) }
                    readResult()
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) {
                        status.text = "登录页面加载失败，请检查网络后重试"
                    }
                }
            }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(android.graphics.Color.WHITE)
                ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                    val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                    view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                    insets
                }
                addView(status)
                addView(
                    LinearLayout(this@RocoLoginActivity).apply {
                        addView(retry, LinearLayout.LayoutParams(0, -2, 1f))
                        addView(modeButton, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                )
                addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
            }
        )
        if (reauthorize) {
            CookieManager.getInstance().removeAllCookies {
                if (!isDestroyed) restartLogin()
            }
        } else {
            restartLogin()
        }
    }

    private fun restartLogin() {
        generation++
        exchangeJob?.cancel()
        exchangeJob = null
        pendingSession = null
        handledCodes.clear()
        reading = false
        web.stopLoading()
        web.settings.userAgentString = if (mobile) {
            WebSettings.getDefaultUserAgent(this).replace("; wv", "").replace("Version/4.0 ", "")
        } else {
            DESKTOP_UA
        }
        modeButton.text = if (mobile) "切换扫码登录" else "切换手机 QQ 登录"
        status.text = if (mobile) "请使用官方页面的 QQ 快捷登录" else "请扫码登录后返回本应用"
        web.loadUrl(LOGIN_URL)
    }

    private fun openQq(uri: Uri) {
        // The p= form returns authorization into an external browser. Only use the
        // official QR-assisted form, whose result is polled by this WebView.
        val launch = RocoQqLaunch.fromOfficialQr(uri.toString(), "$packageName.qqlogin")
        if (launch == null) {
            status.text = "当前页面不支持应用内 QQ 授权，请切换扫码或密码登录"
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(launch))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            // QQ's QR authorization finishes by moving its task to the back.
            // Isolate it so it cannot background our polling WebView's task too.
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .setPackage("com.tencent.mobileqq")
        runCatching { startActivity(intent) }
            .onSuccess { status.text = "请在 QQ 确认登录后返回本应用，正在等待授权…" }
            .onFailure { status.text = "无法唤起 QQ，请切换扫码或密码登录" }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data?.scheme != "$packageName.qqlogin" || launched) return
        // The callback only resumes the original WebView. Never load its URL or tickets.
        status.text = "QQ 已返回，正在等待官方授权结果…"
        readResult()
    }

    private fun exchangeCode(code: String) {
        if (launched || pendingSession != null || exchangeJob?.isActive == true) return
        if (!handledCodes.add(code)) return
        val current = generation
        val ua = web.settings.userAgentString
        status.text = "QQ 授权成功，正在获取游戏会话…"
        exchangeJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val cookies = CookieManager.getInstance()
                    RocoLoginClient(ua, cookies::getCookie) { url, cookie ->
                        cookies.setCookie(url, cookie)
                    }.exchange(code)
                }
                if (current != generation || isDestroyed) return@launch
                if (result.vars != null) {
                    acceptSession(result.vars)
                } else if (RocoSession.isNightNotice(result.html, result.url)) {
                    openNightNotice()
                } else {
                    status.text = "正在读取官方游戏页面，请稍候…"
                    web.loadDataWithBaseURL(result.url, result.html, "text/html", "UTF-8", null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (current == generation) {
                    status.text = "获取游戏会话失败，请点击重新登录"
                }
            }
        }
    }

    private fun readResult() {
        if (!foreground || launched || isDestroyed || reading) return
        reading = true
        val current = generation
        web.evaluateJavascript(RocoLoginPage.readResult) { result ->
            reading = false
            if (launched || isDestroyed || current != generation) return@evaluateJavascript
            val value = runCatching { JSONObject(result) }.getOrNull() ?: return@evaluateJavascript
            if (value.optBoolean("night")) {
                openNightNotice()
                return@evaluateJavascript
            }
            value.optString("vars").takeIf { it.isNotEmpty() }?.let { raw ->
                runCatching { RocoSession.parse(raw) }.getOrNull()?.let { acceptSession(it) }
            }
            value.optString("code").takeIf { it.isNotEmpty() }?.let { exchangeCode(it) }
        }
    }

    private fun acceptSession(vars: String) {
        if (pendingSession != vars) {
            runCatching { RocoSessionStore(this).save(vars) }.onFailure {
                Toast.makeText(this, "本次登录信息保存失败，下次需重新授权", Toast.LENGTH_LONG).show()
            }
            CookieManager.getInstance().flush()
        }
        pendingSession = vars
        if (!foreground || launched) return
        launched = true
        handler.removeCallbacks(poll)
        openGame(vars)
    }

    private fun openGame(vars: String) = openPlayer(RocoSession.GAME_SWF, vars)

    private fun openNightNotice() {
        if (launched || pendingSession != null || isDestroyed) return
        pendingNight = true
        if (!foreground) return
        launched = true
        pendingNight = false
        handler.removeCallbacks(poll)
        Toast.makeText(this, "夜间保护期间无法登录游戏，正在播放官方提示", Toast.LENGTH_SHORT).show()
        openPlayer(RocoSession.NIGHT_SWF, null)
    }

    private fun openPlayer(swf: String, vars: String?) {
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                data = Uri.parse(swf)
                putExtra("baseUrl", RocoSession.BASE_URL)
                if (vars != null) putExtra("flashVars", vars)
            }
        )
        finish()
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (!::web.isInitialized) return
        web.onResume()
        pendingSession?.let { acceptSession(it) }
        if (pendingNight) openNightNotice()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onPause() {
        foreground = false
        handler.removeCallbacks(poll)
        if (::web.isInitialized) web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        generation++
        handler.removeCallbacksAndMessages(null)
        if (::web.isInitialized) {
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        private const val LOGIN_URL = "https://17roco.qq.com/login.html"
        private val GAME_HOSTS = setOf("17roco.qq.com", "web2.17roco.qq.com")
        private const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        private fun callbackCode(uri: Uri): String? = if (
            uri.scheme in listOf("https", "http") &&
            (
                uri.host == "17roco.qq.com" &&
                    uri.path == "/logintarget.html" ||
                    uri.host == "web2.17roco.qq.com" &&
                    uri.path == "/fcgi-bin/login3"
                )
        ) {
            uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }
        } else {
            null
        }
    }
}
