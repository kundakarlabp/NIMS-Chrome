package org.kundakarlab.nimsresults

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Locally retained CR history and opt-in patient lists. Entire JSON payload,
 * including identifiers/names/notes, is encrypted with an Android Keystore key.
 * No PDF, lab observations, credentials or reports are stored here.
 * Backup is disabled in the AndroidManifest.
 */
data class PatientBookmark(
    val cr: String,
    val name: String = "",
    val lists: Set<String> = emptySet(),
    val lastUsed: Long = 0L,
    val note: String = ""
)
object PatientShelfRules {
    val LISTS = listOf("Favorites", "Follow-up", "Research")
    fun validCr(value: String) = value.length == 15 && value.all(Char::isDigit)
    fun sanitizeName(value: String) = value.trim().take(85)
    fun sanitizeNote(value: String) = value.trim().take(180)
    fun normalizedList(value: String) = value.takeIf { it in LISTS }
    fun bounded(values: List<PatientBookmark>): List<PatientBookmark> {
        val recent = values.sortedByDescending { it.lastUsed }.distinctBy { it.cr }
        // Limit automatic CR history to 25; retain intentionally saved
        // bookmarks, but cap the whole shelf to 125 patients.
        val saved = recent.filter { it.lists.isNotEmpty() }.take(100)
        val unsaved = recent.filter { it.lists.isEmpty() }.take(25)
        return (saved + unsaved).sortedByDescending { it.lastUsed }
    }
}
class PatientShelf(context: Context) {
    private val prefs = context.getSharedPreferences("nims_patient_shelf_v1", Context.MODE_PRIVATE)
    private val alias = "nims-patient-shelf-aes-v1"

    fun read(): List<PatientBookmark> {
        val raw = prefs.getString("sealed", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(decrypt(raw))
            (0 until array.length()).mapNotNull { i ->
                val row = array.optJSONObject(i) ?: return@mapNotNull null
                val cr = row.optString("cr")
                if (!PatientShelfRules.validCr(cr)) return@mapNotNull null
                PatientBookmark(
                    cr, PatientShelfRules.sanitizeName(row.optString("name")),
                    row.optJSONArray("lists")?.let { a ->
                        (0 until a.length()).mapNotNull { j ->
                            PatientShelfRules.normalizedList(a.optString(j))
                        }.toSet()
                    }.orEmpty(),
                    row.optLong("used"), PatientShelfRules.sanitizeNote(row.optString("note"))
                )
            }.let(PatientShelfRules::bounded)
        }.getOrDefault(emptyList())  // fail closed if key unavailable/invalid
    }

    fun rememberCr(cr: String): List<PatientBookmark> = change(cr) { it }

    fun confirmedPatient(cr: String, name: String): List<PatientBookmark> =
        change(cr) { it.copy(name = PatientShelfRules.sanitizeName(name)) }

    fun setList(cr: String, list: String, enabled: Boolean): List<PatientBookmark> {
        require(PatientShelfRules.normalizedList(list) != null)
        return change(cr) { bookmark ->
            bookmark.copy(lists = if (enabled) bookmark.lists + list else bookmark.lists - list)
        }
    }

    fun setNote(cr: String, text: String): List<PatientBookmark> =
        change(cr) { it.copy(note = PatientShelfRules.sanitizeNote(text)) }

    fun remove(cr: String): List<PatientBookmark> {
        val others = read().filterNot { it.cr == cr }
        write(others)
        return others
    }

    fun clearRecent(): List<PatientBookmark> {
        val kept = read().filter { it.lists.isNotEmpty() }
        write(kept)
        return kept
    }

    private fun change(cr: String, change: (PatientBookmark) -> PatientBookmark): List<PatientBookmark> {
        require(PatientShelfRules.validCr(cr))
        val entries = read()
        val previous = entries.firstOrNull { it.cr == cr } ?: PatientBookmark(cr)
        val updated = PatientShelfRules.bounded(
            entries.filterNot { it.cr == cr } +
                change(previous).copy(lastUsed = System.currentTimeMillis())
        )
        write(updated)
        return updated
    }

    private fun write(entries: List<PatientBookmark>) {
        val array = JSONArray()
        PatientShelfRules.bounded(entries).forEach { row ->
            array.put(JSONObject().apply {
                put("cr", row.cr)
                put("name", row.name)
                put("lists", JSONArray(row.lists.toList().sorted()))
                put("used", row.lastUsed)
                put("note", row.note)
            })
        }
        check(prefs.edit().putString("sealed", encrypt(array.toString())).commit()) {
            "Patient list could not be saved"
        }
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
            generateKey()
        }
    }
}
