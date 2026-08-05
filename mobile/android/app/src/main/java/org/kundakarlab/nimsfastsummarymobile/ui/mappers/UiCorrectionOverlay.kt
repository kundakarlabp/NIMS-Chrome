package org.kundakarlab.nimsfastsummarymobile.ui.mappers

import org.kundakarlab.nimsfastsummarymobile.domain.recovery.ClinicianCorrection
import org.kundakarlab.nimsfastsummarymobile.ui.models.Abnormality
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiCultureRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiLabTrendRow
import org.kundakarlab.nimsfastsummarymobile.ui.models.UiSummary

/** Adds clearly labelled, local-only clinician corrections without overwriting source values. */
object UiCorrectionOverlay {
    fun apply(summary: UiSummary, corrections: List<ClinicianCorrection>): UiSummary {
        if (corrections.isEmpty()) return summary

        val labRows = summary.labTrends.toMutableList()
        val cultureRows = summary.cultures.toMutableList()

        corrections.forEach { correction ->
            if (isCultureField(correction.field)) {
                applyCultureCorrection(cultureRows, correction)
            } else {
                val displayValue = listOf(correction.value.trim(), correction.unit.trim())
                    .filter(String::isNotBlank)
                    .joinToString(" ")
                val previous = labRows.firstOrNull { it.parameter.equals(correction.field, ignoreCase = true) }
                labRows.removeAll { it.parameter.equals(correction.field, ignoreCase = true) && it.latestDate == "Clinician entered" }
                labRows.add(
                    0,
                    UiLabTrendRow(
                        parameter = "${correction.field.trim()} · Clinician entered",
                        latestValue = displayValue,
                        latestDate = correction.resultDate.ifBlank { "Clinician entered" },
                        previousValue = previous?.latestValue,
                        previousDate = previous?.latestDate,
                        trendText = "Local correction",
                        abnormality = Abnormality.UNKNOWN,
                        history = buildList {
                            add(correction.resultDate.ifBlank { "Clinician entered" } to displayValue)
                            previous?.history?.let(::addAll)
                        }
                    )
                )
            }
        }

        return summary.copy(labTrends = labRows, cultures = cultureRows)
    }

    private fun applyCultureCorrection(rows: MutableList<UiCultureRow>, correction: ClinicianCorrection) {
        val existingIndex = rows.indexOfFirst { it.sourceKey == correction.reportId }
        val existing = rows.getOrNull(existingIndex)
        val field = correction.field.lowercase()
        val updated = (existing ?: UiCultureRow(
            sourceKey = correction.reportId,
            collectionDate = correction.resultDate,
            cultureNo = "",
            specimen = "",
            site = "",
            organism = "",
            growth = "",
            status = "unknown",
            sensitivitySummary = "",
            comment = "Clinician entered",
            sourceReportName = "Clinician correction"
        )).let { row ->
            when {
                "organism" in field -> row.copy(
                    organism = correction.value.trim(),
                    status = "growth_detected",
                    comment = appendLabel(row.comment)
                )
                "specimen" in field || "sample" in field || "site" in field -> row.copy(
                    specimen = correction.value.trim(),
                    site = correction.value.trim(),
                    comment = appendLabel(row.comment)
                )
                "suscept" in field || "sensitivity" in field || "antibiogram" in field -> row.copy(
                    sensitivitySummary = correction.value.trim(),
                    comment = appendLabel(row.comment)
                )
                "status" in field || "growth" in field -> row.copy(
                    growth = correction.value.trim(),
                    status = normalizeStatus(correction.value),
                    comment = appendLabel(row.comment)
                )
                else -> row.copy(comment = appendLabel(listOf(row.comment, "${correction.field}: ${correction.value}").filter(String::isNotBlank).joinToString(" · ")))
            }
        }
        if (existingIndex >= 0) rows[existingIndex] = updated else rows.add(0, updated)
    }

    private fun appendLabel(value: String): String = listOf(value.takeIf(String::isNotBlank), "Clinician entered")
        .filterNotNull()
        .distinct()
        .joinToString(" · ")

    private fun normalizeStatus(value: String): String {
        val lower = value.lowercase()
        return when {
            "no growth" in lower || "negative" in lower -> "no_growth"
            "pending" in lower || "prelim" in lower -> "pending"
            "growth" in lower || "positive" in lower || "isolated" in lower -> "growth_detected"
            else -> "unknown"
        }
    }

    private fun isCultureField(field: String): Boolean {
        val lower = field.lowercase()
        return listOf("organism", "culture", "growth", "specimen", "sample", "site", "sensitivity", "susceptibility", "antibiogram").any(lower::contains)
    }
}
