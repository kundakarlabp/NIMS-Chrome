package org.kundakarlab.nimsresults

/** Display-only evidence from patient-checked PDF text. Never decodes HIS codes. */
object PdfClinicalEvidence {
    private val specimenRegex=Regex(
        """(?im)^\s*(?:sample\s*type\s*/\s*no\.?|specimen\s*(?:type|source)|sample\s*type|specimen)\s*:\s*([^\r\n]+)""")
    private val specimenTypes=listOf(
        "pleural fluid" to "Pleural fluid", "pleural" to "Pleural fluid",
        "cerebrospinal fluid" to "CSF", "csf" to "CSF",
        "synovial fluid" to "Synovial fluid", "synovial" to "Synovial fluid",
        "joint fluid" to "Synovial fluid", "ascitic fluid" to "Ascitic fluid",
        "ascitic" to "Ascitic fluid", "peritoneal fluid" to "Ascitic fluid",
        "pericardial fluid" to "Pericardial fluid", "pericardial" to "Pericardial fluid"
    )
    fun specimen(text:String):String {
        val raw=specimenRegex.findAll(text).map { it.groupValues[1].trim() }
            .filter(String::isNotBlank).toList()
        val mapped=raw.mapNotNull { label ->
            val normalized=label.substringBefore('/').trim().lowercase()
            specimenTypes.firstOrNull { (key,_) ->
                normalized==key || normalized.startsWith(key+" ")
            }?.second
        }.distinct()
        return mapped.singleOrNull().orEmpty()
    }
    fun coombs(test:String,document:String):String? {
        // A signed report may place Result on a separate line from the test.
        PdfClinicalSummaries.coombs(test,document)?.let { return it }
        val expected=when {
            test.contains("indirect",true) -> "indirect"
            test.contains("direct",true) -> "direct"
            else -> return null
        }
        val other=if(expected=="direct") "indirect" else "direct"
        val section=Regex(
            """(?is)(?:^|\n)\s*"""+expected+
                """\s+(?:coombs|antiglobulin)(?:\s+test)?(?:\s*\(GEL\))?""" +
                """\s*(?:[:=\-]|\r?\n|\s)+\s*(?:(?:Result|Observation)\s*[:=\-]?\s*)?""" +
                """(negative|positive|non[\s-]*reactive|reactive|weak\s+positive)\b"""
        ).findAll(document).map { it.groupValues[1].trim() }
            .distinctBy { it.lowercase() }.toList()
        if(section.size!=1) return null
        // Avoid using conclusions from a document with both coombs modalities
        // when the parts cannot be individually established.
        if(Regex("""(?i)\b"""+other+"""\s+(?:coombs|antiglobulin)""")
                .containsMatchIn(document)) return null
        return section.single().replaceFirstChar { it.uppercase() }
    }
}
