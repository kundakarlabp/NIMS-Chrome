package org.kundakarlab.nimsresults

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.time.temporal.ChronoUnit

/**
 * Classify by original investigation name (not patient narrative).
 * These are presentation sections, never diagnoses.
 */
enum class ResultSection { MICROBIOLOGY, METABOLIC, PATHOLOGY, OTHER }

object ResultSections {
    fun of(test: String): ResultSection {
        if (Regex("""(?i)\b(?:histo\s*pathology|histopathology|biopsy|cytology|cytopathology|fine[\s-]*needle|fnac|peripheral[\s-]*smear|bone[\s-]*marrow|pathology[\s-]*specimen|immunohistochemistry|dic[\s-]*profile)\b""")
                .containsMatchIn(test)) return ResultSection.PATHOLOGY
        if (ClinicalReportRenderer.isMicrobiologyInvestigation(test)) return ResultSection.MICROBIOLOGY
        if (Regex("""(?i)\b(?:electrolytes?|sodium|potassium|chloride|renal[\s-]*function|kidney[\s-]*function|liver[\s-]*function|hepatic[\s-]*function|creatinine|urea|uric[\s-]*acid|glucose|blood[\s-]*sugar|hba1c|glycated|albumin|bilirubin|transaminase|sgot|sgpt|alkaline[\s-]*phosphatase|total[\s-]*protein|complete[\s-]*urine[\s-]*examination|urine[\s-]*routine|urinalysis|metabolic|lipid[\s-]*profile|calcium|magnesium|phosphate|phosphorus|lactate|rft|lft|kft|bmp|cmp)\b""")
                .containsMatchIn(test)) return ResultSection.METABOLIC
        return ResultSection.OTHER
    }

    fun label(section: ResultSection): String = when(section) {
        ResultSection.MICROBIOLOGY -> "Microbiology"
        ResultSection.METABOLIC -> "Metabolic"
        ResultSection.PATHOLOGY -> "Pathology"
        ResultSection.OTHER -> "Other"
    }
}

/**
 * Never treat an order/collection date as a result's reported date.
 * For unlabelled inventory dates, visibly label them 'index date'.
 */
object RecentReportDates {
    private val textual = DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH)
    private val numeric = DateTimeFormatter.ofPattern("d-M-uuuu", Locale.ENGLISH)

    fun parse(value: String): LocalDate? {
        val trimmed = value.trim()
        val token = Regex("""(?i)\d{4}-\d{1,2}-\d{1,2}|\d{1,2}[-/][a-z]{3}[-/]\d{4}|\d{1,2}[-/]\d{1,2}[-/]\d{4}""")
            .find(trimmed)?.value ?: return null
        return try {
            when {
                token.matches(Regex("""\d{4}-\d{1,2}-\d{1,2}""")) ->
                    LocalDate.parse(token)
                token.contains('/') && token.any(Char::isLetter) ->
                    LocalDate.parse(token.replace('/', '-'), textual)
                token.contains('/') || token.matches(Regex("""\d{1,2}-\d{1,2}-\d{4}""")) -> LocalDate.parse(token.replace('/', '-'), numeric)
                else -> LocalDate.parse(token, textual)
            }
        } catch (_: DateTimeParseException) { null }
    }

    // Seven calendar days including device-local today; exclude future reports.
    fun isLastWeek(date: LocalDate?, today: LocalDate): Boolean =
        date != null && ChronoUnit.DAYS.between(date, today) in 0L..6L

    fun dateFor(report: ReportRef): Pair<LocalDate?, String> {
        val reported = parse(report.reportedDate)
        return if (reported != null) reported to "Reported"
        else parse(report.date) to "Source index date"
    }
    fun dateFor(rows: List<LabResult>): Pair<LocalDate?, String> {
        val reported = rows.firstNotNullOfOrNull { parse(it.reportedDate) }
        return if (reported != null) reported to "Reported"
        else rows.firstNotNullOfOrNull { parse(it.date) } to "Result date"
    }
}
