package org.kundakarlab.nimsresults

import android.content.Context

/** Persistent non-identifying status counts for the authenticated Enquiry UI. */
object EnquiryCaptureDiagnostics {
    private const val STORE="nims_enquiry_capture_diagnostics"
    private val events=setOf("PAGE_LOADED","MAINTENANCE","CAPTURE_REQUESTED",
        "CAPTURE_NO_TABLE","CAPTURE_INVALID_CR","CAPTURE_SUCCESS",
        "CAPTURE_HTML_ERROR","BROWSER_ERROR","RENDERER_GONE","NOT_HIS_HOST")

    fun record(context:Context,event:String) {
        if(event !in events) return
        val prefs=context.getSharedPreferences(STORE,Context.MODE_PRIVATE)
        prefs.edit().putInt(event,prefs.getInt(event,0)+1)
            .putString("lastEvent",event).apply()
    }
    fun snapshot(context:Context):String {
        val prefs=context.getSharedPreferences(STORE,Context.MODE_PRIVATE)
        return buildString {
            appendLine("enquiry.capture.diagnostics=privacy_safe_counts")
            events.sorted().forEach { name ->
                appendLine("enquiry.capture.${name.lowercase()}=${prefs.getInt(name,0)}")
            }
            appendLine("enquiry.capture.lastEvent=${prefs.getString("lastEvent","none")}")
            appendLine("enquiry.capture.patient_identifiers_saved=false")
        }
    }
}
