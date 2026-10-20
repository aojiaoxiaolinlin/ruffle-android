package rs.ruffle

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RememberedLoginStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "remembered_login_test"
    private val store = RememberedLoginStore(context, name)
    private val file = File(context.noBackupFilesDir, name)

    @Before
    fun reset() = store.clear()

    @After
    fun cleanUp() = store.clear()

    @Test
    fun encryptedCredentialsSurviveNewStoreAndCanBeRemoved() {
        store.save("test-account-中文", "test-password-123")
        val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("test-account"))
        assertFalse(bytes.contains("test-password"))
        assertEquals(
            RememberedLogin("test-account-中文", "test-password-123"),
            RememberedLoginStore(context, name).load()
        )
        store.clear()
        assertFalse(file.exists())
        assertNull(store.load())
    }

    @Test
    fun tamperedCiphertextIsDiscarded() {
        store.save("account", "password")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(store.load())
        assertFalse(file.exists())
    }

    @Test
    fun invalidatedKeyDoesNotBreakLoginAndCanBeReplaced() {
        store.save("account", "password")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            .deleteEntry("${context.packageName}.$name")
        assertNull(store.load())
        store.save("new-account", "new-password")
        assertEquals(RememberedLogin("new-account", "new-password"), store.load())
    }
}
