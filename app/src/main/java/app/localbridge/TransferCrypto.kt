package app.localbridge

import android.util.Base64
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Ephemeral ECDH and per-chunk AES-GCM protection for native transfer sessions. */
internal object TransferCrypto {
    private const val NONCE_BYTES = 12
    private const val TAG_BYTES = 16
    private val random = SecureRandom()

    fun createEphemeralKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    fun encodePublicKey(pair: KeyPair): String = Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP)

    fun deriveSessionKey(
        localPrivateKey: java.security.PrivateKey,
        remotePublicKeyBase64: String,
        transferId: String,
        senderId: String,
        receiverId: String
    ): SecretKeySpec {
        val remotePublicBytes = Base64.decode(remotePublicKeyBase64, Base64.DEFAULT)
        val remotePublic = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(remotePublicBytes))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(localPrivateKey)
        agreement.doPhase(remotePublic, true)
        val sharedSecret = agreement.generateSecret()
        val info = "LoBBY-Transfer-v1|$transferId|$senderId|$receiverId".toByteArray(Charsets.UTF_8)
        val salt = MessageDigest.getInstance("SHA-256").digest(info)
        val extract = Mac.getInstance("HmacSHA256")
        extract.init(SecretKeySpec(salt, "HmacSHA256"))
        val pseudoRandomKey = extract.doFinal(sharedSecret)
        val expand = Mac.getInstance("HmacSHA256")
        expand.init(SecretKeySpec(pseudoRandomKey, "HmacSHA256"))
        expand.update(info)
        expand.update(1.toByte())
        val key = expand.doFinal().copyOf(32)
        sharedSecret.fill(0)
        pseudoRandomKey.fill(0)
        return SecretKeySpec(key, "AES")
    }

    fun encodeKey(key: SecretKeySpec): String = Base64.encodeToString(key.encoded, Base64.NO_WRAP)

    fun decodeKey(value: String): SecretKeySpec = SecretKeySpec(Base64.decode(value, Base64.DEFAULT), "AES")

    fun encryptedLength(plainLength: Long): Long = plainLength + NONCE_BYTES + TAG_BYTES

    fun createSessionAuthHeader(key: SecretKeySpec, method: String, path: String): String {
        val timestamp = System.currentTimeMillis()
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
        val canonical = "${method.uppercase()}|$path|$timestamp|$nonce".toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.encoded, "HmacSHA256"))
        val signature = Base64.encodeToString(mac.doFinal(canonical), Base64.NO_WRAP)
        return "$timestamp:$nonce:$signature"
    }

    fun verifySessionAuthHeader(key: SecretKeySpec, header: String?, method: String, path: String): String? {
        val parts = header?.split(':') ?: return null
        if (parts.size != 3) return null
        val timestamp = parts[0].toLongOrNull() ?: return null
        if (kotlin.math.abs(System.currentTimeMillis() - timestamp) > 120_000L) return null
        val nonce = parts[1]
        if (!nonce.matches(Regex("[A-Za-z0-9]{16,64}"))) return null
        return try {
            val canonical = "${method.uppercase()}|$path|$timestamp|$nonce".toByteArray(Charsets.UTF_8)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key.encoded, "HmacSHA256"))
            val expected = mac.doFinal(canonical)
            val actual = Base64.decode(parts[2], Base64.DEFAULT)
            if (MessageDigest.isEqual(expected, actual)) nonce else null
        } catch (_: Exception) { null }
    }

    fun aad(transferId: String, fileIndex: Int, chunkIndex: Int, offset: Long, length: Long): ByteArray =
        "$transferId|$fileIndex|$chunkIndex|$offset|$length".toByteArray(Charsets.UTF_8)

    /** Streams a file range through AES-GCM without buffering an entire chunk in RAM. */
    fun encryptRange(
        input: InputStream,
        output: OutputStream,
        length: Long,
        key: SecretKeySpec,
        transferId: String,
        fileIndex: Int,
        chunkIndex: Int,
        offset: Long,
        bufferSize: Int,
        cancelled: () -> Boolean,
        onPlainBytes: (Int) -> Unit
    ) {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
        cipher.updateAAD(aad(transferId, fileIndex, chunkIndex, offset, length))
        output.write(nonce)
        val buffer = ByteArray(bufferSize.coerceIn(64 * 1024, 1024 * 1024))
        var remaining = length
        while (remaining > 0L) {
            if (cancelled()) throw InterruptedException("Transfer cancelled")
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("File ended before the requested chunk was read")
            val encrypted = cipher.update(buffer, 0, count)
            if (encrypted != null && encrypted.isNotEmpty()) output.write(encrypted)
            onPlainBytes(count)
            remaining -= count
        }
        val finalBytes = cipher.doFinal()
        if (finalBytes.isNotEmpty()) output.write(finalBytes)
        output.flush()
    }

    /** Verifies the GCM tag before allowing the plaintext temporary file to be committed. */
    fun decryptFile(
        encryptedFile: File,
        plainFile: File,
        expectedPlainLength: Long,
        key: SecretKeySpec,
        transferId: String,
        fileIndex: Int,
        chunkIndex: Int,
        offset: Long
    ) {
        if (encryptedFile.length() != encryptedLength(expectedPlainLength)) {
            throw IOException("Encrypted chunk length mismatch")
        }
        try {
            FileInputStream(encryptedFile).use { input ->
                val nonce = ByteArray(NONCE_BYTES)
                readFully(input, nonce)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
                cipher.updateAAD(aad(transferId, fileIndex, chunkIndex, offset, expectedPlainLength))
                FileOutputStream(plainFile).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        val decoded = cipher.update(buffer, 0, count)
                        if (decoded != null && decoded.isNotEmpty()) output.write(decoded)
                    }
                    val finalBytes = cipher.doFinal()
                    if (finalBytes.isNotEmpty()) output.write(finalBytes)
                }
            }
            if (plainFile.length() != expectedPlainLength) throw IOException("Decrypted chunk length mismatch")
        } catch (error: Exception) {
            plainFile.delete()
            throw IOException("Chunk authentication failed", error)
        }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) throw IOException("Encrypted chunk is truncated")
            offset += count
        }
    }
}
