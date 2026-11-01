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
            val elements=label.split('/').map(String::trim)
            // "Pleural Fluid/P5631" is a named sample plus accession;
            // "CSF/Pleural/Ascitic" is a generic panel heading, NOT a sample.
            if (elements.drop(1).any { item ->
                    specimenTypes.any { (name,_) ->
                        item.equals(name,true) || item.startsWith(name+" ",true)
                    }
                }) return@mapNotNull null
            val normalized=elements.first().lowercase()
            specimenTypes.firstOrNull { (key,_) ->
                normalized==key || normalized.startsWith(key+" ")
            }?.second
        }.distinct()
        return mapped.singleOrNull().orEmpty()
    }
    /**
     * Resolve DAT and IAT independently, even when both sections are in one
     * CR-verified PDF. Never infer results from com1/com2/dropdown codes.
     * Reject conflicting or unlabelled conclusions.
     */
    fun coombs(test: String, document: String): String? {
        val expected = when {
            Regex("""(?i)\b(?:indirect\s+(?:coombs|antiglobulin)|IAT|ICT)\b""")
                .containsMatchIn(test) -> "indirect"
            Regex("""(?i)\b(?:direct\s+(?:coombs|antiglobulin)|DAT|DCT)\b""")
                .containsMatchIn(test) -> "direct"
            else -> return null
        }
        if (document.isBlank()) return null
        val source = document.replace("\r\n", "\n")
        // Heading must identify the specific modality: a "Result: Negative"
        // elsewhere on the page is not sufficient for DAT or IAT.
        val heading = Regex(
            """(?im)^\s*(?:\d+[.)]\s*)?""" +
                """((?:direct|indirect)\s+(?:coombs|antiglobulin)(?:\s+test)?|DAT|DCT|IAT|ICT)""" +
                """(?:\s*\(\s*(?:GEL|DAT|IAT)\s*\))?(?=\s|[:=\-]|$)"""
        )
        val parts = heading.findAll(source).toList()
        if (parts.isEmpty()) return null
        val word = Regex("""(?i)\b(weak\s+positive|non[\s-]*reactive|negative|positive|reactive)\b""")
        val answers = mutableListOf<String>()
        parts.forEachIndexed { index, match ->
            val foundMode = when {
                match.groupValues[1].startsWith("indirect",true) ||
                    match.groupValues[1].equals("IAT",true) ||
                    match.groupValues[1].equals("ICT",true) -> "indirect"
                else -> "direct"
            }
            if (foundMode != expected) return@forEachIndexed
            val end = parts.getOrNull(index + 1)?.range?.first ?: source.length
            // A section is limited to its own result header and adjacent
            // output; not distant reference ranges, comments or later tests.
            val section = source.substring(match.range.last + 1, end)
                .take(650).lines().take(9)
            val observations = mutableListOf<String>()
            section.forEachIndexed { n, line ->
                val clean = line.trim()
                if (clean.isEmpty()) return@forEachIndexed
                if (Regex("""(?i)^(?:reference|normal|method|remarks?|comments?|interpretation\s+guide)\b""")
                        .containsMatchIn(clean)) return@forEachIndexed
                val labelled = Regex(
                    """(?i)^(?:[:=\-]\s*)?(?:(?:final\s+)?(?:result|observation|interpretation|finding)\s*[:=\-]?\s*)?""" +
                        """(weak\s+positive|non[\s-]*reactive|negative|positive|reactive)\s*[.]?$"""
                ).find(clean)
                if (labelled != null) observations += labelled.groupValues[1]
                else if (n < 5) {
                    // Report tables sometimes put "Result" on one line
                    // and the value immediately below it.
                    val prior = section.getOrNull(n - 1)?.trim().orEmpty()
                    if (Regex("""(?i)^(?:result|observation|interpretation)\s*[:=\-]?$""")
                            .matches(prior)) {
                        word.find(clean)?.groupValues?.get(1)?.let(observations::add)
                    }
                }
            }
            val unique = observations.map {
                it.trim().replace(Regex("""\s+"""), " ").lowercase()
            }.distinct()
            if (unique.size == 1) answers += unique.single()
        }
        val uniqueResults = answers.distinct()
        if (uniqueResults.size != 1) return null
        return uniqueResults.single().replaceFirstChar { it.uppercase() }
    }

}
