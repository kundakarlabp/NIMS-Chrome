package org.kundakarlab.nimsfastsummarymobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

class NimsRelayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settings: SecureSettings
    private lateinit var client: NimsRelayClient
    private lateinit var retriever: NimsBackgroundRetriever
    private var loopJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        settings = SecureSettings(this)
        client = NimsRelayClient(settings)
        retriever = NimsBackgroundRetriever(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(notification("NIMS bridge active", "Waiting for encrypted CR requests"))
        when (intent?.action) {
            ACTION_STOP -> {
                settings.saveRelayEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_AUTH_RESUMED -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID).orEmpty()
                    .ifBlank { settings.pendingRelayJobId() }
                if (jobId.isNotBlank()) {
                    scope.launch {
                        runCatching { client.resume(jobId) }
                            .onSuccess {
                                settings.clearPendingRelayJobId()
                                updateNotification("NIMS bridge active", "Authentication verified · resuming pending request")
                            }
                            .onFailure {
                                settings.savePendingRelayJobId(jobId)
                                updateNotification(
                                    "NIMS bridge reconnecting",
                                    "Authentication succeeded; secure relay resume will retry",
                                    authIntent(jobId)
                                )
                            }
                    }
                }
            }
            else -> settings.saveRelayEnabled(true)
        }
        ensureLoop()
        return START_STICKY
    }

    private fun ensureLoop() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            var retryMs = 2_000L
            var registered = false
            while (isActive && settings.relayEnabled()) {
                try {
                    if (!registered) {
                        client.register()
                        registered = true
                    }
                    retryMs = 2_000L
                    val pending = settings.pendingRelayJobId()
                    if (pending.isNotBlank()) {
                        updateNotification("NIMS authentication required", "Tap Authenticate to resume the pending CR request", authIntent(pending))
                        delay(2_000L)
                        continue
                    }

                    val job = client.poll()
                    if (job == null) {
                        updateNotification("NIMS bridge active", "Waiting for encrypted CR requests")
                        delay(2_500L)
                        continue
                    }
                    process(job)
                } catch (_: Throwable) {
                    registered = false
                    updateNotification("NIMS bridge reconnecting", "Secure relay temporarily unavailable")
                    delay(retryMs)
                    retryMs = (retryMs * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private suspend fun process(job: NimsRelayJob) {
        val request = runCatching { NimsRelayCrypto.decryptEnvelope(job.requestEnvelope) }.getOrElse {
            client.fail(job.id, "invalid_encrypted_request")
            return
        }
        val crNo = request.optString("crNo").filter(Char::isDigit)
        if (!crNo.matches(Regex("""\d{15}"""))) {
            client.fail(job.id, "invalid_cr")
            return
        }

        updateNotification("Retrieving NIMS results", "Processing an encrypted CR request")
        try {
            val bundle = retriever.retrieve(crNo)
            client.complete(job, bundle)
            settings.clearPendingRelayJobId()
            updateNotification("NIMS bridge active", "Results returned securely · waiting for next request")
        } catch (_: NimsAuthenticationRequiredException) {
            settings.savePendingRelayJobId(job.id)
            client.authRequired(job.id)
            updateNotification(
                "NIMS authentication required",
                "Tap Authenticate, enter the fresh CAPTCHA, and the same request will continue",
                authIntent(job.id)
            )
        } catch (_: Throwable) {
            client.fail(job.id, "nims_retrieval_failed")
            updateNotification("NIMS request failed", "The dashboard can retry the CR request")
        }
    }

    private fun authIntent(jobId: String): PendingIntent {
        val intent = Intent(this, LoginGateActivity::class.java)
            .putExtra(LoginGateActivity.EXTRA_RELAY_JOB_ID, jobId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            4102,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun settingsIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        4101,
        Intent(this, NimsRelayPairingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        this,
        4103,
        Intent(this, NimsRelayService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun notification(title: String, message: String, contentIntent: PendingIntent? = settingsIntent()): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Bridge settings", settingsIntent())
            .addAction(0, "Stop", stopIntent())
            .build()

    private fun updateNotification(title: String, message: String, contentIntent: PendingIntent? = settingsIntent()) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, message, contentIntent))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "NIMS secure bridge", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Clinician-enabled encrypted dashboard and ChatGPT CR retrieval"
                }
            )
        }
    }

    override fun onDestroy() {
        loopJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "nims_relay_bridge"
        private const val NOTIFICATION_ID = 4100
        private const val ACTION_START = "org.kundakarlab.nims.START_RELAY"
        private const val ACTION_STOP = "org.kundakarlab.nims.STOP_RELAY"
        private const val ACTION_AUTH_RESUMED = "org.kundakarlab.nims.RELAY_AUTH_RESUMED"
        private const val EXTRA_JOB_ID = "relay_job_id"

        fun start(context: Context) {
            val intent = Intent(context, NimsRelayService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NimsRelayService::class.java).setAction(ACTION_STOP))
        }

        fun resumeAfterAuthentication(context: Context, jobId: String) {
            val intent = Intent(context, NimsRelayService::class.java)
                .setAction(ACTION_AUTH_RESUMED)
                .putExtra(EXTRA_JOB_ID, jobId)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }
    }
}
