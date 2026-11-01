package org.kundakarlab.nimsresults

/**
 * Compact, display-only serology. Preserve original source, exact assay index
 * and the actual reference cutoff printed by NIMS. Never infer a laboratory
 * threshold or decode a numeric LIS choice.
 */
object SerologyReportDisplay {
    data class Finding(
        val label: String, val result: String,
        val testValue: String = "", val reference: String = ""
    )

    private val reaction=Regex(
        """(?i)\b(non[\s-]*reactive|positive|negative|reactive|indeterminate|equivocal)\b"""
    )
    private val testValue=Regex(
        """(?i)\b(IV|TV|test\s+value)\s*[:=]\s*(\d+(?:\.\d+)?)"""
    )
    private val cutoff=Regex(
        """(?i)\b(IV|TV|test\s+value)\s*(<=|>=|[<>≤≥])\s*""" +
            """(\d+(?:\.\d+)?)\s*[-–:]\s*(positive|negative|reactive|non[\s-]*reactive)\b"""
    )
    private val dengueIg=Regex("""(?i)\b(?:anti\s+)?dengue(?:\s+virus)?\s+(IgG|IgM)\b""")
    private val dengueNs1=Regex("""(?i)\b(?:dengue(?:\s+virus)?\s+)?NS1\s*(?:Ag|antigen)?\b""")
    private val leptospira=Regex("""(?i)\bleptospira\s+IgM\b""")
    private fun assay(test:String,section:String):String {
        fun sourceMethod(text:String):String {
            val dot=Regex("""(?i)\bdot\s*blot\s*ELISA\b""")
                .containsMatchIn(text)
            val elfa=Regex("""(?i)\bELFA\b""").containsMatchIn(text)
            val elisa=Regex("""(?i)\bELISA\b""").containsMatchIn(text)
            return when {
                dot && !elfa -> "Dot blot ELISA"
                elfa && !elisa -> "ELFA"
                elisa && !elfa -> "ELISA"
                else -> ""
            }
        }
        // The source narrative's specific method beats a broad LIS panel
        // title such as "HCV ELFA / ELISA".
        return sourceMethod(section).ifBlank { sourceMethod(test) }
    }
    private fun titled(label:String,test:String,section:String):String =
        label+assay(test,section).takeIf(String::isNotBlank)
            ?.let { " ("+it+")" }.orEmpty()

    fun isSerology(test:String):Boolean =
        Regex("""(?i)\b(?:dengue|hiv|hcv|hepatitis\s+c|leptospira|NS1)\b""")
            .containsMatchIn(test)

    fun extract(test: String, narrative: String): List<Finding> {
        if(!isSerology(test) || narrative.isBlank()) return emptyList()
        val source=narrative.replace(Regex("""\s+""")," ").trim()
        val dengue=dengueIg.findAll(source).toList()
        if(dengue.isNotEmpty()) {
            // Explicit analyte labels are essential: two immunoglobulins can
            // be reported in the very same HIS text field.
            return dengue.mapNotNull { m ->
                val next=dengue.firstOrNull { it.range.first > m.range.first }
                    ?.range?.first ?: source.length
                val section=source.substring(m.range.last+1,next)
                finding(titled("Dengue "+m.groupValues[1],test,section),section)
            }
        }
        if(dengueNs1.containsMatchIn(source) || test.contains("NS1",true))
            return finding(titled("Dengue NS1 Ag",test,source),source)?.let(::listOf)
                ?: emptyList()
        if(leptospira.containsMatchIn(source) ||
            test.contains("leptospira",true))
            return finding(titled("Leptospira IgM",test,source),source)?.let(::listOf)
                ?: emptyList()
        if(Regex("""(?i)\bHCV\b|hepatitis\s+c""").containsMatchIn(test))
            return finding(titled("HCV antibody",test,source),source)?.let(::listOf)
                ?: emptyList()
        if(Regex("""(?i)\bHIV\b""").containsMatchIn(test))
            return finding(titled("HIV",test,source),source)?.let(::listOf)
                ?: emptyList()
        return emptyList()
    }

    private fun finding(label: String, raw: String): Finding? {
        val section=raw.trim()
        // Cutoff text often contains another "Positive" or "Negative":
        // use only the explicit clinical result BEFORE that reference.
        val referenceMatches=cutoff.findAll(section).toList()
        val clinical=section.take(referenceMatches.firstOrNull()?.range?.first ?: section.length)
        val match=reaction.find(clinical) ?: return null
        val result=match.value.lowercase().replace(Regex("""\s+"""),"-")
            .replaceFirstChar { it.uppercase() }
        val measured=testValue.find(clinical)?.let { m ->
            val type=if(m.groupValues[1].contains("test",true)) "TV"
                else m.groupValues[1].uppercase()
            type+" "+m.groupValues[2]
        }.orEmpty()
        // Keep the PDF/HIS comparison operator, precision and reference
        // interpretation. Do not apply a generic manufacturer's cutoff.
        val reference=referenceMatches.map { m ->
            m.value.trim().replace(Regex("""\s+""")," ")
        }.distinct().joinToString(" · ")
        return Finding(label,result,measured,reference)
    }
}
