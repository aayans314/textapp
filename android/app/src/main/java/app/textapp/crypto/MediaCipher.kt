package app.textapp.crypto

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streaming AES-256-GCM file encryption. File format: 12-byte IV followed by ciphertext.
 */
object MediaCipher {
    private const val IV_BYTES = 12

    fun encryptFile(input: File, output: File, key: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        FileOutputStream(output).use { fos ->
            fos.write(cipher.iv)
            CipherOutputStream(fos, cipher).use { cos ->
                FileInputStream(input).use { it.copyTo(cos) }
            }
        }
    }

    fun decryptFile(input: File, output: File, key: ByteArray) {
        FileInputStream(input).use { fin ->
            val iv = ByteArray(IV_BYTES)
            var read = 0
            while (read < iv.size) {
                val n = fin.read(iv, read, iv.size - read)
                if (n < 0) throw IllegalStateException("truncated encrypted file")
                read += n
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            FileOutputStream(output).use { fos ->
                CipherInputStream(fin, cipher).use { it.copyTo(fos) }
            }
        }
    }
}
