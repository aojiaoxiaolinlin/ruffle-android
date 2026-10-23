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

/** Opaque game tickets: reuse until the game rejects them, with no invented local expiry. */
internal class KabuSessionStore(context: Context, name: String = "kabu_session") {
    private val file = AtomicFile(File(context.noBackupFilesDir, name))
    private val alias = "${context.packageName}.$name"

    fun load(): String? {
        if (!file.baseFile.exists()) return null
        return try {
            val buffer = ByteBuffer.wrap(file.readFully())
            require(buffer.get().toInt() == 1)
            val iv = ByteArray(12).also { buffer.get(it) }
            val encrypted = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val key = keyStore().getKey(alias, null) as SecretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            KabuSession.parse(String(cipher.doFinal(encrypted), Charsets.UTF_8))
        } catch (_: Exception) {
            clear()
            null
        }
    }

    fun save(auth: String) {
        val validated = KabuSession.parse(auth)
        val key = keyStore().getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance(
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
        val encrypted = cipher.doFinal(validated.toByteArray(Charsets.UTF_8))
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
        runCatching { keyStore().deleteEntry(alias) }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}
