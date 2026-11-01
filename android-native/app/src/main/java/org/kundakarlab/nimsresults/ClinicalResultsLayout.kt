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
        val isCoag = CoagulationDisplay.isPTPanel(test) ||
            CoagulationDisplay.isAPTTPanel(test) || t=="inr" ||
            t=="ptaptt"
        if (!isLft && !isCbc && !isCoag) return rows
        return rows.withIndex().sortedWith(compareBy<IndexedValue<LabResult>> {
            // Order by the normalized clinical display label so NIMS
            // variants such as "Sr. Alk. Phosphatase" still sort as ALP.
            val p = key(ClinicalChronology.parameterLabel(it.value))
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
                isCoag -> CoagulationDisplay.sortPriority(test,it.value)
                else -> when {
                    p == "hb" || p.contains("hemoglobin") || p.contains("haemoglobin") -> 0
                    p == "tlc" || p == "tlccount" || p == "wbc" ||
                        p.contains("totalleuco") || p.contains("totalleuko") ||
                        p.contains("totalwhite") || p.contains("whitebloodcell") -> 1
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

    /**
     * A routine value-only study is already readable without its PDF.
     * PDF inventory remains accessible in the dedicated Source PDFs screen.
     */
    fun omitRoutinePdf(test: String, rows: List<LabResult>): Boolean {
        if (rows.isEmpty() || ClinicalReportRenderer.isMicrobiologyInvestigation(test)) return false
        val t = key(test)
        if (Regex("""(?i)^(?:cbc|cbp|hemogram|haemogram|completehemogram|completehaemogram|completeblood|lft|creatinine|serumcreatinine|serumurea|urea|electrolyte|liverfunction|renalfunction|kidneyfunction|lipidprofile|iron|ferritin|ldh|lactatedehydrogenase|thyroidfunction|prothrombintime|ptinr|ptaptt|inr|esr|serumcalcium|calcium|serummagnesium)""").containsMatchIn(t))
            return true
        // Other uncomplicated biochemistry panels, including single numeric
        // analytes; do not suppress PDFs for interpretive narrative reports.
        return rows.all { row ->
            row.valueText.trim().toDoubleOrNull() != null &&
                row.valueText.length <= 18
        }
    }

    /** Raw NIMS dropdown integers are visible in a separate urine chemistry
     * section without mistaking code 1 for pH 1 or code 7 for SG 7. */
    fun urineChemistryCode(row: LabResult): Boolean {
        if (!ClinicalReportRenderer.isUrineRoutine(row.test)) return false
        val p = key(row.parameter)
        return p in setOf("phresult","specificgravityresult","ketonesresult",
            "ketoneresult","nitriteresult","proteinresult","glucoseresult",
            "bloodresult","bilirubinresult","urobilinogenresult") &&
            row.valueText.trim().isNotEmpty() && row.valueText.trim() != "-1" &&
            ClinicalReportRenderer.isUninterpretedCode(row)
    }

    fun urineChemistryLabel(row: LabResult): String = when(key(row.parameter)) {
        "phresult" -> "pH"
        "specificgravityresult" -> "Specific gravity"
        "ketoneresult", "ketonesresult" -> "Ketones"
        "nitriteresult" -> "Nitrite"
        "proteinresult" -> "Protein"
        "glucoseresult" -> "Glucose"
        "bloodresult" -> "Blood"
        "bilirubinresult" -> "Bilirubin"
        "urobilinogenresult" -> "Urobilinogen"
        else -> row.parameter
    }

    fun urineCodeDisplay(row: LabResult): String =
        row.valueText.trim() + " (HIS code)"

    /** Only fields actually recorded in this result or explicitly labelled
     * in the same culture narrative. A source-index collection date is never
     * mislabeled as date sent. */
    fun cultureProvenance(rows: List<LabResult>, sourceIndex: List<ReportRef> = emptyList()): List<String> {
        val narrative = rows.mapNotNull { MicrobiologyNarrativeMetadata.extract(it) }
        val lab = rows.map { it.labNumber.trim() }.firstOrNull(String::isNotBlank)
            ?: narrative.map { it.labNumber }.firstOrNull(String::isNotBlank).orEmpty()
        val sample = rows.map { it.sampleNumber.trim() }.firstOrNull(String::isNotBlank)
            ?: narrative.map { it.sampleNumber }.firstOrNull(String::isNotBlank).orEmpty()
        val sent = rows.map { it.sentDate.trim() }.firstOrNull(String::isNotBlank)
            ?: narrative.map { it.sentDate }.firstOrNull(String::isNotBlank).orEmpty()
        val reported = rows.map { it.reportedDate.trim() }.firstOrNull(String::isNotBlank)
            ?: narrative.map { it.reportedDate }.firstOrNull(String::isNotBlank).orEmpty()
        // This list is already filtered by lab accession or same-date/title
        // in SourceMetadataReview.pdfCandidates. Never borrow provenance
        // when there are multiple candidates (bottles/stages may differ).
        val match = sourceIndex.singleOrNull()?.takeIf { candidate ->
            lab.isBlank() || candidate.labNumber.equals(lab,true)
        }
        val trueSent = sent.ifBlank { match?.sentDate.orEmpty() }
        return listOfNotNull(
            lab.takeIf(String::isNotBlank)?.let { "Lab " + it }
                ?: match?.labNumber?.takeIf(String::isNotBlank)
                    ?.let { "Source-index lab " + it },
            sample.takeIf(String::isNotBlank)?.let { "Sample " + it }
                ?: match?.sampleNumber?.takeIf(String::isNotBlank)
                    ?.let { "Source-index sample " + it },
            trueSent.takeIf(String::isNotBlank)?.let { "Sent " + it },
            reported.takeIf(String::isNotBlank)?.let { "Reported " + it },
            match?.collectionDateTime?.takeIf { it.isNotBlank() }
                ?.let { "Source-index collected " + it }
        )
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
