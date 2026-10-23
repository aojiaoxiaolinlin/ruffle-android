package rs.ruffle

import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemKeyboardTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun compositionIsCommittedOnceAndOldConnectionsCannotInsertText() {
        val commits = mutableListOf<String>()
        val deletes = mutableListOf<Pair<Int, Int>>()
        instrumentation.runOnMainSync {
            val view = GameTextInputView(
                context,
                commits::add,
                { before, after, _ -> deletes.add(before to after) },
                {},
                {}
            )
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
}
