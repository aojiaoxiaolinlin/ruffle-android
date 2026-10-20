package rs.ruffle

import android.content.pm.ApplicationInfo
import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkPolicyTest {
    @Test
    fun permitsHttpForGameServersAndLoopback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            context.applicationInfo.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0
        )
        val policy = NetworkSecurityPolicy.getInstance()
        // Check the real hosts too: loopback-only tests miss restrictive domain policies.
        for (host in listOf(
            "ptlogin.4399.com",
            "ptlogin.3304399.net",
            "enter.wanwan4399.com",
            "127.0.0.1",
            "localhost"
        )) {
            assertTrue("HTTP must be permitted for $host", policy.isCleartextTrafficPermitted(host))
        }
        assertFalse(policy.isCleartextTrafficPermitted("example.com"))
    }
}
