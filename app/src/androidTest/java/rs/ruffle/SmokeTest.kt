package rs.ruffle

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @Test
    fun emulatorRunsASwf() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val trace = File.createTempFile("trace", ".txt", context.cacheDir)
        val movie = File.createTempFile("movie", ".swf", context.cacheDir)
        try {
            instrumentation.context.resources.openRawResource(rs.ruffle.test.R.raw.helloflash)
                .use { movie.writeBytes(it.readBytes()) }
            val launch = Intent(context, PlayerActivity::class.java)
                .setData(Uri.fromFile(movie))
                .putExtra("traceOutput", trace.absolutePath)
            ActivityScenario.launch<PlayerActivity>(launch).use {
                val deadline = SystemClock.elapsedRealtime() + 10000
                while (SystemClock.elapsedRealtime() < deadline) {
                    if (trace.readLines() == listOf("Hello from Flash!")) break
                    SystemClock.sleep(100)
                }
                assertEquals(listOf("Hello from Flash!"), trace.readLines())
            }
        } finally {
            movie.delete()
            trace.delete()
        }
    }
}
