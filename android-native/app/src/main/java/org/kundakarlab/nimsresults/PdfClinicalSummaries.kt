package org.kundakarlab.nimsresults

/** Only statements explicitly written in the patient's own source PDF. */
data class VerifiedBloodGrouping(val group: String, val antibodyScreen: String = "")

object PdfClinicalSummaries {
    fun bloodGrouping(text: String): VerifiedBloodGrouping? {
        if (!Regex("""(?i)\b(?:blood\s+grouping|grouping\s+and\s+(?:antibody\s+)?screening)\b""")
                .containsMatchIn(text)) return null
        val group = Regex(
            """(?i)\bRESULT\s*:\s*(AB|A|B|O)\s*(POSITIVE|NEGATIVE)\b"""
        ).find(text) ?: return null
        val screen = Regex(
            """(?i)\bAntibody\s+Screening\s*[:\-]?\s*(Negative|Positive)\b"""
        ).find(text)?.groupValues?.getOrNull(1).orEmpty()
        return VerifiedBloodGrouping(
            group.groupValues[1].uppercase() + " " +
                group.groupValues[2].lowercase().replaceFirstChar(Char::uppercaseChar),
            screen.replaceFirstChar(Char::uppercaseChar)
        )
    }
}
