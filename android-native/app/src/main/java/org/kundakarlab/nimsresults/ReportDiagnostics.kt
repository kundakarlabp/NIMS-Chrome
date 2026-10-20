package org.kundakarlab.nimsresults

import java.util.Locale

/**
 * Pasteable, redacted source-mapping diagnostics.
 * Never copy patient identifiers, lab numbers, results or PDF URLs.
 */
object ReportDiagnostics {
    private fun normalized(s: String): String =
        s.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

    fun describe(test: String, rows: List<LabResult>,
                 inventory: List<ReportRef>, cr: String): String {
        val matching = ReportLinkResolver.candidates(rows, inventory, cr)
        val normTest = normalized(test)
        val titleMatches = inventory.count {
            val title = normalized(it.title)
            title.isNotBlank() && normTest.isNotBlank() &&
                (title == normTest || (title.length > 8 && normTest.length > 8 &&
                 (title.contains(normTest) || normTest.contains(title))))
        }
        val dates = rows.map { it.date }.filter(String::isNotBlank).distinct()
        val opaque = rows.count(ClinicalReportRenderer::isUninterpretedCode)
        val placeholders = rows.count(ClinicalReportRenderer::isPlaceholder)
        val technical = rows.count(ClinicalPresentation::isTechnical)
        val checked = inventory.count {
            ReportTitleCache.header(it.url)?.crNo == cr
        }
        val status = when {
            matching.verified != null -> "accession_verified"
            matching.candidates.size > 1 -> "multiple_unverified_candidates"
            matching.candidates.size == 1 -> "one_related_but_unverified"
            rows.any { it.labNumber.isNotBlank() } -> "accession_present_no_match"
            else -> "no_verified_identity"
        }
        val safeTitle = test.replace(Regex("""(?i)\b\d{8,}\b"""), "[number]")
            .replace(Regex("""https?://\S+"""), "[url]")
            .take(90)
        return buildString {
            appendLine("NIMS report diagnostics v2 (redacted)")
            appendLine("app_version=" + BuildConfig.VERSION_NAME)
            appendLine("test=" + safeTitle)
            appendLine("result_dates=" + dates.size)
            appendLine("source_rows=" + rows.size)
            appendLine("numeric_rows=" + rows.count { it.numericValue != null })
            appendLine("qualitative_text_rows=" +
                rows.count { it.numericValue == null && it.valueText.isNotBlank() })
            appendLine("long_narrative_rows=" + rows.count { it.valueText.length > 160 })
            appendLine("opaque_control_fields=" + opaque)
            appendLine("placeholder_fields=" + placeholders)
            appendLine("technical_fields=" + technical)
            appendLine("accession_on_result=" + rows.any { it.labNumber.isNotBlank() })
            appendLine("order_date_on_result=" + rows.any { it.orderDate.isNotBlank() })
            appendLine("reported_date_on_result=" + rows.any { it.reportedDate.isNotBlank() })
            appendLine("source_inventory=" + inventory.size)
            appendLine("pdf_urls_available=" + inventory.count { it.url.isNotBlank() })
            appendLine("verified_cr_pdf_headers=" + checked)
            appendLine("same_test_title_matches=" + titleMatches)
            appendLine("relevant_unverified_candidates=" + matching.candidates.size)
            appendLine("pdf_mapping_status=" + status)
            appendLine("layout=" + ClinicalReportRenderer.layout(test, rows))
            appendLine("source_value_lengths=" + rows.take(12).map { it.valueText.length })
            appendLine("No patient identifiers, accessions, URLs or result text included.")
        }
    }
}
