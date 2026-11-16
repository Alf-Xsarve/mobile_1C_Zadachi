package kg.devtasks.app.data

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

data class PasswordProof(val salt: ByteArray, val hash: ByteArray, val iterations: Int)

class PasswordHash(private val rounds: Int = 600000) {
    fun create(password: String): PasswordProof {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return PasswordProof(salt, derive(password, salt, rounds), rounds)
    }

    fun verify(password: String, proof: PasswordProof): Boolean {
        require(proof.iterations in 1000..3000000)
        return MessageDigest.isEqual(proof.hash, derive(password, proof.salt, proof.iterations))
    }

    private fun derive(password: String, salt: ByteArray, rounds: Int): ByteArray {
        val chars = password.toCharArray()
        val spec = PBEKeySpec(chars, salt, rounds, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            chars.fill('\u0000')
        }
    }
}
