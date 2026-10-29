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
        val sourceReports = reports.filterNot {
            it.sourceTag.startsWith("nims_rest_reportList")
        }
        val restRows = reports.size - sourceReports.size
        val enquiry = EnquirySourceBridge.coverage(bundle)
        val groups = rows.groupBy {
            listOf(it.test, it.date, it.labNumber, it.sampleNumber,
                it.reportStage, it.sourceOrderId)
        }
        // No PDF matching, PDF parsing or title/date-based specimen linking
        // during diagnostics. Only explicit source ID matches are assessed.
        val decisions = groups.values.map { SourceEventReconciliation.assess(it, reports).state }
        val verified = decisions.count { it == SourceEventReconciliation.State.VERIFIED_INDEX }
        val withoutId = decisions.count { it == SourceEventReconciliation.State.NO_RESULT_ID }
        val ambiguous = decisions.count {
            it == SourceEventReconciliation.State.AMBIGUOUS_INDEX ||
                it == SourceEventReconciliation.State.CONFLICTING_RESULT_ID
        }
        val dateConflict = decisions.count { it == SourceEventReconciliation.State.DATE_CONFLICT }
        val noIndexMatch = decisions.count { it == SourceEventReconciliation.State.NO_INDEX_MATCH }
        val sections = ResultSection.entries.associateWith { section ->
            groups.count { (id, _) -> ResultSections.of(id[0]) == section }
        }
        val sourceIds = sourceReports.map { it.url.ifBlank { "id:" + it.id } }.toSet()
        val completeHeaders = sourceReports.count {
            ReportTitleCache.header(it.url)?.crNo == bundle.patient.crNo
        }
        val medicalCodes = rows.count { ClinicalReportRenderer.isUninterpretedCode(it) }
        val technical = rows.count { ClinicalPresentation.isTechnical(it) }
        val panelGroups = groups.count { (id, _) ->
            Regex("""(?i)blood\s*group|antibody\s*screen|biofire|filmarray|\bbgt\b""")
                .containsMatchIn(id[0])
        }
        val unparsableDates = groups.count { (_, v) ->
            RecentReportDates.parse(v.firstOrNull()?.date.orEmpty()) == null
        }
        val lastWeek = java.time.LocalDate.now()
        val recentIndex = sourceReports.count {
            RecentReportDates.isLastWeek(RecentReportDates.dateFor(it).first, lastWeek)
        }
        val findings = buildList {
            if (sourceReports.isEmpty()) add("SOURCE_INVENTORY_EMPTY")
            if (sourceReports.any { it.url.isBlank() }) add("SOURCE_PDF_URL_MISSING")
            if (withoutId > 0) add("STRUCTURED_SPECIMEN_ID_NOT_SUPPLIED")
            if (ambiguous > 0) add("AMBIGUOUS_SOURCE_IDENTITY")
            if (dateConflict > 0) add("SOURCE_DATE_DISAGREEMENT")
            if (medicalCodes > 0) add("UNMAPPED_SOURCE_CODES")
            if (rows.isNotEmpty() && groups.any { (k, _) -> k[2].isBlank() })
                add("RESULT_ACCESSION_NOT_SUPPLIED")
            if (unparsableDates > 0) add("UNRECOGNIZED_RESULT_DATES")
            if (sourceReports.any { OpaqueReportTitles.isCode(it.title) })
                add("OPAQUE_REPORT_INDEX_LABELS")
        }
        return buildString {
            appendLine("audit.scope=all_fetched_investigations")
            appendLine("audit.result_fields=" + rows.size)
            appendLine("audit.investigation_groups=" + groups.size)
            appendLine("audit.enquiry_rows=" + enquiry.total)
            appendLine("audit.enquiry_origin=" + if (bundle.enquiryCapturedAt > 0L)
                "manual_verified_ui" else "not_captured")
            appendLine("audit.enquiry_with_sample_id=" +
                bundle.enquiryOrders.count { it.sampleNumber.isNotBlank() })
            appendLine("audit.enquiry_with_lab_id=" +
                bundle.enquiryOrders.count { it.labNumber.isNotBlank() })
            appendLine("audit.enquiry_with_packing_list=" +
                bundle.enquiryOrders.count { it.packingListNumber.isNotBlank() })
            appendLine("audit.enquiry_pending_statuses=" + enquiry.pending)
            appendLine("audit.enquiry_reported_statuses=" + enquiry.reported)
            appendLine("audit.enquiry_report_index_exact_matches=" + enquiry.linked)
            appendLine("audit.enquiry_report_index_unmatched=" + enquiry.unmatched)
            appendLine("audit.enquiry_report_index_ambiguous=" + enquiry.ambiguous)
            appendLine("audit.enquiry_report_index_missing_ids=" + enquiry.missingIds)
            appendLine("audit.source_index_entries=" + sourceReports.size)
            appendLine("audit.rest_metadata_entries=" + restRows)
            appendLine("audit.rest_context_bound_entries=" +
                reports.count { it.sourceTag == "nims_rest_reportList_context" })
            appendLine("audit.unique_source_documents=" + sourceIds.size)
            appendLine("audit.source_PDF_links=" + sourceReports.count { it.url.isNotBlank() })
            appendLine("audit.source_PDF_headers_CR_checked=" + completeHeaders)
            appendLine("audit.source_titles_opaque=" +
                sourceReports.count { OpaqueReportTitles.isCode(it.title) })
            appendLine("audit.result_groups_with_accession=" +
                groups.count { (id, _) -> id[2].isNotBlank() })
            appendLine("audit.result_groups_no_accession=" +
                groups.count { (id, _) -> id[2].isBlank() })
            appendLine("audit.source_identity_verified=" + verified)
            appendLine("audit.source_identity_no_result_id=" + withoutId)
            appendLine("audit.source_identity_ambiguous=" + ambiguous)
            appendLine("audit.source_identity_date_conflict=" + dateConflict)
            appendLine("audit.source_identity_unmatched=" + noIndexMatch)
            appendLine("audit.pdf_auto_mapping=disabled")
            appendLine("audit.pdf_match_auto_verified=0")
            appendLine("audit.pdf_match_one_unverified_candidate=0")
            appendLine("audit.pdf_match_ambiguous=0")
            appendLine("audit.pdf_match_no_candidate=" + groups.size)
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
