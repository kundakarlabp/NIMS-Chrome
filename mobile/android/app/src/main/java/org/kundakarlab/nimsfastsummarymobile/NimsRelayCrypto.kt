package org.kundakarlab.nimsfastsummarymobile

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.RSAPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

object NimsRelayCrypto {
    private const val RSA_ALIAS = "nims_results_relay_rsa_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    fun publicJwk(): JSONObject {
        val publicKey = keyPairPublicKey()
        return JSONObject()
            .put("kty", "RSA")
            .put("alg", "RSA-OAEP-256")
            .put("use", "enc")
            .put("n", b64url(unsigned(publicKey.modulus)))
            .put("e", b64url(unsigned(publicKey.publicExponent)))
    }

    fun decryptEnvelope(envelope: JSONObject): JSONObject {
        require(envelope.optInt("v") == 1) { "Unsupported relay envelope" }
        val wrapped = decode(envelope.getString("wrappedKey"))
        val iv = decode(envelope.getString("iv"))
        val ciphertext = decode(envelope.getString("ciphertext"))

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val privateKey = keyStore.getKey(RSA_ALIAS, null)
            ?: throw IllegalStateException("Relay private key unavailable")

        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
        rsa.init(
            Cipher.DECRYPT_MODE,
            privateKey,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        )
        val rawAes = rsa.doFinal(wrapped)

        val aes = javax.crypto.spec.SecretKeySpec(rawAes, "AES")
        val gcm = Cipher.getInstance("AES/GCM/NoPadding")
        gcm.init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(128, iv))
        return JSONObject(String(gcm.doFinal(ciphertext), Charsets.UTF_8))
    }

    fun encryptEnvelope(payload: JSONObject, recipientJwk: JSONObject): JSONObject {
        val publicKey = publicKeyFromJwk(recipientJwk)
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        val aesKey = generator.generateKey()
        val iv = ByteArray(12).also(java.security.SecureRandom()::nextBytes)

        val gcm = Cipher.getInstance("AES/GCM/NoPadding")
        gcm.init(Cipher.ENCRYPT_MODE, aesKey, GCMParameterSpec(128, iv))
        val ciphertext = gcm.doFinal(payload.toString().toByteArray(Charsets.UTF_8))

        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
        rsa.init(
            Cipher.ENCRYPT_MODE,
            publicKey,
            OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        )
        val wrapped = rsa.doFinal(aesKey.encoded)

        return JSONObject()
            .put("v", 1)
            .put("alg", "RSA-OAEP-256+A256GCM")
            .put("wrappedKey", b64url(wrapped))
            .put("iv", b64url(iv))
            .put("ciphertext", b64url(ciphertext))
    }

    private fun keyPairPublicKey(): RSAPublicKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getCertificate(RSA_ALIAS)?.publicKey as? RSAPublicKey
        if (existing != null) return existing

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                RSA_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
                .build()
        )
        return generator.generateKeyPair().public as RSAPublicKey
    }

    private fun publicKeyFromJwk(jwk: JSONObject): PublicKey {
        require(jwk.optString("kty") == "RSA") { "Relay result key must be RSA" }
        val modulus = BigInteger(1, decode(jwk.getString("n")))
        val exponent = BigInteger(1, decode(jwk.getString("e")))
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent))
    }

    private fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }

    private fun b64url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun decode(value: String): ByteArray =
        Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
