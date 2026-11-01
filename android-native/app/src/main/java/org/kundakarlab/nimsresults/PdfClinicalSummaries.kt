package org.kundakarlab.nimsresults

import java.util.Locale

/** Only explicitly stated, non-conflicting conclusions from a CR-verified PDF. */
data class VerifiedBloodGrouping(val group: String, val antibodyScreen: String = "")

object PdfClinicalSummaries {
    private val reportHeader = Regex(
        """(?i)\b(?:blood\s+group(?:ing)?|grouping\s+and\s+(?:antibody\s+)?screening|abo\s*(?:and|&|/|\s+)\s*rh(?:\s*typing)?|blood\s*bank\s*grouping)\b"""
    )
    private val combinedGroup = Regex(
        """(?im)^\s*(?:final\s+)?(?:result|interpretation|blood\s+group(?:ing)?|abo\s*(?:/|and\s*)\s*rh)\s*[:\-]\s*(AB|A|B|O)\s*(?:rh(?:esus)?(?:\s*\(d\))?\s*[:\-]?\s*)?(POSITIVE|NEGATIVE|POS|NEG)\b"""
    )
    private val signedSymbol = Regex(
        """(?im)^\s*(?:final\s+)?(?:result|blood\s+group(?:ing)?|abo\s*(?:/|and\s*)\s*rh)\s*[:\-]\s*(AB|A|B|O)\s*([+\-])\s*$"""
    )
    private val groupOnly = Regex(
        """(?im)^\s*(?:abo\s*(?:blood\s*)?group|blood\s+group(?:ing)?)\s*[:\-]\s*(AB|A|B|O)\s*$"""
    )
    private val rhesus = Regex(
        """(?im)^\s*(?:rh(?:esus)?(?:\s*\(d\))?|rh\s*factor)\s*[:\-]\s*(POSITIVE|NEGATIVE|POS|NEG)\b"""
    )
    private val screen = Regex(
        """(?im)^\s*(?:\d+\.\s*)?(?:irregular\s*)?(?:red\s*cell\s*)?antibody\s*screen(?:ing)?\s*[:\-]?\s*(NEGATIVE|POSITIVE|NON[\s-]*REACTIVE|REACTIVE)\b"""
    )

    fun bloodGrouping(text: String): VerifiedBloodGrouping? {
        val source = text.replace("\r\n", "\n")
        if (!reportHeader.containsMatchIn(source)) return null
        val combined = combinedGroup.findAll(source).map {
            it.groupValues[1].uppercase(Locale.US) to it.groupValues[2].uppercase(Locale.US)
        }.toList()
        val symbols = signedSymbol.findAll(source).map {
            it.groupValues[1].uppercase(Locale.US) to
                if (it.groupValues[2] == "+") "POSITIVE" else "NEGATIVE"
        }.toList()
        val groups = if (combined.isNotEmpty() || symbols.isNotEmpty())
            combined + symbols else {
            val abo = groupOnly.findAll(source).map { it.groupValues[1].uppercase(Locale.US) }.distinct().toList()
            val rh = rhesus.findAll(source).map { it.groupValues[1].uppercase(Locale.US) }.distinct().toList()
            if (abo.size == 1 && rh.size == 1) listOf(abo.single() to rh.single()) else emptyList()
        }
        if (groups.isEmpty()) return null
        val normalized = groups.map { (abo, rh) ->
            abo to if (rh.startsWith("POS")) "Positive" else "Negative"
        }.distinct()
        // Discordant signed conclusions must never be resolved by choosing
        // the first one. Forward/reverse controls are not an ABO conclusion.
        if (normalized.size != 1) return null
        val antibodyResults = screen.findAll(source).map {
            when(it.groupValues[1].uppercase(Locale.US).replace(Regex("[\\s-]"), "")) {
                "NEGATIVE", "NONREACTIVE" -> "Negative"
                "POSITIVE", "REACTIVE" -> "Positive"
                else -> ""
            }
        }.filter(String::isNotBlank).distinct().toList()
        return VerifiedBloodGrouping(
            group = normalized.single().first + " " + normalized.single().second,
            antibodyScreen = antibodyResults.singleOrNull().orEmpty()
        )
    }
    /** Require explicitly labelled direct/indirect antiglobulin result in a
     * patient-verified PDF; an anonymous dropdown number is never interpreted. */
    fun coombs(test:String, text:String):String? {
        val mode=when {
            test.contains("indirect",true) || test.contains("IAT",true) -> "indirect"
            test.contains("direct",true) || test.contains("DAT",true) -> "direct"
            else -> return null
        }
        val label=if(mode=="indirect")
            """(?:indirect\s+coombs(?:\s+test)?|indirect\s+antiglobulin(?:\s+test)?|\bIAT\b)"""
            else """(?:direct\s+coombs(?:\s+test)?|direct\s+antiglobulin(?:\s+test)?|\bDAT\b)"""
        val re=Regex("""(?im)^\s*(?:\d+[.)]\s*)?"""+label+
            """(?:\s*\(\s*GEL\s*\))?\s*[:=\-]?\s*(negative|positive|non[\s-]*reactive|reactive|weak\s+positive)\b""")
        val results=re.findAll(text).map { it.groupValues[1].trim() }
            .distinctBy { it.lowercase() }.toList()
        return results.singleOrNull()
    }

}