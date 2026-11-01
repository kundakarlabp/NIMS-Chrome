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
        "PDF_IDENTITY_MISMATCH", "PDF_TEXT_MISSING", "PDF_RENDER_FAILED",
        "CR_SCAN_VALID", "CR_SCAN_UNRECOGNIZED", "CR_SCAN_FAILED")
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
        // Metadata-only candidate assessment: no network, no PDF fetch or
        // speculative specimen association. Runs off the UI thread when copied.
        val candidateCounts = groups.values.map { group ->
            SourceMetadataReview.candidates(group, sourceReports)
                .map { it.url }.filter(String::isNotBlank).distinct().size
        }
        val clinicalPanels = LabPanelMatrix.build(
            rows.filterNot { ClinicalReportRenderer.isMicrobiologyInvestigation(it.test) },
            clinical = true)
        val allClinicalPanels = LabPanelMatrix.Panel.entries.associateWith { panel ->
            clinicalPanels.firstOrNull { it.panel == panel }
        }
        val nonMicroRows=rows.filterNot {
            ClinicalReportRenderer.isMicrobiologyInvestigation(it.test)
        }
        val missingCellText = rows.count {
            it.valueText.isBlank() ||
            it.valueText.contains("Not reported in structured feed", ignoreCase = true)
        }
        val lastDate=rows.mapNotNull { RecentReportDates.parse(it.date) }.maxOrNull()
        val sourceMetadata = mapOf(
            "lab" to sourceReports.count { it.labNumber.isNotBlank() },
            "sample" to sourceReports.count { it.sampleNumber.isNotBlank() },
            "collection" to sourceReports.count { it.collectionDateTime.isNotBlank() },
            "reportDate" to sourceReports.count { it.reportedDate.isNotBlank() },
            "orderDate" to sourceReports.count { it.orderDate.isNotBlank() },
            "department" to sourceReports.count { it.department.isNotBlank() },
            "pdf" to sourceReports.count { it.url.isNotBlank() }
        )
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
            appendLine("audit.pdf_match_one_unverified_candidate=" +
                candidateCounts.count { it == 1 })
            appendLine("audit.pdf_match_ambiguous=" +
                candidateCounts.count { it > 1 })
            appendLine("audit.pdf_match_no_candidate=" +
                candidateCounts.count { it == 0 })
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
            appendLine("diagnostics.clinical.structured_numeric=" +
                rows.count { it.numericValue?.isFinite() == true })
            appendLine("diagnostics.clinical.missing_placeholder_values=" + missingCellText)
            appendLine("diagnostics.clinical.structured_lab_identifiers=" +
                rows.count { it.labNumber.isNotBlank() })
            appendLine("diagnostics.clinical.structured_sample_identifiers=" +
                rows.count { it.sampleNumber.isNotBlank() })
            appendLine("diagnostics.clinical.structured_collection_times=" +
                rows.count { it.collectionDateTime.isNotBlank() })
            appendLine("diagnostics.clinical.structured_report_dates=" +
                rows.count { it.reportedDate.isNotBlank() })
            appendLine("diagnostics.clinical.latest_result_age_days=" +
                (lastDate?.let { java.time.temporal.ChronoUnit.DAYS.between(
                    it,java.time.LocalDate.now()).coerceAtLeast(0) } ?: "unavailable"))
            appendLine("diagnostics.source.pdf_candidate_one=" +
                candidateCounts.count { it == 1 })
            appendLine("diagnostics.source.pdf_candidate_multiple=" +
                candidateCounts.count { it > 1 })
            appendLine("diagnostics.source.pdf_candidate_none=" +
                candidateCounts.count { it == 0 })
            appendLine("diagnostics.source.candidates_are_specimen_verified=false")
            for ((name,count) in sourceMetadata) {
                appendLine("diagnostics.source.index_rows_with_" + name + "=" + count)
            }
            for ((panel,table) in allClinicalPanels) {
                val name=panel.name.lowercase()
                appendLine("diagnostics.labs." + name + ".visible_parameters=" +
                    (table?.parameters?.size ?: 0))
                appendLine("diagnostics.labs." + name + ".visible_observations=" +
                    (table?.parameters?.sumOf { p ->
                        p.byDate.values.sumOf { it.size } } ?: 0))
                appendLine("diagnostics.labs." + name + ".dates=" +
                    (table?.dates?.size ?: 0))
            }
            appendLine("diagnostics.labs.source_non_micro_rows=" + nonMicroRows.size)
            appendLine("diagnostics.labs.filtered_from_clinical_view=" +
                (nonMicroRows.size - clinicalPanels.sumOf { table ->
                    table.parameters.sumOf { p ->
                        p.byDate.values.sumOf { it.size } } }).coerceAtLeast(0))
            appendLine("diagnostics.labs.raw_source_retained=true")
            val chronology=ClinicalChronology.days(rows)
            appendLine("diagnostics.results.chronological_dates=" + chronology.size)
            appendLine("diagnostics.results.chronological_studies=" +
                chronology.sumOf { it.studies.size })
            appendLine("diagnostics.results.studies_with_values=" +
                chronology.sumOf { day -> day.studies.count { it.visible.isNotEmpty() } })
            appendLine("diagnostics.results.structured_reported_dates=" +
                rows.count { it.reportedDate.isNotBlank() })
            appendLine("diagnostics.results.date_is_source_not_collection=true")
            appendLine("diagnostics.results.raw_source_retained=true")
            val studies = chronology.flatMap { it.studies }
            appendLine("diagnostics.results.readable_inline_observations=" +
                studies.sumOf { ClinicalChronology.inlineRows(it).size })
            appendLine("diagnostics.results.unverified_source_observations=" +
                studies.sumOf { ClinicalChronology.unresolvedRows(it).size })
            appendLine("diagnostics.results.studies_without_readable_value=" +
                studies.count { ClinicalChronology.inlineRows(it).isEmpty() })
            appendLine("diagnostics.results.unverified_urine_source_fields=" +
                rows.count { ClinicalReportRenderer.isUrineRoutine(it.test) &&
                    ClinicalReportRenderer.isUninterpretedCode(it) })
            appendLine("diagnostics.results.source_minus_one_codes=" +
                rows.count { it.valueText.trim() == "-1" })
            appendLine("diagnostics.results.studies_with_explicit_report_dates=" +
                studies.count { it.reportedIsExplicit })
            appendLine("diagnostics.results.source_date_not_verified_reporting=" +
                studies.count { !it.reportedIsExplicit && it.date != java.time.LocalDate.MIN })
            appendLine("diagnostics.results.source_pdf_candidate_unverified=true")
            appendLine("diagnostics.results.pdf_candidate_policy=exact_lab_or_same_source_date")
            appendLine("diagnostics.results.culture_studies=" +
                studies.count { it.title.contains("culture", ignoreCase=true) })
            appendLine("diagnostics.results.culture_studies_with_labelled_accession=" +
                studies.count { study ->
                    study.title.contains("culture",ignoreCase=true) &&
                    study.rows.any { MicrobiologyNarrativeMetadata.extract(it)
                        ?.labNumber?.isNotBlank() == true }
                })
            appendLine("diagnostics.results.imaging_reports=" +
                studies.count { study ->
                    Regex("""(?i)\\b(?:doppler|ct|mri|ultrasound|usg|radiology)\\b""")
                        .containsMatchIn(study.title)
                })
            appendLine("diagnostics.results.source_reference_ranges=" +
                rows.count { it.refRange.isNotBlank() })
            appendLine("diagnostics.results.unverified_missing_sentinels_hidden=" +
                rows.count { it.valueText.trim() == "-1" })
            appendLine("diagnostics.results.unique_pdf_candidates_same_day_or_lab=" +
                candidateCounts.count { it == 1 })

            appendLine("diagnostics.privacy=Counts/booleans only; no patient details or result values")
            appendLine("audit.privacy=No names, CRs, source values, lab numbers, cookies or URLs")
        }
    }
}
