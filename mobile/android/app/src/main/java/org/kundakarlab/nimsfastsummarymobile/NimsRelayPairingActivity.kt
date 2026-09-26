package org.kundakarlab.nimsfastsummarymobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class NimsRelayPairingActivity : ComponentActivity() {
    private lateinit var settings: SecureSettings
    private lateinit var client: NimsRelayClient
    private var bridgeEnabled by mutableStateOf(false)
    private var status by mutableStateOf("Bridge not started")
    private var pairingCode by mutableStateOf("")
    private var expiresAt by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SecureSettings(this)
        client = NimsRelayClient(settings)
        bridgeEnabled = settings.relayEnabled()
        status = if (bridgeEnabled) "Secure relay is enabled on this phone." else "Enable the bridge to accept encrypted dashboard or ChatGPT CR requests."

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 44)
        }

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF006B9E))) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("NIMS secure bridge", style = MaterialTheme.typography.headlineMedium)
                    Text("The phone keeps the NIMS session, credentials and cookies local. CR jobs and result bundles cross the relay only as end-to-end encrypted envelopes.")
                    Text(status)

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = ::enableBridge,
                            enabled = !bridgeEnabled,
                            modifier = Modifier.weight(1f)
                        ) { Text("Enable bridge") }
                        OutlinedButton(
                            onClick = ::disableBridge,
                            enabled = bridgeEnabled,
                            modifier = Modifier.weight(1f)
                        ) { Text("Stop bridge") }
                    }

                    Button(
                        onClick = ::generatePairingCode,
                        enabled = bridgeEnabled,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Pair dashboard / ChatGPT") }

                    OutlinedButton(
                        onClick = ::revokePairing,
                        enabled = bridgeEnabled,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Revoke paired dashboard / ChatGPT") }

                    if (pairingCode.isNotBlank()) {
                        Text("One-time pairing code", style = MaterialTheme.typography.labelLarge)
                        Text(pairingCode, style = MaterialTheme.typography.headlineLarge)
                        Text("Expires: $expiresAt. Enter this code only in your NIMS dashboard/ChatGPT bridge pairing screen.")
                    }

                    OutlinedButton(
                        onClick = { startActivity(Intent(this@NimsRelayPairingActivity, LoginGateActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Authenticate NIMS now") }

                    Text(
                        "CAPTCHA/OTP remains human-entered. The bridge never sends the NIMS ID/password, cookies, CAPTCHA, report URLs or transient report tokens to the relay.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (bridgeEnabled) NimsRelayService.start(this)
    }

    private fun enableBridge() {
        settings.saveRelayEnabled(true)
        bridgeEnabled = true
        status = "Starting secure relay…"
        NimsRelayService.start(this)
        lifecycleScope.launch {
            runCatching { client.register() }
                .onSuccess { status = "Secure relay enabled and registered." }
                .onFailure { status = "Bridge enabled; registration will retry automatically." }
        }
    }

    private fun disableBridge() {
        settings.saveRelayEnabled(false)
        bridgeEnabled = false
        pairingCode = ""
        expiresAt = ""
        NimsRelayService.stop(this)
        status = "Secure relay stopped. NIMS itself remains available in the app."
    }

    private fun revokePairing() {
        status = "Revoking paired requester…"
        lifecycleScope.launch {
            runCatching { client.revokeRequester() }
                .onSuccess {
                    pairingCode = ""
                    expiresAt = ""
                    status = "Paired dashboard/ChatGPT requester revoked. Create a new pairing code when needed."
                }
                .onFailure { status = "Could not revoke pairing yet. Check connectivity and retry." }
        }
    }

    private fun generatePairingCode() {
        status = "Creating one-time pairing code…"
        lifecycleScope.launch {
            runCatching { client.createPairingCode() }
                .onSuccess { (code, expiry) ->
                    pairingCode = code
                    expiresAt = expiry
                    status = "Pairing code ready. It can be used once."
                }
                .onFailure { status = "Could not create a pairing code. The relay will retry when connectivity returns." }
        }
    }
}
