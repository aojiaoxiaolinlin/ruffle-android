package rs.ruffle

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives 4399's official QQ login in a login-only WebView. The app never sees
 * the QQ password, code, state or 4399 cookies; it only receives the final
 * KBgameindex.html ticket after 4399 has exchanged the code server-side.
 */
class KabuQqLoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var status: TextView
    private lateinit var retry: Button
    private lateinit var mode: Button
    private lateinit var mobileUserAgent: String
    private var desktopMode = false
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var foreground = false
    private var started = false
    private var injected = false
    private var qqTriggered = false
    private var fetching = false
    private var finished = false
    private var ticketJob: Job? = null
    private var lastError: String? = null
    private var pollStartedAt = 0L

    private val poll = object : Runnable {
        override fun run() {
            if (!foreground || finished || fetching) return
            pollForPauth()
            if (foreground && !finished && !fetching) {
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWebView()
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelLogin()
            }
        })

        val fromCallback = intent.data?.scheme == KabuQqUrls.callbackScheme(packageName)
        if (fromCallback) {
            // The process may have been recreated by QQ. The WebView cookie
            // jar can still hold Pauth; poll it instead of restarting OAuth.
            started = true
            status.text = "QQ 已返回，正在读取授权结果…"
            retry.visibility = View.VISIBLE
        } else {
            startLogin(clearCookies = true)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            mobileUserAgent = WebSettings.getDefaultUserAgent(this@KabuQqLoginActivity)
                .replace("; wv", "")
                .replace("Version/4.0 ", "")
            userAgentString = mobileUserAgent
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                web,
                KabuQqLoginPage.useQqPolling,
                setOf("https://xui.ptlogin2.qq.com")
            )
        }
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val scheme = uri.scheme.orEmpty()
                if (scheme != "http" && scheme != "https") {
                    return when (scheme) {
                        "wtloginmqq" -> {
                            openQqApp(uri.toString())
                            true
                        }
                        KabuQqUrls.callbackScheme(packageName) -> true
                        else -> true
                    }
                }
                if (!desktopMode &&
                    uri.host == "graph.qq.com" &&
                    uri.path == "/oauth2.0/authorize" &&
                    uri.getQueryParameter("display") == null
                ) {
                    // Ask QQ for its mobile page so QR login is available even
                    // when the QQ app is not installed.
                    view.loadUrl(
                        uri.buildUpon().appendQueryParameter("display", "mobile")
                            .build().toString()
                    )
                    return true
                }
                handleUrl(uri.toString())
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                val uri = runCatching { URI(url) }.getOrNull()
                if (uri != null && KabuQqUrls.isLoginPage(uri)) {
                    injected = false
                }
                handleUrl(url)
            }

            override fun doUpdateVisitedHistory(
                view: WebView,
                url: String,
                isReload: Boolean
            ) {
                handleUrl(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                val uri = runCatching { URI(url) }.getOrNull() ?: return
                if (KabuQqUrls.isLoginPage(uri)) {
                    if (started && !injected) {
                        injected = true
                        injectStartScript()
                    }
                } else {
                    handleUrl(url)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame && !finished) {
                    showError("登录页面加载失败，请检查网络后重试")
                }
            }
        }
    }

    private fun buildUi() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        status = TextView(this).apply {
            text = "正在打开 4399 登录页…"
            setTextColor(Color.rgb(247, 233, 215))
            setTextSize(14f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = Button(this).apply {
            text = "返回"
            setOnClickListener { cancelLogin() }
        }
        retry = Button(this).apply {
            text = "重试"
            visibility = View.GONE
            setOnClickListener { startLogin(clearCookies = true) }
        }
        mode = Button(this).apply {
            text = "扫码登录"
            setOnClickListener {
                desktopMode = !desktopMode
                web.settings.userAgentString =
                    if (desktopMode) DESKTOP_UA else mobileUserAgent
                this.text = if (desktopMode) "手机登录" else "扫码登录"
                startLogin(clearCookies = true)
            }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(back, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(mode, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(retry, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        val hint = TextView(this).apply {
            text = "未安装手机 QQ？请用另一台手机打开 QQ → 扫一扫，扫描页面二维码。"
            setTextColor(Color.argb(179, 215, 195, 174))
            setTextSize(11f)
            setPadding(dp(16), dp(8), dp(16), dp(10))
            gravity = Gravity.CENTER
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 16, 12))
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
            addView(status)
            addView(controls)
            addView(
                web,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
            addView(hint)
        }
        setContentView(root)
    }

    private fun startLogin(clearCookies: Boolean) {
        generation += 1
        val current = generation
        ticketJob?.cancel()
        ticketJob = null
        handler.removeCallbacksAndMessages(null)
        started = true
        injected = false
        qqTriggered = false
        fetching = false
        finished = false
        lastError = null
        pollStartedAt = 0L
        retry.visibility = View.GONE
        status.text = "正在打开 4399 登录页…"
        web.stopLoading()
        if (clearCookies) {
            CookieManager.getInstance().removeAllCookies {
                if (!isDestroyed && current == generation) loadLoginPage()
            }
        } else {
            loadLoginPage()
        }
    }

    private fun loadLoginPage() {
        if (finished) return
        status.text = "正在打开 4399 登录页…"
        web.loadUrl(LOGIN_URL + System.currentTimeMillis())
    }

    private fun injectStartScript() {
        if (finished) return
        status.text = "请选择 QQ 登录；也可用另一台手机扫码"
        val current = generation
        web.evaluateJavascript(KabuQqLoginPage.startQqFlow) { result ->
            if (finished) return@evaluateJavascript
            if (result?.contains("unavailable") == true) {
                showError("登录页面未能启动 QQ 登录，请重试")
            } else {
                startPolling()
            }
        }
        handler.postDelayed({
            if (!finished && current == generation &&
                web.url.orEmpty().startsWith(LOGIN_PAGE)
            ) {
                showError("登录页面未能启动 QQ 登录，请重试")
            }
        }, 15_000L)
    }

    private fun openQqApp(raw: String) {
        val callback = KabuQqUrls.callbackScheme(packageName)
        val launch = KabuQqLaunch.fromOfficialQr(raw, callback)
            ?: KabuQqLaunch.fromOneKey(raw, callback)
        if (launch == null) {
            status.text = "当前页面无法唤起手机 QQ，请切换扫码登录"
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(launch)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(QQ_PACKAGE)
        }
        runCatching { startActivity(intent) }
            .onSuccess { status.text = "请在 QQ 中确认登录后返回本应用…" }
            .onFailure {
                status.text = "无法唤起手机 QQ，请确认已安装最新版 QQ，或切换扫码登录"
            }
        startPolling()
    }

    private fun switchToQrMode() {
        desktopMode = true
        web.settings.userAgentString = DESKTOP_UA
        mode.text = "手机登录"
        status.text = "请用另一台手机打开 QQ 扫码登录…"
        startLogin(clearCookies = true)
    }

    private fun handleUrl(raw: String) {
        if (finished) return
        val uri = runCatching { URI(raw) }.getOrNull() ?: return
        when {
            KabuQqUrls.isCallbackPage(uri) -> {
                status.text = "正在完成 QQ 授权…"
                startPolling()
            }
            KabuQqUrls.isQqPage(uri) -> {
                status.text = "请在 QQ 页面完成授权；未安装 QQ 时可用另一台手机扫码"
                if (!qqTriggered) {
                    qqTriggered = true
                    web.evaluateJavascript(KabuQqLoginPage.startQqApp) { result ->
                        if (!finished && result?.contains("none") != true) {
                            status.text = "正在唤起手机 QQ 授权…"
                        }
                    }
                }
                startPolling()
            }
        }
    }

    private fun startPolling() {
        if (finished || !foreground) return
        if (pollStartedAt == 0L) pollStartedAt = System.currentTimeMillis()
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    private fun pollForPauth() {
        if (fetching || finished) return
        val cookies = CookieManager.getInstance().getCookie("https://ptlogin.4399.com/")
            ?: CookieManager.getInstance().getCookie("https://www.4399.com/")
        if (KabuQqUrls.hasPauth(cookies)) {
            onPauthFound(cookies.orEmpty())
            return
        }
        if (pollStartedAt > 0L &&
            System.currentTimeMillis() - pollStartedAt > TIMEOUT_MS
        ) {
            showError("授权超时，请重试")
        }
    }

    private fun onPauthFound(cookieHeader: String) {
        if (fetching || finished) return
        fetching = true
        handler.removeCallbacks(poll)
        web.stopLoading()
        retry.visibility = View.GONE
        status.text = "授权成功，正在获取游戏登录凭证…"
        val userAgent = web.settings.userAgentString
        val current = generation
        ticketJob = lifecycleScope.launch {
            try {
                var auth: String? = null
                var failure: Exception? = null
                for (attempt in 0..1) {
                    try {
                        auth = withContext(Dispatchers.IO) {
                            KabuQqTicketClient(userAgent, cookieHeader).fetch()
                        }
                        break
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        failure = error
                        if (attempt == 0) delay(700)
                    }
                }
                if (current != generation || finished) return@launch
                val ticket = auth ?: throw (
                    failure ?: KabuLoginException("获取游戏登录凭证失败，请重试")
                    )
                val validated = KabuSession.parse(ticket)
                val saved = runCatching {
                    KabuSessionStore(this@KabuQqLoginActivity).save(validated)
                }.isSuccess
                if (!saved) {
                    Toast.makeText(
                        this@KabuQqLoginActivity,
                        "登录成功，但保存登录状态失败",
                        Toast.LENGTH_LONG
                    ).show()
                }
                finishSuccess(validated)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (current == generation && !finished) {
                    showError(
                        (error as? KabuLoginException)?.message
                            ?: "获取游戏登录凭证失败，请重试"
                    )
                }
            } finally {
                fetching = false
            }
        }
    }

    private fun finishSuccess(auth: String) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        web.stopLoading()
        if (callingActivity != null) {
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(RESULT_AUTH, auth)
            )
        } else {
            startActivity(
                Intent(this, PlayerActivity::class.java).apply {
                    data = Uri.parse(KabuLoginClient.GAME_SWF)
                    putExtra("baseUrl", KabuLoginClient.BASE_URL)
                    putExtra("authString", auth)
                }
            )
        }
        finish()
    }

    private fun showError(message: String) {
        if (finished) return
        fetching = false
        handler.removeCallbacks(poll)
        lastError = message
        status.text = message
        retry.visibility = View.VISIBLE
    }

    private fun cancelLogin() {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        ticketJob?.cancel()
        val result = Intent().putExtra(RESULT_ERROR, lastError.orEmpty())
        setResult(Activity.RESULT_CANCELED, result)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data?.scheme == KabuQqUrls.callbackScheme(packageName) && !finished) {
            status.text = "QQ 已返回，正在等待授权结果…"
            startPolling()
        }
    }

    override fun onResume() {
        super.onResume()
        foreground = true
        if (::web.isInitialized) web.onResume()
        if (started && !finished) startPolling()
    }

    override fun onPause() {
        foreground = false
        handler.removeCallbacks(poll)
        if (::web.isInitialized) web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        generation += 1
        finished = true
        handler.removeCallbacksAndMessages(null)
        ticketJob?.cancel()
        if (::web.isInitialized) {
            web.stopLoading()
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val RESULT_AUTH = "auth"
        const val RESULT_ERROR = "error"
        private const val LOGIN_PAGE = "http://news.4399.com/login/kbxy.html"
        private const val LOGIN_URL = "$LOGIN_PAGE?reg=0&pass=1&v="
        private const val QQ_PACKAGE = "com.tencent.mobileqq"
        private const val POLL_INTERVAL_MS = 500L
        private const val TIMEOUT_MS = 10 * 60 * 1000L
        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        fun newIntent(context: Context): Intent =
            Intent(context, KabuQqLoginActivity::class.java)
    }
}
