package rs.ruffle

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RocoSessionStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "roco_session_test"
    private val store = RocoSessionStore(context, name)
    private val file = File(context.noBackupFilesDir, name)
    private val vars = "angel_uin=123&angel_key=fixture-secret-ticket&skey=b&pskey=c"

    @Before
    fun before() = store.clear()

    @After
    fun after() = store.clear()

    @Test
    fun sessionSurvivesReopeningWithoutPlaintextAndCanBeCleared() {
        store.save(vars)
        assertTrue(file.isFile)
        assertFalse(
            file.readBytes().toString(Charsets.ISO_8859_1).contains("fixture-secret-ticket")
        )
        val reopened = RocoSessionStore(context, name)
        assertEquals(RocoSession.parse(vars), reopened.load())
        reopened.clear()
        assertNull(store.load())
    }

    @Test
    fun damagedCiphertextFallsBackToLoginWithoutCrashing() {
        store.save(vars)
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(RocoSessionStore(context, name).load())
        assertFalse(file.exists())
    }

    @Test
    fun incompleteSessionDoesNotOverwriteTheSavedLogin() {
        store.save(vars)
        try {
            store.save("angel_uin=123")
            fail("Incomplete session was accepted")
        } catch (_: IllegalArgumentException) {
            assertEquals(RocoSession.parse(vars), store.load())
        }
    }
}
