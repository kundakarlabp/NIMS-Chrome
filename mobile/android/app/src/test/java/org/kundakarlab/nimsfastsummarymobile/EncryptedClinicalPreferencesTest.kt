package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.*
import org.junit.Test

class EncryptedClinicalPreferencesTest {
    @Test fun encryptedSummaryRoundTrip() {
        val env = Env()
        env.store.write(SUMMARY, "clinical summary", "last_summary_json")
        assertEquals("clinical summary", env.store.read(SUMMARY))
        assertTrue(env.map[SUMMARY]!!.startsWith("enc:v1:"))
    }

    @Test fun encryptedNoteRoundTrip() {
        val env = Env()
        env.store.write(NOTE, "doctor note", "physician_note")
        assertEquals("doctor note", env.store.read(NOTE))
    }

    @Test fun plaintextMigrationEncryptsAndRemovesPlaintext() {
        val env = Env(mutableMapOf("last_summary_json" to "old summary", "physician_note" to "old note"))
        env.store.migrate(SUMMARY, "last_summary_json")
        env.store.migrate(NOTE, "physician_note")
        assertEquals("old summary", env.store.read(SUMMARY))
        assertEquals("old note", env.store.read(NOTE))
        assertFalse(env.map.containsKey("last_summary_json"))
        assertFalse(env.map.containsKey("physician_note"))
    }

    @Test fun corruptSummaryDoesNotDeleteNote() {
        val env = Env()
        env.store.write(NOTE, "valid note", "physician_note")
        env.map[SUMMARY] = "enc:v1:bad"
        assertEquals("", env.store.read(SUMMARY))
        assertEquals("valid note", env.store.read(NOTE))
        assertTrue(env.map.containsKey(NOTE))
    }

    @Test fun corruptNoteDoesNotDeleteSummary() {
        val env = Env()
        env.store.write(SUMMARY, "valid summary", "last_summary_json")
        env.map[NOTE] = "enc:v1:bad"
        assertEquals("", env.store.read(NOTE))
        assertEquals("valid summary", env.store.read(SUMMARY))
        assertTrue(env.map.containsKey(SUMMARY))
    }

    @Test fun storedPreferencesDoNotContainPlaintextClinicalContent() {
        val env = Env()
        env.store.write(SUMMARY, "secret summary", "last_summary_json")
        env.store.write(NOTE, "secret note", "physician_note")
        val stored = env.map.values.joinToString("|")
        assertFalse(stored.contains("secret summary"))
        assertFalse(stored.contains("secret note"))
    }

    private class Env(val map: MutableMap<String, String> = mutableMapOf()) {
        val store = EncryptedClinicalPreferences(
            get = { map[it] },
            put = { key, value -> map[key] = value },
            remove = { key -> map.remove(key) },
            crypto = TestCrypto,
            alias = "alias"
        )
    }

    private object TestCrypto : SettingsCrypto {
        override fun encrypt(value: String, alias: String): String = value.reversed()
        override fun decrypt(value: String, alias: String): String {
            if (value == "bad") error("corrupt")
            return value.reversed()
        }
    }

    private companion object {
        const val SUMMARY = SecureSettings.KEY_LAST_SUMMARY_ENC
        const val NOTE = SecureSettings.KEY_NOTE_ENC
    }
}
