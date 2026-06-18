package org.kundakarlab.nimsfastsummarymobile

class EncryptedClinicalPreferences(
    private val get: (String) -> String?,
    private val put: (String, String) -> Unit,
    private val remove: (String) -> Unit,
    private val crypto: SettingsCrypto,
    private val alias: String
) {
    fun read(key: String): String {
        val value = get(key).orEmpty()
        if (value.isBlank()) return ""
        return try { crypto.decrypt(decode(value), alias) } catch (_: Exception) { remove(key); "" }
    }

    fun write(key: String, plainText: String, legacyPlaintextKey: String) {
        put(key, encode(crypto.encrypt(plainText, alias)))
        remove(legacyPlaintextKey)
    }

    fun migrate(key: String, legacyPlaintextKey: String) {
        val plain = get(legacyPlaintextKey)
        if (!plain.isNullOrBlank() && get(key).isNullOrBlank()) put(key, encode(crypto.encrypt(plain, alias)))
        if (plain != null) remove(legacyPlaintextKey)
    }

    private fun encode(value: String): String = "enc:v1:$value"
    private fun decode(value: String): String = if (value.startsWith("enc:v1:")) value.removePrefix("enc:v1:") else value
}
