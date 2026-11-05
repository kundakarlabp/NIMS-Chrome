package org.kundakarlab.nimsresults

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * Accept an unambiguous NIMS 15-digit CR from a barcode or a QR payload.
 * Output is populated into the editable CR field; never auto-fetch patients
 * from a scan, log source code data or send a scanned URL to the network.
 */
object CrScanParser {
    private val exact=Regex("""^[0-9]{15}$""")
    private val tokens=Regex("""(?<![0-9])[0-9]{15}(?![0-9])""")
    private val labeled=Regex(
        """(?i)(?:\b(?:patient\s*)?(?:cr|cr\s*no|cr\s*number|patient\s*cr\s*no|registration\s*no|registration\s*number)\b)\s*[:#=\-]?\s*['"]?([0-9]{15})(?![0-9])"""
    )
    private val allowedHosts=setOf("nimsts.edu.in","www.nimsts.edu.in",
        "nims.edu.in","www.nims.edu.in")
    private val urlKeys=setOf("cr","crno","crnumber","patientcr","patientcrno")

    private fun decode(value:String):String? =
        runCatching { URLDecoder.decode(value,"UTF-8") }.getOrNull()

    fun parse(raw:String):String? {
        if(raw.length>8192)return null
        val text=raw.trim().trim('\uFEFF')
        if(exact.matches(text))return text
        // Reject outside-host URLs before inspecting any CR-like label.
        // Otherwise an attacker-controlled QR with ?cr=... would bypass
        // the allowlist through the generic labeled-text fallback.
        val uri=runCatching { URI(text) }.getOrNull()
        if(uri?.scheme!=null && uri.host!=null &&
            (uri.scheme.lowercase(Locale.US) !in setOf("http","https") ||
                uri.host.lowercase(Locale.US) !in allowedHosts))
            return null
        val labeledMatches=labeled.findAll(text)
            .map { it.groupValues[1] }.distinct().toList()
        if(labeledMatches.size>1)return null
        if(labeledMatches.size==1)return labeledMatches.single()

        if(uri!=null && uri.scheme?.lowercase(Locale.US) in setOf("http","https") &&
            uri.host?.lowercase(Locale.US) in allowedHosts) {
            val entries=listOf(uri.rawQuery.orEmpty(),uri.rawFragment.orEmpty())
                .filter(String::isNotBlank).flatMap { it.split("&") }
                .mapNotNull { entry ->
                    val pair=entry.split("=",limit=2)
                    if(pair.size!=2)return@mapNotNull null
                    val key=decode(pair[0])?.lowercase(Locale.US)
                        ?.replace(Regex("""[^a-z]"""),"").orEmpty()
                    if(key !in urlKeys)return@mapNotNull null
                    decode(pair[1])?.takeIf(exact::matches)
                }.distinct()
            if(entries.size==1)return entries.single()
            if(entries.size>1)return null
        }
        // HIS printed labels sometimes store a structured line or compact
        // JSON with an unnamed 15-digit CR and non-numeric fields. Accept
        // only ONE standalone 15-digit identifier and require that the user
        // confirms it before fetching; ambiguous QR payloads are rejected.
        val numbers=tokens.findAll(text).map { it.value }.distinct().toList()
        if(numbers.size!=1)return null
        // An arbitrary standalone 15-digit ID in an unrelated QR must not
        // be promoted to a patient CR. Labels/official URLs above are safer.
        // For other encoded badges require NIMS or patient/registration
        // context before offering the value for human confirmation.
        val context=Regex("""(?i)\bnims\b|\bpatient\b|\bregistration\b|\bcr\b""")
        return numbers.single().takeIf { context.containsMatchIn(text) }
    }
}
