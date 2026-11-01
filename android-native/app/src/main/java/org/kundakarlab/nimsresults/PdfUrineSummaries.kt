package org.kundakarlab.nimsresults

/**
 * Source-verified, display-only extraction of ordinary urine chemistry.
 * Call only after the same patient CR and single PDF match have been checked.
 * Does not decode arbitrary HIS dropdown IDs; values come from labelled PDF.
 */
object PdfUrineSummaries {
    data class Field(val label:String, val result:String)
    private val fields=listOf(
        "pH" to Regex("""(?i)^\s*(?:urine\s*)?p\s*H\s*[:=]?\s*(\d(?:\.\d+)?)\s*(?:$|\s|\t)"""),
        "Specific gravity" to Regex("""(?i)^\s*(?:urine\s*)?specific\s+gravity\s*[:=]?\s*(1\.\d{2,3})\s*(?:$|\s|\t)"""),
        "Ketones" to Regex("""(?i)^\s*(?:urine\s*)?ketones?\s*[:=]?\s*(negative|positive|nil|trace|small|moderate|large|\+{1,4})(?=\s|$)"""),
        "Protein" to Regex("""(?i)^\s*(?:urine\s*)?protein\s*[:=]?\s*(negative|positive|nil|trace|\+{1,4}|\d+(?:\.\d+)?\s*(?:mg/dl|g/l))(?=\s|$)"""),
        "Nitrite" to Regex("""(?i)^\s*(?:urine\s*)?nitrites?\s*[:=]?\s*(negative|positive|nil)\b"""),
        "Glucose" to Regex("""(?i)^\s*(?:urine\s*)?(?:(?:sugar\s*[:=]?\s*\()?glucose\)?|glucose)\s*[:=]?\s*(negative|positive|nil|trace|\+{1,4})(?=\s|$)""")
    )
    fun extract(text:String):List<Field> {
        val result=mutableListOf<Field>()
        val lines=text.replace("\r\n","\n").lines()
        for((label,regex) in fields) {
            val candidates=lines.mapNotNull { line ->
                regex.find(line)?.groupValues?.getOrNull(1)?.trim()
            }.distinct()
            if(candidates.size==1) result+=Field(label,candidates.single())
        }
        return result
    }
}
