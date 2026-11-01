package org.kundakarlab.nimsresults

/** Presentation-only text splitting. No inference of fungal culture negativity. */
object FungalReportSummary {
    data class Finding(val label: String, val result: String)

    fun extract(test: String, narrative: String): List<Finding> {
        if (!Regex("""(?i)\b(?:fungal|koh|calcofluor)\b""")
                .containsMatchIn(test) || narrative.isBlank()) return emptyList()
        val source=narrative.trim().replace(Regex("""\s+""")," ")
        val preliminary=Regex(
            """(?i)\bPRELIMINARY REPORT OF FUNGAL CULTURE(?:\s+AFTER\s+ONE\s+WEEK)?\b"""
        ).find(source)
        val microscopy=if(preliminary!=null)
            source.substring(0,preliminary.range.first).trim().trimEnd('.')
        else source.trimEnd('.')
        val result=mutableListOf<Finding>()
        if(microscopy.isNotBlank()) {
            val isKoh=Regex("""(?i)\bkoh\b""").containsMatchIn(test)
            val isCalco=Regex("""(?i)calcofluor|fluorescen""").containsMatchIn(test)
            val neg=Regex("""(?i)\bNO FUNGAL ELEMENTS\b""").containsMatchIn(microscopy)
            if(neg && (isKoh || isCalco)) {
                result+=Finding(if(isKoh) "KOH" else "Calcofluor",
                    "No fungal elements seen")
            } else result+=Finding("Microscopy",microscopy)
        }
        if(preliminary!=null)
            result+=Finding("Fungal culture","Preliminary report after one week")
        return result
    }
}
