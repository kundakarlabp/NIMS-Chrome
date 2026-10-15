package org.kundakarlab.nimsresults

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SessionVault(private val context: Context) {
    private val prefs = context.getSharedPreferences("nims_session_vault", Context.MODE_PRIVATE)

    fun captureFromWebView() {
        val cm = CookieManager.getInstance()
        val headers = listOf(
            cm.getCookie(NimsUrls.WWW_ORIGIN).orEmpty(),
            cm.getCookie(NimsUrls.APEX_ORIGIN).orEmpty()
        ).filter { it.isNotBlank() }
        if (headers.isEmpty()) return
        val cookies = linkedMapOf<String, String>()
        headers.flatMap { it.split(";") }.forEach { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0) cookies[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim()
        }
        if (cookies.isEmpty()) return
        val header = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        prefs.edit().putString("cookies", encrypt(header)).apply()
        cm.flush()
    }

    fun restoreToWebView() {
        val stored = prefs.getString("cookies", null) ?: return
        val header = runCatching { decrypt(stored) }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
        val cm = CookieManager.getInstance()
        header.split(";").map { it.trim() }.filter { it.contains("=") }.forEach { cookie ->
            cm.setCookie(NimsUrls.WWW_ORIGIN, "$cookie; Path=/; Secure")
            cm.setCookie(NimsUrls.APEX_ORIGIN, "$cookie; Path=/; Secure")
        }
        cm.flush()
    }

    fun clear() {
        prefs.edit().clear().apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > 12)
        val iv = payload.copyOfRange(0, 12)
        val encrypted = payload.copyOfRange(12, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "nims-results-session-v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

object NimsUrls {
    const val WWW_ORIGIN = "https://www.nimsts.edu.in"
    const val APEX_ORIGIN = "https://nimsts.edu.in"
    const val LOGIN = "$WWW_ORIGIN/AHIMSG5/hissso/loginLogin.action"
    const val CR_REPORTS = "$WWW_ORIGIN/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt"
    const val REPORT_PATH = "/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt"
    const val REPORT_LIST_API = "$APEX_ORIGIN/HBIMS/services/restful/invService/reportList"
    const val HOSPITAL_CODE = "33101"
    val HOSTS = setOf("nimsts.edu.in", "www.nimsts.edu.in")
    const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
}
