package rs.ruffle

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
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
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun launcherReturnPreservesTheLoginActivityAndItsJavascriptState() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK))
        val main = awaitResumed<MainActivity>()
        instrumentation.runOnMainSync {
            main.startActivity(Intent(main, RocoLoginActivity::class.java))
        }
        val login = AtomicReference(awaitResumed<RocoLoginActivity>())
        val webField = RocoLoginActivity::class.java.getDeclaredField("web").apply {
            isAccessible = true
        }
        val web = webField.get(login.get()) as WebView
        val ready = CountDownLatch(1)
        instrumentation.runOnMainSync {
            web.stopLoading()
            web.loadDataWithBaseURL(
                "https://17roco.qq.com/login.html",
                "<html><script>window.rocoTestMarker='preserved'</script></html>",
                "text/html",
                "UTF-8",
                null
            )
            web.postDelayed({ ready.countDown() }, 500)
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        val device = UiDevice.getInstance(instrumentation)
        device.pressHome()
        context.startActivity(
            context.packageManager.getLaunchIntentForPackage(context.packageName)!!
        )
        assertTrue(awaitResumed<RocoLoginActivity>() === login.get())
        val checked = CountDownLatch(1)
        val marker = AtomicReference<String>()
        instrumentation.runOnMainSync {
            web.evaluateJavascript("window.rocoTestMarker") {
                marker.set(it)
                checked.countDown()
            }
        }
        assertTrue(checked.await(5, TimeUnit.SECONDS))
        assertEquals("\"preserved\"", marker.get())
        instrumentation.runOnMainSync { login.get().finish() }
    }

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

    private inline fun <reified T : Activity> awaitResumed(): T {
        val result = AtomicReference<T>()
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                result.set(
                    ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<T>().firstOrNull()
                )
            }
            result.get()?.let { return it }
            SystemClock.sleep(50)
        }
        error("Expected resumed activity: ${T::class.java.simpleName}")
    }
}
