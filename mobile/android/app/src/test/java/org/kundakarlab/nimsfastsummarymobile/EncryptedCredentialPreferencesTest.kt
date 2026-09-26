package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.*
import org.junit.Test

class EncryptedCredentialPreferencesTest {
    @Test fun credentialsRoundTripEncrypted() {
        val env = Env()
        env.store.write(USER, "doctor01")
        env.store.write(PASS, "very-secret-password")

        assertEquals("doctor01", env.store.read(USER))
        assertEquals("very-secret-password", env.store.read(PASS))
        val stored = env.map.values.joinToString("|")
        assertFalse(stored.contains("doctor01"))
        assertFalse(stored.contains("very-secret-password"))
        assertTrue(env.map[USER]!!.startsWith("enc:v1:"))
        assertTrue(env.map[PASS]!!.startsWith("enc:v1:"))
    }

    @Test fun clearRemovesBothCredentials() {
        val env = Env()
        env.store.write(USER, "doctor01")
        env.store.write(PASS, "pw")
        env.store.clear(USER, PASS)
        assertEquals("", env.store.read(USER))
        assertEquals("", env.store.read(PASS))
    }

    @Test fun corruptCredentialIsRemovedIndependently() {
        val env = Env()
        env.store.write(USER, "doctor01")
        env.map[PASS] = "enc:v1:bad"
        assertEquals("", env.store.read(PASS))
        assertEquals("doctor01", env.store.read(USER))
        assertFalse(env.map.containsKey(PASS))
    }

    private class Env(val map: MutableMap<String, String> = mutableMapOf()) {
        val store = EncryptedCredentialPreferences(
            get = { map[it] },
            put = { key, value -> map[key] = value },
            remove = { key -> map.remove(key) },
            crypto = TestCrypto,
            alias = "login_alias"
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
        const val USER = "nims_username_encrypted"
        const val PASS = "nims_password_encrypted"
    }
}
