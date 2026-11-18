package kg.devtasks.app.data

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kg.devtasks.app.core.*

/** Encrypted session and account-scoped snapshots; excluded from Android backup. */
class SharedStorage(context: Context, scope: String) {
    private val vault = Vault()
    private val directory =
        File(context.noBackupFilesDir, "shared-api-" + digest(scope)).apply { mkdirs() }

    private fun digest(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    @Synchronized
    fun read(key: String): Row? =
        runCatching {
                val file = AtomicFile(File(directory, digest(key)))
                Json.obj(vault.open(file.readFully(), key))
            }
            .getOrNull()

    @Synchronized
    fun write(key: String, row: Row?) {
        val file = AtomicFile(File(directory, digest(key)))
        if (row == null) {
            file.delete()
            return
        }
        val bytes = vault.seal(Json.encode(row), key)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }
}
