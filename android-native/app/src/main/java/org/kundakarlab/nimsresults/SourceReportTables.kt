package org.kundakarlab.nimsresults

/**
 * Strict display-only interpretation of LABELLED BioFire / blood bank table rows.
 * A result value with an opaque LIS code is not a pathogen or an ABO result.
 * Original source text and PDF remain intact; this never writes LabResults.
 */
data class SourceTableCell(val label: String, val value: String, val sourceLine: String)

object SourceReportTables {
    private val codedLabel = Regex("""(?i)^(?:combo\d+|com\d+|cb\d+|textbox\d*|textarea\d*|result|control|\d+)$""")
    private val detected = Regex("""(?i)^(?:not\s+detected|detected|negative|positive|non[\s-]*reactive|reactive|invalid|equivocal|inconclusive|indeterminate)$""")
    private val bloodReaction = Regex("""(?i)^(?:0|[1-4]\+|\+{1,4}|negative|positive|weak(?:\s+positive)?|mixed[\s-]*field|non[\s-]*reactive|reactive|not\s+tested|nt)$""")
    private val cellDelimiter = Regex("""\s*\|\s*|\t+|\s{2,}""")
    private val bloodLabel = Regex("""(?i)^(?:anti[\s-]*a|anti[\s-]*b|anti[\s-]*ab|anti[\s-]*d|rh(?:\(d\))?|a1?\s*cells?|b\s*cells?|o\s*cells?|forward\s*(?:grouping|type)|reverse\s*(?:grouping|type)|antibody\s*screen(?:ing)?(?:\s*[12])?|abo(?:/rh)?(?:\s*grouping)?|blood\s*group(?:ing)?|final\s*result|interpretation|result\s*of\s*blood\s*group(?:ing)?|method|controls?(?:\s*\d+)?|remarks?|comments?)$""")
    private val header = Regex("""(?i)^(?:test|target|pathogen|organism|analyte|interpretation|result|finding|reaction|method|comments|remarks|panel|normal\s*range|reference)$""")

    private fun label(value: String) = value.trim().trimEnd(':').replace(Regex("""\s+"""), " ")
    private fun split(line: String): Pair<String,String>? {
        val source = line.trim()
        if (source.isEmpty()) return null
        val cells = source.split(cellDelimiter).map(String::trim).filter(String::isNotBlank)
        if (cells.size >= 2) return cells.first() to cells.drop(1).joinToString(" ")
        val colon = Regex("""^([^:]{2,120})\s*:\s*(\S.*)$""").matchEntire(source)
        return colon?.let { it.groupValues[1] to it.groupValues[2] }
    }
    private fun lines(value: String): List<String> =
        value.replace("\r\n","\n").replace(Regex("""(?i)<br\s*/?>"""),"\n")
            .replace(Regex("""(?i)</(?:tr|p|div)>"""),"\n")
            .split('\n').map(String::trim).filter(String::isNotBlank)

    /** Never derive a BioFire target from 'combo'/'cb' and never call a control
     * a pathogen. The source must explicitly name the target and its result. */
    fun biofire(text: String): List<SourceTableCell> = lines(text).mapNotNull { line ->
        val (rawName,rawValue) = split(line) ?: return@mapNotNull null
        val name = label(rawName)
        val value = rawValue.trim()
        if (name.length !in 4..120 || codedLabel.matches(name) ||
            header.matches(name) || !name.any(Char::isLetter) ||
            name.contains("control",true) || !detected.matches(value)) null
        else SourceTableCell(name,value,line)
    }

    /** BGT: report each explicitly labelled reaction, not an inferred group.
     * ABO/Rh conclusions are separately extracted by PdfClinicalSummaries. */
    fun bloodGrouping(text: String): List<SourceTableCell> =
        lines(text).mapNotNull { line ->
            val (rawName,rawValue) = split(line) ?: return@mapNotNull null
            val name=label(rawName)
            val value=rawValue.trim()
            if (!bloodLabel.matches(name) || value.isEmpty() || value.length > 90) null
            else {
                val conclusion = Regex("""(?i)\b(?:AB|A|B|O)\s*(?:(?:Rh\s*)?(?:positive|negative|pos|neg)|[+\-])\b""")
                val reactions = value.split(Regex("""\s+"""))
                val isTableReactions = reactions.size in 2..6 &&
                    reactions.all { bloodReaction.matches(it) }
                val sourceNote = Regex("""(?i)^(?:method|controls?(?:\s*\d+)?|remarks?|comments?)$""")
                    .matches(name) && value.length <= 120
                val admissible = bloodReaction.matches(value) ||
                    isTableReactions || sourceNote ||
                    detected.matches(value) || conclusion.containsMatchIn(value) ||
                    (name.contains("abo",true) && value.matches(Regex("""(?i)^(?:AB|A|B|O)$"""))) ||
                    (name.contains("screen",true) && value.length <= 35)
                if (!admissible) null else SourceTableCell(name,value,line)
            }
        }
}
