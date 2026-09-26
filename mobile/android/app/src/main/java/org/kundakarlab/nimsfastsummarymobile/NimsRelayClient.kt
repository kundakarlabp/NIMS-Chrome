package org.kundakarlab.nimsfastsummarymobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.TimeUnit

data class NimsRelayIdentity(val deviceId: String, val deviceSecret: String)

data class NimsRelayJob(
    val id: String,
    val status: String,
    val requestEnvelope: JSONObject,
    val resultPublicKeyJwk: JSONObject
)

class NimsRelayClient(private val settings: SecureSettings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    fun ensureIdentity(): NimsRelayIdentity {
        val existingId = settings.relayDeviceId()
        val existingSecret = settings.relayDeviceSecret()
        if (existingId.isNotBlank() && existingSecret.isNotBlank()) {
            return NimsRelayIdentity(existingId, existingSecret)
        }
        val id = UUID.randomUUID().toString()
        val secretBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val secret = android.util.Base64.encodeToString(
            secretBytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
        settings.saveRelayIdentity(id, secret)
        return NimsRelayIdentity(id, secret)
    }

    suspend fun register() {
        val identity = ensureIdentity()
        post(
            JSONObject()
                .put("action", "register")
                .put("deviceId", identity.deviceId)
                .put("deviceSecret", identity.deviceSecret)
                .put("clientVersion", BuildConfig.VERSION_NAME)
                .put("publicKeyJwk", NimsRelayCrypto.publicJwk()),
            authenticated = false
        )
    }

    suspend fun heartbeat() {
        post(JSONObject().put("action", "heartbeat"))
    }

    suspend fun createPairingCode(): Pair<String, String> {
        register()
        val response = post(JSONObject().put("action", "create_pairing"))
        return response.getString("pairingCode") to response.getString("expiresAt")
    }

    suspend fun poll(): NimsRelayJob? {
        val response = post(JSONObject().put("action", "poll"))
        val job = response.optJSONObject("job") ?: return null
        return NimsRelayJob(
            id = job.getString("id"),
            status = job.optString("status"),
            requestEnvelope = job.getJSONObject("request_envelope"),
            resultPublicKeyJwk = job.getJSONObject("result_public_key_jwk")
        )
    }

    suspend fun complete(job: NimsRelayJob, payload: JSONObject) {
        val envelope = NimsRelayCrypto.encryptEnvelope(payload, job.resultPublicKeyJwk)
        post(
            JSONObject()
                .put("action", "complete")
                .put("jobId", job.id)
                .put("resultEnvelope", envelope)
        )
    }

    suspend fun fail(jobId: String, code: String) {
        post(
            JSONObject()
                .put("action", "fail")
                .put("jobId", jobId)
                .put("errorCode", code.lowercase().replace(Regex("[^a-z0-9_:-]"), "_").take(80))
        )
    }

    suspend fun authRequired(jobId: String) {
        post(JSONObject().put("action", "auth_required").put("jobId", jobId))
    }

    suspend fun resume(jobId: String) {
        post(JSONObject().put("action", "resume").put("jobId", jobId))
    }

    suspend fun unregister() {
        post(JSONObject().put("action", "unregister"))
        settings.clearRelayIdentity()
    }

    private suspend fun post(body: JSONObject, authenticated: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        val identity = ensureIdentity()
        val builder = Request.Builder()
            .url(RELAY_URL)
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
        if (authenticated) {
            builder.header("x-nims-device-id", identity.deviceId)
            builder.header("x-nims-device-secret", identity.deviceSecret)
        }
        http.newCall(builder.build()).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            val json = runCatching { JSONObject(raw) }.getOrElse { JSONObject().put("ok", false).put("error", "invalid_relay_response") }
            if (!response.isSuccessful || !json.optBoolean("ok")) {
                throw IllegalStateException(json.optString("error", "relay_request_failed"))
            }
            json
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val RELAY_URL = "https://dehdptgkqbrkyzyodicd.supabase.co/functions/v1/nims-chat-bridge"
    }
}
