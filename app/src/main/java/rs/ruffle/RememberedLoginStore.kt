package rs.ruffle

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal data class RememberedLogin(val username: String, val password: String)

/** Device-bound credentials, excluded from both cloud backup and device transfer. */
internal class RememberedLoginStore(context: Context, name: String = "remembered_login") {
    private val file = AtomicFile(File(context.noBackupFilesDir, name))
    private val alias = "${context.packageName}.$name"

    fun load(): RememberedLogin? {
        if (!file.baseFile.exists()) return null
        return try {
            val buffer = ByteBuffer.wrap(file.readFully())
            require(buffer.get().toInt() == 1)
            val iv = ByteArray(12).also { buffer.get(it) }
            val encrypted = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val key = keyStore().getKey(alias, null) as SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val data = JSONObject(String(cipher.doFinal(encrypted), Charsets.UTF_8))
            RememberedLogin(data.getString("username"), data.getString("password"))
        } catch (_: Exception) {
            // A restored/corrupted file or invalidated key must never break the login screen.
            clear()
            null
        }
    }

    fun save(username: String, password: String) {
        val store = keyStore()
        val key = store.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        ).apply {
            init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val data = JSONObject().put("username", username).put("password", password)
        val encrypted = cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        val bytes = ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(1.toByte()).put(cipher.iv).put(encrypted).array()
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    fun clear() {
        file.delete()
        // Removing the ciphertext is sufficient even if the keystore is temporarily unavailable.
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
