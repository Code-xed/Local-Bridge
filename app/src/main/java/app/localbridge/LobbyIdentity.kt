package app.localbridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale

/** Persistent per-installation EC identity and explicitly paired peer store. */
object LobbyIdentity {
    private const val KEY_ALIAS = "lobby.installation.identity.v1"
    private const val PREFS = "lobby_security"
    private const val PEERS_KEY = "trusted_peers"

    data class Identity(val deviceId: String, val publicKey: String)
    data class TrustedPeer(val deviceId: String, val name: String, val publicKey: String, val addedAt: Long)

    @Synchronized
    fun get(context: Context): Identity {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            generator.generateKeyPair()
        }
        val publicBytes = keyStore.getCertificate(KEY_ALIAS).publicKey.encoded
        val publicKey = Base64.encodeToString(publicBytes, Base64.NO_WRAP)
        return Identity(fingerprint(publicBytes), publicKey)
    }

    fun sign(context: Context, payload: ByteArray): String {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as? java.security.PrivateKey
            ?: throw IllegalStateException("LoBBY device identity is unavailable")
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(payload)
        return Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
    }

    fun verify(publicKeyBase64: String, payload: ByteArray, signatureBase64: String): Boolean = try {
        val encoded = Base64.decode(publicKeyBase64, Base64.DEFAULT)
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(payload)
        verifier.verify(Base64.decode(signatureBase64, Base64.DEFAULT))
    } catch (_: Exception) {
        false
    }

    fun deviceId(publicKeyBase64: String): String = fingerprint(Base64.decode(publicKeyBase64, Base64.DEFAULT))

    private fun fingerprint(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).take(16).joinToString("") { "%02x".format(it) }

    fun pairingCode(firstPublicKey: String, secondPublicKey: String, nonce: String): String {
        val keys = listOf(firstPublicKey, secondPublicKey).sorted()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${keys[0]}|${keys[1]}|$nonce".toByteArray(Charsets.UTF_8))
        val value = ((digest[0].toInt() and 0xff) shl 16) or
            ((digest[1].toInt() and 0xff) shl 8) or (digest[2].toInt() and 0xff)
        return String.format(Locale.ROOT, "%06d", value % 1_000_000)
    }

    fun trustedPeers(context: Context): List<TrustedPeer> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PEERS_KEY, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                TrustedPeer(item.getString("id"), item.optString("name", "LoBBY device"), item.getString("key"), item.optLong("added", 0L))
            }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun trustPeer(context: Context, peer: TrustedPeer) {
        val peers = trustedPeers(context).filterNot { it.deviceId == peer.deviceId } + peer
        val array = JSONArray()
        peers.forEach { array.put(JSONObject().put("id", it.deviceId).put("name", it.name).put("key", it.publicKey).put("added", it.addedAt)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PEERS_KEY, array.toString()).apply()
    }

    fun findTrustedPeer(context: Context, deviceId: String): TrustedPeer? = trustedPeers(context).firstOrNull { it.deviceId == deviceId }

    @Synchronized
    fun renameTrustedPeer(context: Context, deviceId: String, name: String) {
        val cleanName = name.trim().take(48).ifBlank { "LoBBY device" }
        val peers = trustedPeers(context).map { peer ->
            if (peer.deviceId == deviceId) peer.copy(name = cleanName) else peer
        }
        val array = JSONArray()
        peers.forEach { array.put(JSONObject().put("id", it.deviceId).put("name", it.name).put("key", it.publicKey).put("added", it.addedAt)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PEERS_KEY, array.toString()).apply()
    }

    @Synchronized
    fun removeAllTrustedPeers(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PEERS_KEY, "[]").apply()
    }

    @Synchronized
    fun removeTrustedPeer(context: Context, deviceId: String) {
        val array = JSONArray()
        trustedPeers(context).filterNot { it.deviceId == deviceId }.forEach {
            array.put(JSONObject().put("id", it.deviceId).put("name", it.name).put("key", it.publicKey).put("added", it.addedAt))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PEERS_KEY, array.toString()).apply()
    }
}
