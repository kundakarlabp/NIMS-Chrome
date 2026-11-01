package org.kundakarlab.nimsresults

/**
 * Display-only reconciliation of a labelled NIMS fluid-chemistry PDF.
 * A same-CR document is not enough: match two or more exact biochemical
 * values (including < qualifiers) to the one bulk study before attribution.
 * Keeps the original HIS rows unchanged and never translates LIS combo IDs.
 */
object PdfFluidChemistry {
    data class Evidence(val specimen: String, val appearance: String, val matchedValues: Int)
    private val specimenName=Regex(
        """(?i)\b(?:ascitic(?:\s+fluid)?|pleural(?:\s+fluid)?|cerebrospinal\s+fluid|CSF|synovial(?:\s+fluid)?|pericardial(?:\s+fluid)?|peritoneal\s+fluid)\b"""
    )
    private val appearanceName=Regex(
        """(?i)\b(?:straw[\s-]*colou?red|yellow|clear|hazy|turbid|cloudy|bloody|blood[\s-]*stained|haemorrhagic|hemorrhagic|purulent|milky|colourless|colorless|amber)\b"""
    )
    // A bounded table-cell read, not a search for a clinical word elsewhere
    // in the document, prevents a panel heading ("CSF/Pleural/Ascitic")
    // from becoming the specimen.
    private fun namedCell(text: String, field: String, value: Regex): String {
        val pattern=Regex(
            """(?is)\b""" + field + """\b\s*(?:[:=]|\||\s){0,15}\s*""" +
                """([^\r\n]{1,90})"""
        )
        val labels=pattern.findAll(text).mapNotNull { match ->
            val end=match.groupValues[1]
                .split("|", "\t", "   ").first().trim()
            value.find(end)?.value
        }.distinctBy(String::lowercase).toList()
        return labels.singleOrNull().orEmpty()
    }
    private fun evidenceName(text: String): String {
        val label=namedCell(text, """nature\s+of\s+specimen""", specimenName)
        return when(label.lowercase().replace(Regex("""\s+""")," ")) {
            "ascitic", "ascitic fluid", "peritoneal fluid" -> "Ascitic fluid"
            "pleural", "pleural fluid" -> "Pleural fluid"
            "cerebrospinal fluid", "csf" -> "CSF"
            "synovial", "synovial fluid" -> "Synovial fluid"
            "pericardial", "pericardial fluid" -> "Pericardial fluid"
            else -> ""
        }
    }
    private fun norm(value: String): String {
        val text=value.trim().lowercase()
            .replace(Regex("""(?i)^(?:less\s+than|lt)\s*"""),"<")
            .replace(Regex("""(?i)^greater\s+than\s*"""),">")
            .replace(Regex("""\s+"""),"")
        val parsed=Regex("""^([<>]?)(\d+(?:\.\d+)?)$""").matchEntire(text)
            ?: return text
        return parsed.groupValues[1]+
            parsed.groupValues[2].toBigDecimalOrNull()?.stripTrailingZeros()?.toPlainString()
    }
    private fun analyte(name: String): String? {
        val clean=name.lowercase().replace(Regex("""[^a-z]"""),"")
        return when {
            clean.startsWith("glucose") -> "glucose"
            clean.startsWith("protein") -> "protein"
            clean.startsWith("albumin") -> "albumin"
            else -> null
        }
    }
    /**
     * Finds result cells, not reference intervals. Rejects ambiguity when
     * the same analyte appears in distinct sections with different results.
     * NIMS's blank CSF subsection has no numeric value and is ignored.
     */
    private fun pdfValues(text: String): Map<String, String> {
        val found=linkedMapOf<String,MutableSet<String>>()
        val lines=text.replace("\r\n","\n").lines()
        val pattern=Regex(
            """(?i)^\s*(glucose|protein|albumin)\s*\.?\s*(?:[:=]|\||\s){0,12}\s*""" +
                """([<>]?\s*\d+(?:\.\d+)?)\b"""
        )
        for(line in lines) {
            val match=pattern.find(line) ?: continue
            val name=match.groupValues[1].lowercase()
            found.getOrPut(name) { linkedSetOf() }.add(norm(match.groupValues[2]))
        }
        return found.mapNotNull { (name, values) ->
            values.singleOrNull()?.let { name to it }
        }.toMap()
    }
    fun reconcile(rows: List<LabResult>, extractedText: String,
                  tableText: String = ""): Evidence? {
        if (extractedText.isBlank() && tableText.isBlank()) return null
        val clinical=rows.mapNotNull { row ->
            analyte(row.parameter)?.let { it to norm(row.valueText) }
        }.toMap()
        if (clinical.size < 2) return null
        val source=if(tableText.contains("Nature",true) &&
            tableText.contains("Specimen",true)) tableText+"\n"+extractedText
            else extractedText+"\n"+tableText
        val specimen=evidenceName(source)
        if(specimen.isBlank()) return null
        val appearance=namedCell(source, """physical\s+appearance""", appearanceName)
        val values=pdfValues(source)
        // An ambiguous or conflicting value is not a positive match.
        if (values.any { (name, value) ->
                clinical.containsKey(name) && clinical[name]!=value
            }) return null
        val matching=values.count { (name,value) -> clinical[name]==value }
        if(matching<2) return null
        return Evidence(specimen,appearance,matching)
    }
}
