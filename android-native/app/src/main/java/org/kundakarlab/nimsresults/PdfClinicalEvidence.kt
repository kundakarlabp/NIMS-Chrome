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
        val target = when {
            Regex("""(?i)\b(?:indirect\s+(?:coombs|antiglobulin)|IAT|ICT)\b""")
                .containsMatchIn(test) -> "indirect"
            Regex("""(?i)\b(?:direct\s+(?:coombs|antiglobulin)|DAT|DCT)\b""")
                .containsMatchIn(test) -> "direct"
            else -> return null
        }
        if (document.isBlank()) return null
        val source=document.replace("\r\n","\n")
        val headings=Regex(
            """(?im)^\s*(?:\d+[.)]\s*)?""" +
                """((?:direct|indirect)\s+(?:coombs|antiglobulin)(?:\s+test)?|DAT|DCT|IAT|ICT)""" +
                """(?:\s*\(\s*(?:GEL|DAT|IAT|DCT|ICT)\s*\))?(?=\s|[:=\-]|$)"""
        ).findAll(source).toList()
        if (headings.isEmpty()) return null
        val value=Regex(
            """(?i)^(weak\s+positive|traces?|non[\s-]*reactive|negative|positive|reactive|neg)\s*[.]?$"""
        )
        val labelled=Regex(
            """(?i)^(?:final\s+)?(?:result|observation|interpretation|finding)\s*[:=\-]?\s*(.*)$"""
        )
        val results=mutableListOf<String>()
        for ((i,heading) in headings.withIndex()) {
            val mode=when {
                heading.groupValues[1].startsWith("indirect",true) ||
                    heading.groupValues[1].equals("IAT",true) ||
                    heading.groupValues[1].equals("ICT",true) -> "indirect"
                else -> "direct"
            }
            if(mode!=target) continue
            val end=headings.getOrNull(i+1)?.range?.first ?: source.length
            val section=source.substring(heading.range.last+1,end)
                .take(500).lines().take(8)
            val found=mutableListOf<String>()
            for((lineIndex,rawLine) in section.withIndex()) {
                // A DAT control is a separate assay control, not the
                // patient's DAT result (e.g. "Traces   Control: Neg").
                val control=Regex("""(?i)\bcontrol\s*[:=\-]?""")
                    .find(rawLine)?.range?.first ?: rawLine.length
                val line=rawLine.substring(0,control).trim()
                    .trimStart(':','=','-').trim()
                if(line.isBlank() || Regex(
                    """(?i)^(?:reference|normal\s+range|method|comments?|remarks?|test\s+done\s+by)\b"""
                    ).containsMatchIn(line)) continue
                val candidate=labelled.find(line)?.groupValues?.getOrNull(1)?.trim() ?: line
                val direct=value.matchEntire(candidate)?.groupValues?.get(1)
                if(direct!=null) found+=direct
                else if (lineIndex>0) {
                    val previous=section[lineIndex-1].trim()
                    if(Regex("""(?i)^(?:result|observation|finding)\s*[:=\-]?$""")
                            .matches(previous)) {
                        value.find(candidate)?.groupValues?.get(1)?.let(found::add)
                    }
                }
            }
            val values=found.map { v ->
                when(v.lowercase().trim()) {
                    "neg" -> "Negative"
                    "trace", "traces" -> "Traces"
                    else -> v.lowercase().replaceFirstChar { it.uppercase() }
                }
            }.distinct()
            if(values.size>1) return null
            if(values.size==1) results+=values.single()
        }
        return results.distinct().singleOrNull()
    }

}
