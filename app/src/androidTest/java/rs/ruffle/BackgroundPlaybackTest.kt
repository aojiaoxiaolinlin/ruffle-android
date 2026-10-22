package rs.ruffle

import android.app.ActivityManager
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundPlaybackTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun heartbeatContinuesWhileHiddenAndPlayerSurvivesRepeatedReturns() {
        val trace = File.createTempFile("background-trace", ".txt", context.cacheDir)
        val movie = File.createTempFile("background-heartbeat", ".swf", context.cacheDir)
        InstrumentationRegistry.getInstrumentation().context.resources
            .openRawResource(rs.ruffle.test.R.raw.background_heartbeat).use {
                movie.writeBytes(it.readBytes())
            }
        val launch = Intent(context, PlayerActivity::class.java)
            .setData(android.net.Uri.fromFile(movie))
            .putExtra("traceOutput", trace.absolutePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(launch)
        waitFor { heartbeatCount(trace) >= 3 }
        waitFor { gameIsImmersiveLandscape() }
        repeat(3) {
            device.pressHome()
            assertTrue(device.wait(Until.hasObject(By.pkg(device.launcherPackageName)), 5000))
            waitFor { connectionServiceIsForeground() }
            // Let onStop/surface destruction complete before measuring background ticks.
            SystemClock.sleep(1000)
            val count = heartbeatCount(trace)
            waitFor { heartbeatCount(trace) >= count + 5 }
            context.startActivity(
                Intent(context, PlayerActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
            assertTrue(device.wait(Until.hasObject(By.desc("Ruffle Player")), 5000))
            waitFor { !connectionServiceIsForeground() }
            waitFor { gameIsImmersiveLandscape() }
            waitFor { heartbeatCount(trace) >= count + 10 }
        }
        device.pressBack()
        waitFor { !connectionServiceIsForeground() }
    }

    private fun heartbeatCount(trace: File) = trace.readLines().count {
        it == "background-heartbeat"
    }

    private fun gameIsImmersiveLandscape(): Boolean {
        var ready = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(
                    Stage.RESUMED
                ).filterIsInstance<PlayerActivity>().firstOrNull()
            if (player != null) {
                val insets = ViewCompat.getRootWindowInsets(player.window.decorView)
                ready = player.resources.configuration.orientation ==
                    Configuration.ORIENTATION_LANDSCAPE &&
                    insets != null &&
                    !insets.isVisible(WindowInsetsCompat.Type.statusBars()) &&
                    !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
            }
        }
        return ready
    }

    @Suppress("DEPRECATION")
    private fun connectionServiceIsForeground(): Boolean =
        context.getSystemService(ActivityManager::class.java).getRunningServices(100).any {
            it.service.className == GameConnectionService::class.java.name && it.foreground
        }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("Background heartbeat/lifecycle condition timed out", condition())
    }
}
