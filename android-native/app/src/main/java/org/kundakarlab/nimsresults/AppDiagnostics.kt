package org.kundakarlab.nimsresults

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Codes only: never store clinical data, accession, name, URL or CR. */
object DiagnosticEvents {
    private val counts = ConcurrentHashMap<String, Int>()
    private val allowed = setOf("AUTH_REQUIRED", "STRUCTURED_FAILURE",
        "REPORT_INDEX_FAILED", "PDF_HTTP_FAILED", "PDF_NOT_VALID",
        "PDF_IDENTITY_MISMATCH", "PDF_TEXT_MISSING", "PDF_RENDER_FAILED")
    fun record(code: String) {
        if (code in allowed) counts.merge(code, 1, Int::plus)
    }
    fun snapshot(): Map<String, Int> = counts.toSortedMap()
    fun clear() = counts.clear()
}

/** Automatic, entire-bundle source/coverage audit. Reports counts only. */
object AppDiagnostics {
    fun summarize(bundle: ClinicalBundle?): String {
        if (bundle == null) return "audit.no_patient_bundle=true\n"
        val rows = bundle.results
        val reports = bundle.reports
        val groups = rows.groupBy { Triple(it.test, it.date, it.labNumber) }
        val mapping = groups.values.map { ReportLinkResolver.candidates(it, reports, bundle.patient.crNo) }
        val matched = mapping.count { it.verified != null }
        val ambiguous = mapping.count { it.verified == null && it.candidates.size > 1 }
        val suggested = mapping.count { it.verified == null && it.candidates.size == 1 }
        val noPdf = mapping.count { it.verified == null && it.candidates.isEmpty() }
        val sections = ResultSection.entries.associateWith { section ->
            groups.count { (id, _) -> ResultSections.of(id.first) == section }
        }
        val sourceIds = reports.map { it.url.ifBlank { "id:" + it.id } }.toSet()
        val completeHeaders = reports.count {
            ReportTitleCache.header(it.url)?.crNo == bundle.patient.crNo
        }
        val medicalCodes = rows.count { ClinicalReportRenderer.isUninterpretedCode(it) }
        val technical = rows.count { ClinicalPresentation.isTechnical(it) }
        val panelGroups = groups.count { (id, _) ->
            Regex("""(?i)blood\s*group|antibody\s*screen|biofire|filmarray|\bbgt\b""")
                .containsMatchIn(id.first)
        }
        val unparsableDates = groups.count { (_, v) ->
            RecentReportDates.parse(v.firstOrNull()?.date.orEmpty()) == null
        }
        val lastWeek = java.time.LocalDate.now()
        val recentIndex = reports.count {
            RecentReportDates.isLastWeek(RecentReportDates.dateFor(it).first, lastWeek)
        }
        val findings = buildList {
            if (reports.isEmpty()) add("SOURCE_INVENTORY_EMPTY")
            if (reports.any { it.url.isBlank() }) add("SOURCE_PDF_URL_MISSING")
            if (noPdf > 0) add("RESULT_GROUPS_WITHOUT_VERIFIED_PDF")
            if (ambiguous > 0) add("MULTIPLE_PDF_CANDIDATES")
            if (medicalCodes > 0) add("UNMAPPED_SOURCE_CODES")
            if (rows.isNotEmpty() && groups.any { (k, _) -> k.third.isBlank() })
                add("RESULT_ACCESSION_NOT_SUPPLIED")
            if (unparsableDates > 0) add("UNRECOGNIZED_RESULT_DATES")
            if (reports.any { OpaqueReportTitles.isCode(it.title) })
                add("OPAQUE_REPORT_INDEX_LABELS")
        }
        return buildString {
            appendLine("audit.scope=all_fetched_investigations")
            appendLine("audit.result_fields=" + rows.size)
            appendLine("audit.investigation_groups=" + groups.size)
            appendLine("audit.source_index_entries=" + reports.size)
            appendLine("audit.unique_source_documents=" + sourceIds.size)
            appendLine("audit.source_PDF_links=" + reports.count { it.url.isNotBlank() })
            appendLine("audit.source_PDF_headers_CR_checked=" + completeHeaders)
            appendLine("audit.source_titles_opaque=" +
                reports.count { OpaqueReportTitles.isCode(it.title) })
            appendLine("audit.result_groups_with_accession=" +
                groups.count { (id, _) -> id.third.isNotBlank() })
            appendLine("audit.result_groups_no_accession=" +
                groups.count { (id, _) -> id.third.isBlank() })
            appendLine("audit.pdf_match_auto_verified=" + matched)
            appendLine("audit.pdf_match_one_unverified_candidate=" + suggested)
            appendLine("audit.pdf_match_ambiguous=" + ambiguous)
            appendLine("audit.pdf_match_no_candidate=" + noPdf)
            appendLine("audit.technical_fields=" + technical)
            appendLine("audit.opaque_or_unmapped_fields=" + medicalCodes)
            appendLine("audit.source_coded_panel_groups=" + panelGroups)
            appendLine("audit.unparseable_result_dates=" + unparsableDates)
            appendLine("audit.source_reports_last_7_days=" + recentIndex)
            for ((section, count) in sections) {
                appendLine("audit.section_" + section.name.lowercase() + "=" + count)
            }
            appendLine("audit.flags=" + findings.joinToString(",").ifBlank { "none" })
            appendLine("audit.event_counters=" + DiagnosticEvents.snapshot())
            appendLine("audit.privacy=No names, CRs, source values, lab numbers, cookies or URLs")
        }
    }
}
