package rs.ruffle

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.UiDevice
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemKeyboardTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun compositionIsCommittedOnceAndOldConnectionsCannotInsertText() {
        val commits = mutableListOf<String>()
        val deletes = mutableListOf<Pair<Int, Int>>()
        instrumentation.runOnMainSync {
            val view = GameTextInputView(context, commits::add,
                { before, after, _ -> deletes.add(before to after) }, {}, {})
            val info = EditorInfo()
            val connection = view.onCreateInputConnection(info)
            assertTrue(info.imeOptions and EditorInfo.IME_FLAG_NO_FULLSCREEN != 0)
            connection.setComposingText("ni", 1)
            connection.setComposingText("nihao", 1)
            assertTrue(commits.isEmpty())
            connection.commitText("你好", 1)
            connection.finishComposingText()
            assertEquals(listOf("你好"), commits)
            connection.deleteSurroundingTextInCodePoints(1, 0)
            assertEquals(listOf(1 to 0), deletes)
            connection.setComposingText("abc", 1)
            connection.closeConnection()
            connection.commitText("stale", 1)
            assertEquals(listOf("你好"), commits)
        }
    }

    @Test
    fun systemKeyboardEditsFlashWithoutResizingSurface() {
        val trace = File.createTempFile("ime-trace", ".txt", context.cacheDir)
        val movie = File(context.cacheDir, "system-keyboard.swf")
        instrumentation.context.resources.openRawResource(rs.ruffle.test.R.raw.system_keyboard)
            .use { movie.writeBytes(it.readBytes()) }
        context.startActivity(Intent(context, PlayerActivity::class.java)
            .setData(Uri.fromFile(movie)).putExtra("traceOutput", trace.absolutePath)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitFor { trace.readText().contains("ime-ready") }
        SystemClock.sleep(500)
        var bounds = Rect()
        onPlayer { activity ->
            val surface = descendants(activity.window.decorView)
                .first { it.contentDescription == "Ruffle Player" }
            val loc = IntArray(2)
            surface.getLocationOnScreen(loc)
            bounds = Rect(loc[0], loc[1], loc[0] + surface.width, loc[1] + surface.height)
        }
        val scale = minOf(bounds.width() / 640f, bounds.height() / 360f)
        device.click((bounds.centerX() - 240 * scale).toInt(),
            (bounds.centerY() - 125 * scale).toInt())
        waitFor { keyboardVisible() }
        assertSurface(bounds)
        lateinit var connection: InputConnection
        onPlayer { activity ->
            val endpoint = descendants(activity.window.decorView).filterIsInstance<GameTextInputView>().first()
            connection = endpoint.onCreateInputConnection(EditorInfo())
            connection.setComposingText("nihao", 1)
        }
        SystemClock.sleep(300)
        assertFalse(trace.readText().contains("ime-text:"))
        instrumentation.runOnMainSync { connection.commitText("你好🙂", 1) }
        waitFor { trace.readText().contains("ime-text:你好🙂") }
        instrumentation.runOnMainSync { connection.deleteSurroundingTextInCodePoints(1, 0) }
        waitFor { trace.readLines().lastOrNull() == "ime-text:你好" }
        instrumentation.runOnMainSync { connection.commitText("🙂", 1) }
        waitFor { trace.readLines().lastOrNull() == "ime-text:你好🙂" }
        instrumentation.runOnMainSync { connection.deleteSurroundingText(2, 0) }
        waitFor { trace.readLines().lastOrNull() == "ime-text:你好" }
        instrumentation.runOnMainSync {
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
        waitFor { trace.readLines().lastOrNull() == "ime-text:你" }
        device.pressBack()
        waitFor { !keyboardVisible() }
        assertSurface(bounds)
        val scale = minOf(bounds.width() / 640f, bounds.height() / 360f)
        device.click((bounds.centerX() - 240 * scale).toInt(),
            (bounds.centerY() - 125 * scale).toInt())
        waitFor { keyboardVisible() }
        assertSurface(bounds)
        instrumentation.runOnMainSync { connection.performEditorAction(EditorInfo.IME_ACTION_DONE) }
        waitFor { !keyboardVisible() }
        assertSurface(bounds)
    }

    private fun assertSurface(expected: Rect) = onPlayer { activity ->
        val surface = descendants(activity.window.decorView)
            .first { it.contentDescription == "Ruffle Player" }
        assertEquals(expected.width(), surface.width)
        assertEquals(expected.height(), surface.height)
    }

    private fun keyboardVisible(): Boolean {
        var visible = false
        onPlayer { visible = ViewCompat.getRootWindowInsets(it.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true }
        return visible
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private fun onPlayer(block: (PlayerActivity) -> Unit) = instrumentation.runOnMainSync {
        block(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<PlayerActivity>().first())
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        assertTrue("IME/Flash condition timed out", condition())
    }
}
