package org.kundakarlab.nimsresults

import org.json.JSONObject
import java.util.Collections
import java.util.IdentityHashMap

/**
 * On-demand, source-to-display trace for EVERY structured observation and
 * original Custom Sheet parameter. Runs only when Copy diagnostics is tapped.
 * No raw clinical values, dates, CRs, accessions, patient names or URLs.
 */
object ParameterDiagnostics {
    private fun safeLabel(raw: String): String = raw
        .replace(Regex("""(?i)\b(?:https?://|www\.)\S+"""), "[url]")
        .replace(Regex("""(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}"""), "[email]")
        .replace(Regex("""\b\d{7,}\b"""), "[number]")
        .replace(Regex("""[\r\n\t|=]+"""), " ")
        .trim().take(96).ifBlank { "[unnamed]" }

    private fun valueKind(row: LabResult): String = when {
        row.valueText.isBlank() -> "empty"
        row.numericValue?.isFinite() == true -> "numeric"
        row.valueText.length > 120 || row.valueText.contains('\n') -> "narrative"
        else -> "text"
    }

    private fun displayState(row: LabResult, inline: Set<LabResult>,
                             unresolved: Set<LabResult>): String = when {
        inline.contains(row) -> "inline"
        unresolved.contains(row) -> "source_review_unmapped"
        ClinicalPresentation.isTechnical(row) -> "hidden_technical"
        ClinicalReportRenderer.isPlaceholder(row) -> "hidden_placeholder"
        CoagulationDisplay.isAbsentValue(row) -> "hidden_absent"
        ClinicalResultsLayout.suppressCbcRow(row) -> "hidden_cbc_artifact"
        ClinicalResultsLayout.suppressStructuredArtifact(row) -> "hidden_source_artifact"
        ClinicalReportRenderer.isUninterpretedCode(row) -> "source_review_unmapped"
        else -> "not_in_inline_feed"
    }

    fun describe(bundle: ClinicalBundle): String {
        val rows = bundle.results
        val inline = Collections.newSetFromMap(IdentityHashMap<LabResult, Boolean>())
        val unresolved = Collections.newSetFromMap(IdentityHashMap<LabResult, Boolean>())
        ClinicalChronology.days(rows).forEach { day ->
            day.studies.forEach { study ->
                inline.addAll(ClinicalChronology.inlineRows(study))
                unresolved.addAll(ClinicalChronology.unresolvedRows(study))
            }
        }
        val normalizedByKey = rows.groupBy { it.sourceKey }
        val rawKeys = HashSet<String>()
        val sourceLines = StringBuilder()
        var rawDateCells = 0
        var emptySourceCells = 0
        var unmatchedSourceCells = 0
        var invalidRawRows = 0

        bundle.rawRows.forEachIndexed { ordinal, source ->
            val obj = runCatching { JSONObject(source.sourceJson) }.getOrNull()
            if (obj == null) invalidRawRows++
            val field = safeLabel(obj?.optString("parameterName").orEmpty())
            val test = safeLabel(source.investigation)
            val values = obj?.optJSONObject("valuesByDate")
            val dates = values?.keys()?.asSequence()?.toList()?.sorted().orEmpty()
            sourceLines.appendLine("parameter.source[$ordinal].group=${source.groupIndex};row=${source.rowIndex};test=$test;field=$field;date_cells=${dates.size};json_valid=${obj != null}")
            if (dates.isEmpty()) {
                sourceLines.appendLine("parameter.source[$ordinal].no_date_cells=true")
            }
            dates.forEachIndexed { dateOrdinal, rawDate ->
                rawDateCells++
                val rawText = values?.opt(rawDate)?.toString().orEmpty()
                if (rawText.isBlank()) emptySourceCells++
                val key = obj?.let {
                    BulkParser.sourceKey(bundle.patient.crNo, it,
                        source.groupIndex, source.rowIndex, rawDate)
                }.orEmpty()
                if (key.isNotBlank()) rawKeys.add(key)
                val accepted = normalizedByKey[key].orEmpty()
                if (rawText.isNotBlank() && accepted.isEmpty()) unmatchedSourceCells++
                val state = when {
                    rawText.isBlank() -> "empty_in_source"
                    accepted.isEmpty() -> "source_not_normalized"
                    accepted.size > 1 -> "multiple_normalized_same_source_key"
                    else -> "normalized"
                }
                sourceLines.appendLine("parameter.source[$ordinal].cell[$dateOrdinal].date_parseable=${RecentReportDates.parse(BulkParser.isoDate(rawDate)) != null};source_length=${rawText.length};normalized_count=${accepted.size};state=$state")
            }
        }

        val observationLines = StringBuilder()
        val statusCounts = linkedMapOf<String, Int>()
        rows.forEachIndexed { ordinal, row ->
            val status = displayState(row, inline, unresolved)
            statusCounts[status] = (statusCounts[status] ?: 0) + 1
            observationLines.appendLine(
                "parameter.result[$ordinal].test=${safeLabel(row.test)}" +
                ";field=${safeLabel(row.parameter)}" +
                ";section=${ResultSections.of(row.test).name.lowercase()}" +
                ";display=$status;kind=${valueKind(row)};value_length=${row.valueText.length}" +
                ";raw_key_present=${row.sourceKey in rawKeys}" +
                ";unit=${row.unit.isNotBlank()};reference=${row.refRange.isNotBlank()}" +
                ";flag=${row.abnormal.isNotBlank()}" +
                ";chart_date_parseable=${RecentReportDates.parse(row.date) != null}" +
                ";reported_date=${row.reportedDate.isNotBlank()}" +
                ";collection_date=${row.collectionDateTime.isNotBlank()}" +
                ";sent_date=${row.sentDate.isNotBlank()}" +
                ";lab_id=${row.labNumber.isNotBlank() || row.accessionNumber.isNotBlank()}" +
                ";sample_id=${row.sampleNumber.isNotBlank()}" +
                ";specimen=${row.specimenType.isNotBlank()}" +
                ";stage=${row.reportStage.isNotBlank()}"
            )
        }
        return buildString {
            appendLine("audit.parameters.format=v1_redacted_every_source_row_and_result")
            appendLine("audit.parameters.source_rows=${bundle.rawRows.size}")
            appendLine("audit.parameters.source_date_cells=$rawDateCells")
            appendLine("audit.parameters.empty_source_cells=$emptySourceCells")
            appendLine("audit.parameters.nonempty_source_cells_without_normalized=$unmatchedSourceCells")
            appendLine("audit.parameters.invalid_raw_json_rows=$invalidRawRows")
            appendLine("audit.parameters.normalized_results=${rows.size}")
            appendLine("audit.parameters.normalized_without_raw_key=" +
                if (bundle.rawRows.isEmpty()) "unknown_no_raw_rows"
                else rows.count { it.sourceKey !in rawKeys })
            appendLine("audit.parameters.display_states=$statusCounts")
            appendLine("audit.parameters.raw_values_redacted=true")
            appendLine("audit.parameters.field_labels_redacted_for_urls_emails_long_numbers=true")
            append(sourceLines)
            append(observationLines)
        }
    }
}
