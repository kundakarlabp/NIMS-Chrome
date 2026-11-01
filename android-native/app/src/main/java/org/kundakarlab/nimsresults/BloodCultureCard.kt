package org.kundakarlab.nimsresults

/** Display-only rules. Report stage is not inferred from future final-report instructions. */
object BloodCultureCard {
    fun isBloodCulture(title:String):Boolean =
        Regex("""(?i)\b(?:blood\s+culture|fan\s+blood\s+culture)\b""")
            .containsMatchIn(title)

    fun stage(title:String, sourceStage:String="", narrative:String=""):String {
        if(!isBloodCulture(title)) return ""
        val text=title+" "+narrative
        // This sentence is a FUTURE reporting instruction, not a final result.
        val actualFinal=Regex(
            """(?i)\b(?:blood\s+)?culture\s+shows\s+no\s+growth""" +
                """[^\n.]{0,100}\bafter\s+(?:5|five)\s+days?\b"""
        ).containsMatchIn(narrative)
        if(actualFinal) return "Final"
        if(Regex("""(?i)\b(?:36\s*(?:to|-)\s*48|48)\s*h(?:ours?|rs?)\b""")
                .containsMatchIn(text)) return "48 h interim"
        if(Regex("""(?i)\b24\s*h(?:ours?|rs?)\b""")
                .containsMatchIn(title)) return "24 h interim"
        val explicit=sourceStage.trim().lowercase()
        return when {
            explicit=="final" || explicit=="amended" ->
                explicit.replaceFirstChar { it.uppercase() }
            explicit=="preliminary" || explicit=="interim" -> "Interim"
            Regex("""(?i)\b(?:final|amended)\s+(?:culture\s+)?report\b""")
                .containsMatchIn(title) -> "Final"
            else -> ""
        }
    }

    fun title(original:String, sourceStage:String=""):String {
        if(!isBloodCulture(original)) return original
        val set=Regex("""(?i)\b(\d+)\s*(?:st|nd|rd|th)?\s*set\b""")
            .find(original)?.groupValues?.get(1).orEmpty()
        val bottle=Regex("""(?i)\b(\d+)\s*(?:st|nd|rd|th)?\s*bottle\b""")
            .find(original)?.groupValues?.get(1).orEmpty()
        val parts=mutableListOf("Blood culture")
        if(set.isNotBlank()) parts+="Set "+set
        if(bottle.isNotBlank()) parts+="Bottle "+bottle
        val reportStage=stage(original,sourceStage)
        if(reportStage.isNotBlank()) parts+=reportStage
        return parts.joinToString(" · ")
    }

    /**
     * Shorten only unambiguously stated no-growth observations.
     * Original full narrative is retained unchanged in LabResult and source PDF.
     */
    fun finding(title:String, raw:String):String {
        if(!isBloodCulture(title)) return raw
        if(Regex("""(?i)\b(?:blood\s+)?culture\s+shows\s+no\s+growth""" +
                """[^\n.]{0,100}\bafter\s+(?:5|five)\s+days?\b""")
                .containsMatchIn(raw))
            return "No aerobic growth after 5 days"
        if(Regex(
                """(?i)\bculture\s+shows\s+no\s+growth\s+aerobically\s+after\s+""" +
                    """incubation\s+for\s+about\s+36\s*(?:to|-)\s*48\s+hours?\b"""
            ).containsMatchIn(raw))
            return "No aerobic growth after 36–48 hours"
        return raw.trim()
    }
}
