package org.kundakarlab.nimsresults

import java.util.Locale

/** Presentation only. Never replace the HIS original values or narratives. */
object ClinicalResultsLayout {
    private fun key(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]"), "")

    fun omitRedundantHeading(title: String, rows: List<LabResult>): Boolean {
        if (rows.size != 1 || ClinicalReportRenderer.isMicrobiologyInvestigation(title)) return false
        val label = key(ClinicalChronology.parameterLabel(rows.single()))
        val heading = key(title)
        return heading.isNotBlank() && (heading == label ||
            (heading == "serumcreatinine" && label == "creatinine") ||
            (heading == "serumurea" && label == "urea"))
    }

    fun ordered(test: String, rows: List<LabResult>): List<LabResult> {
        val t = key(test)
        val isLft = "liverfunction" in t || t == "lft"
        val isCbc = t.startsWith("cbc") || "hemogram" in t || "haemogram" in t ||
            "completeblood" in t
        val isCoag = "prothrombin" in t || t.contains("ptinr") ||
            t == "ptaptt" || t == "inr"
        if (!isLft && !isCbc && !isCoag) return rows
        return rows.withIndex().sortedWith(compareBy<IndexedValue<LabResult>> {
            val p = key(it.value.parameter)
            when {
                isLft -> when {
                    p.contains("bilirubin") && p.contains("total") -> 0
                    p.contains("bilirubin") && (p.contains("direct") || p.contains("conjugated")) -> 1
                    p == "ast" || p.contains("sgot") -> 2
                    p == "alt" || p.contains("sgpt") -> 3
                    p == "alp" || p.contains("alkalinephosphatase") -> 4
                    p.contains("totalprotein") -> 5
                    p.contains("albumin") -> 6
                    p.contains("globulin") -> 7
                    else -> 30
                }
                isCoag -> when {
                    p.contains("ptpatient") || (p.contains("prothrombintime") &&
                        p.contains("patient")) -> 0
                    p.contains("ptcontrol") || (p.contains("prothrombintime") &&
                        p.contains("control")) -> 1
                    p == "inr" || p == "ptinr" -> 2
                    p.contains("apttpatient") -> 3
                    p.contains("apttcontrol") -> 4
                    else -> 20
                }
                else -> when {
                    p == "hb" || p.contains("hemoglobin") || p.contains("haemoglobin") -> 0
                    p == "tlc" || p == "wbc" || p.contains("totalleukocyte") ||
                        p.contains("totalwhiteblood") -> 1
                    p.contains("neutrophil") -> 2
                    p.contains("lymphocyte") -> 3
                    p.contains("monocyte") -> 4
                    p.contains("eosinophil") -> 5
                    p.contains("basophil") -> 6
                    (p == "platelets" || p.contains("plateletcount")) &&
                        it.value.valueText.trim().toDoubleOrNull() != null -> 7
                    p == "redcellcount" || p == "rbccount" || p == "rbc" -> 8
                    p == "pcv" || p.contains("hematocrit") -> 9
                    p == "mcv" -> 10
                    p == "mch" -> 11
                    p == "mchc" -> 12
                    p.startsWith("rdw") -> 13
                    p.contains("nrbc") -> 14
                    p.contains("peripheralsmear") -> 90
                    p == "rbcs" || p.startsWith("rbcmorphology") -> 91
                    p == "wbcs" || p.startsWith("wbcmorphology") -> 92
                    p.startsWith("platelets") || p.contains("plateletadequacy") -> 93
                    p.contains("others") -> 95
                    else -> 40
                }
            }
        }.thenBy { it.index }).map { it.value }
    }

    data class Narrative(val sections: List<SourceSection>, val reporter: String)
    /** Split the explicit headings of flattened NIMS imaging narratives. */
    fun radiology(text: String): Narrative {
        val raw = text.trim()
        if (raw.isEmpty()) return Narrative(emptyList(), "")
        val reporterMatch = Regex(
            """(?i)\b(DR\.?\s+[A-Z][A-Z.\s]{2,45}\((?:SR|JR|ASSISTANT|CONSULTANT)\))\s*$"""
        ).find(raw)
        val reporter = reporterMatch?.value?.trim().orEmpty()
        val body = if (reporterMatch == null) raw else raw.take(reporterMatch.range.first).trim()
        val headings = Regex(
            """(?i)\b(EXAMINATION\s+PERFORMED\s+ON|TECHNIQUE|PROTOCOL|FINDINGS|OBSERVATIONS|IMPRESSION|CONCLUSION|RECOMMENDATIONS?)\s*[:\-]?\s+"""
        ).findAll(body).toList()
        if (headings.isEmpty()) return Narrative(listOf(SourceSection("Report",body)),reporter)
        val parts = mutableListOf<SourceSection>()
        val leading = body.substring(0,headings.first().range.first).trim()
        if (leading.isNotBlank()) parts += SourceSection("Study details",leading)
        headings.forEachIndexed { i, marker ->
            val start = marker.range.last + 1
            val end = headings.getOrNull(i+1)?.range?.first ?: body.length
            val value = body.substring(start,end).trim()
            if (value.isNotBlank()) parts += SourceSection(
                marker.groupValues[1].lowercase(Locale.ROOT).replaceFirstChar { it.uppercase() },
                value
            )
        }
        return Narrative(parts, reporter)
    }

    data class CultureDisplay(val accession: String, val stage: String,
                              val set: String, val bottle: String,
                              val result: String, val guidance: String)
    fun culture(test: String, raw: String): CultureDisplay {
        val lab = Regex("""(?i)^\s*([A-Z]{1,3}\d{4,8})\s+CULTURE\s+REPORT\b""")
            .find(raw)?.groupValues?.get(1).orEmpty()
        val set = Regex("""(?i)(\d+)\s*(?:st|nd|rd|th)?\s*set\b""")
            .find(test)?.groupValues?.get(1).orEmpty()
        val bottle = Regex("""(?i)(\d+)\s*(?:st|nd|rd|th)?\s*bottle\b""")
            .find(test)?.groupValues?.get(1).orEmpty()
        val stage = when {
            Regex("""(?i)\b(?:final|amended)\b""").containsMatchIn(test) ->
                if(test.contains("amended",true)) "Amended" else "Final"
            Regex("""(?i)\b(?:48\s*h(?:ou)?rs?|48h)\b""").containsMatchIn(test) -> "48-hour interim"
            Regex("""(?i)\b(?:24\s*h(?:ou)?rs?|24h)\b""").containsMatchIn(test) -> "24-hour interim"
            else -> "Stage not supplied"
        }
        val delimiter=Regex(
            """(?i)\b(?:INSTRUCTIONS\s+FOR\s+(?:BLOOD\s+)?CULTURE\s+COLLECTION|TREATMENT\s*:|RECOMMENDATIONS?\s*:)"""
        ).find(raw)
        val before = if(delimiter==null) raw else raw.take(delimiter.range.first)
        val after = if(delimiter==null) "" else raw.drop(delimiter.range.first)
        val cleaned=before.replace(Regex("""(?i)^\s*(?:[A-Z]{1,3}\d{4,8}\s+)?CULTURE\s+REPORT\s*"""),"").trim()
        return CultureDisplay(lab,stage,set,bottle,cleaned,after.trim())
    }
}
