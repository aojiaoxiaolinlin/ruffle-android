package rs.ruffle

import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RocoLoginFlowTest {

    @Test
    fun readsGameParametersInsideAnIframeWithoutATopLevelNavigation() {
        // A plain WebView avoids launching the real game with fixture tickets.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web = AtomicReference<WebView>()
            scenario.onActivity { activity ->
                web.set(
                    WebView(activity).apply {
                        settings.javaScriptEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? {
                                if (request.url.query != "fixture=1") return null
                                val html = "<embed flashvars=\"angel_uin=123&angel_key=a" +
                                    "&skey=b&pskey=c\">"
                                return WebResourceResponse(
                                    "text/html",
                                    "UTF-8",
                                    ByteArrayInputStream(html.toByteArray())
                                )
                            }
                        }
                        loadDataWithBaseURL(
                            "https://17roco.qq.com/main.html",
                            """
                            <html>
                            <iframe src="https://17roco.qq.com/main.html?fixture=1"></iframe>
                            </html>
                            """.trimIndent(),
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                )
            }
            val deadline = SystemClock.uptimeMillis() + 5000
            var result: JSONObject? = null
            while (SystemClock.uptimeMillis() < deadline && result == null) {
                val checked = CountDownLatch(1)
                val value = AtomicReference<String>()
                scenario.onActivity {
                    web.get().evaluateJavascript(RocoLoginPage.readResult) {
                        value.set(it)
                        checked.countDown()
                    }
                }
                assertTrue(checked.await(5, TimeUnit.SECONDS))
                result = runCatching { JSONObject(value.get()) }.getOrNull()
                if (result == null) SystemClock.sleep(100)
            }
            assertEquals("angel_uin=123&angel_key=a&skey=b&pskey=c", result?.optString("vars"))
            scenario.onActivity { web.get().destroy() }
        }
    }

    @Test
    fun officialQqButtonUsesPollingInsideTheCrossOriginFrame() {
        org.junit.Assume.assumeTrue(
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val web = AtomicReference<WebView>()
            scenario.onActivity { activity ->
                web.set(
                    WebView(activity).apply {
                        settings.javaScriptEnabled = true
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            RocoLoginPage.useQqPolling,
                            setOf("https://xui.ptlogin2.qq.com")
                        )
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? {
                                if (request.url.host != "xui.ptlogin2.qq.com") return null
                                val html = """
                                    <button id="onekey">QQ</button><script>
                                    var calls=0,browser=false;
                                    window.pt={qrcode:{get:function(type){calls+=type;}}};
                                    var button=document.getElementById('onekey');
                                    button.addEventListener('touchstart',function(){browser=true;});
                                    button.addEventListener('click',function(){browser=true;});
                                    button.dispatchEvent(new Event('touchstart',{
                                        bubbles:true,cancelable:true
                                    }));
                                    button.dispatchEvent(new Event('click',{
                                        bubbles:true,cancelable:true
                                    }));
                                    parent.postMessage({calls:calls,browser:browser},'*');
                                    </script>
                                """.trimIndent()
                                return WebResourceResponse(
                                    "text/html",
                                    "UTF-8",
                                    ByteArrayInputStream(html.toByteArray())
                                )
                            }
                        }
                        loadDataWithBaseURL(
                            "https://17roco.qq.com/login.html",
                            """
                            <script>
                            window.addEventListener('message',function(e){
                                window.fixtureResult=e.data;
                            });
                            </script>
                            <iframe src="https://xui.ptlogin2.qq.com/cgi-bin/xlogin"></iframe>
                            """.trimIndent(),
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                )
            }
            var result: JSONObject? = null
            val deadline = SystemClock.uptimeMillis() + 5000
            while (SystemClock.uptimeMillis() < deadline && result == null) {
                val checked = CountDownLatch(1)
                val value = AtomicReference<String>()
                scenario.onActivity {
                    web.get().evaluateJavascript("window.fixtureResult") {
                        value.set(it)
                        checked.countDown()
                    }
                }
                assertTrue(checked.await(5, TimeUnit.SECONDS))
                result = runCatching { JSONObject(value.get()) }.getOrNull()
                if (result == null) SystemClock.sleep(100)
            }
            assertEquals(1, result?.optInt("calls"))
            assertEquals(false, result?.optBoolean("browser"))
            scenario.onActivity { web.get().destroy() }
        }
    }
}
