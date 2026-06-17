package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.kundakarlab.nimsfastsummarymobile.domain.model.*

class LocalSummaryBuilder {
    fun build(reports: List<ParsedReport>, mode: SummaryMode): ProcessingSummary {
        val lines = mutableListOf<String>()
        lines += "Auto-parsed summary. Verify with source NIMS reports before clinical decisions."
        lines += "Reports processed: ${reports.size}."
        val labs = reports.flatMap { report -> report.labs.filter { it.confidence != ParseConfidence.LOW }.map { report.dateSent to it } }
        labs.groupBy { it.second.canonicalCode }.values.forEach { rows ->
            val ordered = rows.sortedBy { it.first }
            val latest = ordered.last().second
            val previous = ordered.dropLast(1).lastOrNull()?.second
            if (previous?.numericValue != null && latest.numericValue != null) {
                val direction = when {
                    latest.numericValue > previous.numericValue -> "increased"
                    latest.numericValue < previous.numericValue -> "decreased"
                    else -> "was unchanged"
                }
                lines += "${latest.displayName} $direction from ${previous.numericValue} to ${latest.numericValue} ${latest.unit.orEmpty()}.".trim()
            } else if (latest.numericValue != null) {
                lines += "Latest ${latest.displayName}: ${latest.numericValue} ${latest.unit.orEmpty()}.".trim()
            }
        }
        val cultures = reports.flatMap { it.cultures }
        cultures.filter { it.growthStatus == GrowthStatus.GROWTH_DETECTED }.forEach { lines += "Positive culture: ${it.organism ?: "organism reported"}." }
        val noGrowth = cultures.count { it.growthStatus == GrowthStatus.NO_GROWTH }
        if (noGrowth > 0) lines += "No-growth cultures: $noGrowth."
        reports.flatMap { it.warnings }.distinct().forEach { lines += "Warning: $it" }
        return ProcessingSummary(lines.joinToString("\n"), reports.size, reports.flatMap { it.warnings }.distinct())
    }
}
