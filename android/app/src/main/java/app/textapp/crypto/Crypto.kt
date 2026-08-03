package app.textapp.crypto

import org.bouncycastle.math.ec.rfc7748.X25519
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.util.Base64

object Crypto {
    private const val PBKDF2_ITERATIONS = 150_000
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    /** Deterministic 32-byte seed derived from password + username. Same on every device. */
    fun deriveSeed(password: String, username: String): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), username.lowercase().toByteArray(), PBKDF2_ITERATIONS, KEY_BYTES * 8)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    /** X25519 keypair from a seed. The public key is the base-point product of the seed. */
    fun keyPairFromSeed(seed: ByteArray): Pair<ByteArray, ByteArray> {
        require(seed.size == 32) { "seed must be 32 bytes" }
        val pub = ByteArray(32)
        X25519.scalarMultBase(seed, 0, pub, 0)
        return seed.copyOf() to pub
    }

    fun privateKeyFromSeed(seed: ByteArray): ByteArray = keyPairFromSeed(seed).first

    fun computeShared(priv: ByteArray, peerPub: ByteArray): ByteArray {
        val out = ByteArray(32)
        if (!X25519.calculateAgreement(peerPub, 0, priv, 0, out, 0)) {
            throw IllegalArgumentException("invalid peer public key")
        }
        return out
    }

    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = KEY_BYTES): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val saltBuf = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(saltBuf, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            out.write(t)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    /** Symmetric conversation key: HKDF(X25519(myPriv, peerPub), salt=convId). */
    fun conversationKey(myPriv: ByteArray, peerPub: ByteArray, conversationId: String): ByteArray {
        val shared = computeShared(myPriv, peerPub)
        return hkdf(shared, conversationId.toByteArray(), "textapp/v1/conv".toByteArray())
    }

    fun encrypt(key: ByteArray, plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return Base64.encodeToString(cipher.iv + cipher.doFinal(plain), Base64.NO_WRAP)
    }

    fun decrypt(key: ByteArray, b64: String): ByteArray {
        val all = Base64.decode(b64, Base64.NO_WRAP)
        val iv = all.copyOfRange(0, IV_BYTES)
        val ct = all.copyOfRange(IV_BYTES, all.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }

    fun randomKey(): ByteArray = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }

    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun decodeB64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
}
