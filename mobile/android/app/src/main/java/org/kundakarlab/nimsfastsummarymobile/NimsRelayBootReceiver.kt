package org.kundakarlab.nimsfastsummarymobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class NimsRelayBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (!SecureSettings(context).relayEnabled()) return
        runCatching { NimsRelayService.start(context) }
    }
}
