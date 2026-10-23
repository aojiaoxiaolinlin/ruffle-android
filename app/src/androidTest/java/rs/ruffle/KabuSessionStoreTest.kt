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
class KabuSessionStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "kabu_session_test"
    private val store = KabuSessionStore(context, name)
    private val file = File(context.noBackupFilesDir, name)
    private val vars = "uid=123&username=test&sig=fixture-secret-ticket%2B%3D&time=1"

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
        val reopened = KabuSessionStore(context, name)
        assertEquals(KabuSession.parse(vars), reopened.load())
        reopened.clear()
        assertNull(store.load())
    }

    @Test
    fun damagedCiphertextFallsBackToLoginWithoutCrashing() {
        store.save(vars)
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(KabuSessionStore(context, name).load())
        assertFalse(file.exists())
    }

    @Test
    fun incompleteSessionDoesNotOverwriteTheSavedLogin() {
        store.save(vars)
        try {
            store.save("uid=123")
            fail("Incomplete session was accepted")
        } catch (_: Exception) {
            assertEquals(KabuSession.parse(vars), store.load())
        }
    }
}
