package kg.devtasks.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Vault {
    @Volatile private var cached: SecretKey? = null

    @Synchronized
    private fun key(): SecretKey {
        cached?.let {
            return it
        }
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("devtasks_aes_v1", null) as? SecretKey)?.let {
            cached = it
            return it
        }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            "devtasks_aes_v1",
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes("GCM")
                        .setEncryptionPaddings("NoPadding")
                        .setKeySize(256)
                        .build()
                )
            }
            .generateKey()
            .also { cached = it }
    }

    fun seal(text: String, aad: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(aad.toByteArray())
        return c.iv + c.doFinal(text.toByteArray(Charsets.UTF_8))
    }

    fun open(bytes: ByteArray, aad: String): String {
        require(bytes.size > 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        c.updateAAD(aad.toByteArray())
        return String(c.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
}
