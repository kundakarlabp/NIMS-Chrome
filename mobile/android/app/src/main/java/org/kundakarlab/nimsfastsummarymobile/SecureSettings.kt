package org.kundakarlab.nimsfastsummarymobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSettings(context: Context) {
    private val prefs = context.getSharedPreferences("nims_mobile_settings", Context.MODE_PRIVATE)

    fun helperUrl(): String = prefs.getString("helper_url", "") ?: ""
    fun lastSummaryJson(): String = prefs.getString("last_summary_json", "") ?: ""
    fun physicianNote(): String = prefs.getString("physician_note", "") ?: ""
    fun processingMode(): org.kundakarlab.nimsfastsummarymobile.domain.model.ProcessingMode = runCatching { org.kundakarlab.nimsfastsummarymobile.domain.model.ProcessingMode.valueOf(prefs.getString("processing_mode", "LOCAL_ONLY") ?: "LOCAL_ONLY") }.getOrDefault(org.kundakarlab.nimsfastsummarymobile.domain.model.ProcessingMode.LOCAL_ONLY)

    fun saveHelperUrl(value: String) {
        prefs.edit().putString("helper_url", HelperSettingsValidator.normalizeUrl(value)).apply()
    }

    fun apiKey(): String = decrypt(prefs.getString("helper_key", "") ?: "")

    fun hasApiKey(): Boolean = prefs.getString("helper_key", "").orEmpty().isNotBlank() && apiKey().isNotBlank()

    fun saveApiKey(value: String) {
        if (value.isNotBlank()) prefs.edit().putString("helper_key", encrypt(value)).apply()
    }

    fun saveLastSummaryJson(value: String) {
        prefs.edit().putString("last_summary_json", value).apply()
    }

    fun savePhysicianNote(value: String) {
        prefs.edit().putString("physician_note", value).apply()
    }

    fun saveProcessingMode(value: org.kundakarlab.nimsfastsummarymobile.domain.model.ProcessingMode) {
        prefs.edit().putString("processing_mode", value.name).apply()
    }

    fun clearResults() {
        prefs.edit().remove("last_summary_json").apply()
    }

    fun clearPhysicianNote() {
        prefs.edit().remove("physician_note").apply()
    }

    fun clearHelperSettings() {
        prefs.edit().remove("helper_url").remove("helper_key").apply()
    }

    fun clearAllLocalData() {
        prefs.edit().clear().apply()
    }

    fun clearApiKey() {
        prefs.edit().remove("helper_key").apply()
    }


    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        if (value.isBlank()) return ""
        return try {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val ciphertext = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            clearApiKey()
            ""
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "nims_fast_summary_helper_key"
    }
}
