package org.kundakarlab.nimsfastsummarymobile

/**
 * Small encrypted preference wrapper for opt-in NIMS login credentials.
 * Callers decide when to persist; this class never logs or exports values.
 */
class EncryptedCredentialPreferences(
    private val get: (String) -> String?,
    private val put: (String, String) -> Unit,
    private val remove: (String) -> Unit,
    private val crypto: SettingsCrypto,
    private val alias: String
) {
    fun read(key: String): String {
        val value = get(key).orEmpty()
        if (value.isBlank()) return ""
        return try {
            crypto.decrypt(decode(value), alias)
        } catch (_: Exception) {
            remove(key)
            ""
        }
    }

    fun write(key: String, plainText: String) {
        if (plainText.isBlank()) {
            remove(key)
            return
        }
        put(key, encode(crypto.encrypt(plainText, alias)))
    }

    fun clear(vararg keys: String) {
        keys.forEach(remove)
    }

    private fun encode(value: String): String = "enc:v1:$value"
    private fun decode(value: String): String =
        if (value.startsWith("enc:v1:")) value.removePrefix("enc:v1:") else value
}
