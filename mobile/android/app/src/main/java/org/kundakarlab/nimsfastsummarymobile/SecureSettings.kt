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

    init { migrateClinicalData() }

    fun helperUrl(): String = prefs.getString("helper_url", "") ?: ""
    fun lastSummaryJson(): String = decryptClinical(prefs.getString(KEY_LAST_SUMMARY_ENC, "") ?: "")
    fun physicianNote(): String = decryptClinical(prefs.getString(KEY_NOTE_ENC, "") ?: "")
    fun processingMode(): org.kundakarlab.nimsfastsummarymobile.domain.model.ProcessingMode = runCatching { org.kundakarlab.nimsfastsummarymobile.domain.model.ProcessingMode.valueOf(prefs.getString("processing_mode", "LOCAL_ONLY") ?: "LOCAL_ONLY") }.getOrDefault(org.kundakarlab.nimsfastsummarymobile.domain.model.ProcessingMode.LOCAL_ONLY)

    fun saveHelperUrl(value: String) { prefs.edit().putString("helper_url", HelperSettingsValidator.normalizeUrl(value)).apply() }
    fun apiKey(): String = decrypt(prefs.getString("helper_key", "") ?: "", HELPER_KEY_ALIAS, clearOnFailure = { clearApiKey() })
    fun hasApiKey(): Boolean = prefs.getString("helper_key", "").orEmpty().isNotBlank() && apiKey().isNotBlank()
    fun saveApiKey(value: String) { if (value.isNotBlank()) prefs.edit().putString("helper_key", encrypt(value, HELPER_KEY_ALIAS)).apply() }
    fun saveLastSummaryJson(value: String) { prefs.edit().putString(KEY_LAST_SUMMARY_ENC, encrypt(value, CLINICAL_KEY_ALIAS)).remove("last_summary_json").apply() }
    fun savePhysicianNote(value: String) { prefs.edit().putString(KEY_NOTE_ENC, encrypt(value, CLINICAL_KEY_ALIAS)).remove("physician_note").apply() }
    fun saveProcessingMode(value: org.kundakarlab.nimsfastsummarymobile.domain.model.ProcessingMode) { prefs.edit().putString("processing_mode", value.name).apply() }
    fun clearResults() { prefs.edit().remove(KEY_LAST_SUMMARY_ENC).remove("last_summary_json").apply() }
    fun clearPhysicianNote() { prefs.edit().remove(KEY_NOTE_ENC).remove("physician_note").apply() }
    fun clearHelperSettings() { prefs.edit().remove("helper_url").remove("helper_key").apply() }
    fun clearAllLocalData() { prefs.edit().remove(KEY_LAST_SUMMARY_ENC).remove(KEY_NOTE_ENC).remove("last_summary_json").remove("physician_note").apply() }
    fun clearApiKey() { prefs.edit().remove("helper_key").apply() }

    private fun migrateClinicalData() {
        val plainSummary = prefs.getString("last_summary_json", null)
        val plainNote = prefs.getString("physician_note", null)
        val edit = prefs.edit()
        if (!plainSummary.isNullOrBlank() && prefs.getString(KEY_LAST_SUMMARY_ENC, "").isNullOrBlank()) edit.putString(KEY_LAST_SUMMARY_ENC, encrypt(plainSummary, CLINICAL_KEY_ALIAS))
        if (!plainNote.isNullOrBlank() && prefs.getString(KEY_NOTE_ENC, "").isNullOrBlank()) edit.putString(KEY_NOTE_ENC, encrypt(plainNote, CLINICAL_KEY_ALIAS))
        if (plainSummary != null) edit.remove("last_summary_json")
        if (plainNote != null) edit.remove("physician_note")
        edit.apply()
    }

    private fun encrypt(value: String, alias: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey(alias))
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decryptClinical(value: String): String = decrypt(value, CLINICAL_KEY_ALIAS, clearOnFailure = { clearResults(); clearPhysicianNote() })

    private fun decrypt(value: String, alias: String, clearOnFailure: () -> Unit): String {
        if (value.isBlank()) return ""
        return try {
            val bytes = Base64.decode(value, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val ciphertext = bytes.copyOfRange(12, bytes.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey(alias), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            clearOnFailure(); ""
        }
    }

    private fun secretKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    companion object {
        private const val HELPER_KEY_ALIAS = "nims_fast_summary_helper_key"
        private const val CLINICAL_KEY_ALIAS = "nims_fast_summary_local_data_key"
        private const val KEY_LAST_SUMMARY_ENC = "last_summary_json_encrypted"
        private const val KEY_NOTE_ENC = "physician_note_encrypted"
    }
}
