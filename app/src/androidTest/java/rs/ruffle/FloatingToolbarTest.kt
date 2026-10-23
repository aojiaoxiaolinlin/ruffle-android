package rs.ruffle

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.View
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

private const val UI_TIMEOUT = 30000L

@RunWith(AndroidJUnit4::class)
class FloatingToolbarTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun clippedHandleCanOpenAndDragToEitherSafeEdge() {
        val movie = File.createTempFile("floating-toolbar", ".swf", context.cacheDir)
        instrumentation.context.resources.openRawResource(rs.ruffle.test.R.raw.system_keyboard)
            .use { movie.writeBytes(it.readBytes()) }
        context.startActivity(
            Intent(context, PlayerActivity::class.java)
                .setData(Uri.fromFile(movie))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        try {
            assertTrue(
                "Player did not appear",
                device.wait(Until.hasObject(By.desc("Ruffle Player")), UI_TIMEOUT)
            )
            waitFor { snapshot()?.halfHidden == true }
            openAndClose()
            for (right in listOf(true, false)) {
                val before = requireNotNull(snapshot())
                val targetX = if (right) {
                    before.safe.right - before.height
                } else {
                    before.safe.left + before.height
                }
                assertTrue(
                    device.drag(
                        before.handle.centerX(),
                        before.handle.centerY(),
                        targetX,
                        before.safe.centerY(),
                        30
                    )
                )
                waitFor {
                    snapshot()?.let {
                        it.halfHidden &&
                            (
                                if (right) {
                                    near(it.handle.right, it.safe.right)
                                } else {
                                    near(it.handle.left, it.safe.left)
                                }
                                )
                    } == true
                }
                openAndClose()
            }
        } finally {
            device.pressBack()
            movie.delete()
        }
    }

    private fun openAndClose() {
        val closed = requireNotNull(snapshot())
        assertTrue(closed.halfHidden)
        device.click(closed.handle.centerX(), closed.handle.centerY())
        waitFor {
            snapshot()?.let { it.expanded && near(it.handle.width(), it.handle.height()) } == true
        }
        val opened = requireNotNull(snapshot())
        assertTrue(withinSafe(opened.safe, opened.handle))
        device.click(opened.handle.centerX(), opened.handle.centerY())
        waitFor { snapshot()?.halfHidden == true }
    }

    private data class Snapshot(
        val handle: Rect,
        val safe: Rect,
        val height: Int,
        val expanded: Boolean,
        val actionsShown: Boolean
    ) {
        val halfHidden: Boolean
            get() = !actionsShown &&
                height > 0 &&
                kotlin.math.abs(handle.width() * 2 - height) <= 1
    }

    private fun snapshot(): Snapshot? {
        var result: Snapshot? = null
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>()
                .firstOrNull() ?: return@runOnMainSync
            val toolbar = activity.findViewById<View>(R.id.toolbar) ?: return@runOnMainSync
            val root = toolbar.parent as? View ?: return@runOnMainSync
            val toggle = activity.findViewById<View>(R.id.button_menu) ?: return@runOnMainSync
            val actions = activity.findViewById<View>(R.id.menu_actions)
            val handle = Rect()
            val safe = Rect()
            if (!toggle.getGlobalVisibleRect(handle) || !root.getGlobalVisibleRect(safe)) {
                return@runOnMainSync
            }
            ViewCompat.getRootWindowInsets(root)?.getInsets(
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.mandatorySystemGestures()
            )?.let {
                safe.left += it.left
                safe.right -= it.right
                safe.top += it.top
                safe.bottom -= it.bottom
            }
            val actionBounds = Rect()
            val actionsFullyVisible = actions.getGlobalVisibleRect(actionBounds) &&
                actionBounds.width() == actions.width &&
                actionBounds.height() == actions.height &&
                safe.contains(actionBounds)
            result = Snapshot(
                handle,
                safe,
                toggle.height,
                actions.isShown && actions.alpha >= 0.99f && actionsFullyVisible,
                actions.isShown
            )
        }
        return result
    }

    private fun near(a: Int, b: Int): Boolean = kotlin.math.abs(a - b) <= 2

    private fun withinSafe(safe: Rect, handle: Rect): Boolean =
        handle.left >= safe.left - 2 &&
            handle.top >= safe.top - 2 &&
            handle.right <= safe.right + 2 &&
            handle.bottom <= safe.bottom + 2

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        assertTrue(
            "Floating toolbar did not reach the expected state; last snapshot: ${snapshot()}",
            condition()
        )
    }
}
