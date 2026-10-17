package rs.ruffle

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class KabuLoginDiagnosticsTest {
    @Test
    fun distinguishesHttpAndPageErrorsFromNetworkFailure() {
        assertEquals("服务器返回 HTTP 403", KabuLoginClient.loginPageProblem(403, "blocked"))
        assertEquals("HTTP 200，但内容不是预期的登录表单", KabuLoginClient.loginPageProblem(200, "blocked"))
        assertNull(KabuLoginClient.loginPageProblem(200, "<form id=\"login_form\"></form>"))
    }

    @Test
    fun reportsDnsTimeoutAndAndroidPolicyFailures() {
        assertEquals("DNS解析失败", KabuLoginClient.connectionProblem(UnknownHostException("host")))
        assertEquals("连接或读取超时", KabuLoginClient.connectionProblem(SocketTimeoutException()))
        assertEquals(
            "Android阻止了HTTP明文连接",
            KabuLoginClient.connectionProblem(IOException("Cleartext HTTP traffic not permitted"))
        )
        assertEquals(
            "联网请求被系统拒绝，请检查应用联网权限",
            KabuLoginClient.connectionProblem(IOException("EACCES (Permission denied)"))
        )
    }

    @Test
    fun doesNotExposeRawExceptionDetails() {
        val details = KabuLoginClient.connectionProblem(IOException("private-test-token"))
        assertFalse(details.contains("private-test-token"))
        assertEquals("网络读写失败（IOException）", details)
    }
}
