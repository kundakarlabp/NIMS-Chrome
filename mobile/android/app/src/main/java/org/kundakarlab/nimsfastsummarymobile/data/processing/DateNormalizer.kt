package org.kundakarlab.nimsfastsummarymobile.data.processing

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

data class NormalizedDate(val original: String, val instantOrLocalDateTime: LocalDateTime?, val sortEpoch: Long?)

object DateNormalizer {
    private val locale = Locale.US
    private val formatters = listOf(
        "dd-MM-yyyy HH:mm", "dd/MM/yyyy HH:mm", "dd-MM-yyyy hh:mm a", "dd/MM/yyyy hh:mm a", "yyyy-MM-dd HH:mm",
        // NIMS uses 3-letter month names: 08-Jun-2026, 26-Sep-2024. Without these
        // every date from NIMS failed normalisation → sortEpoch=null → Trends shows
        // 0 parameters and every lab shows "report date unavailable".
        "dd-MMM-yyyy HH:mm", "dd-MMM-yyyy hh:mm a", "dd/MMM/yyyy HH:mm"
    ).map { DateTimeFormatter.ofPattern(it, locale) }
    private val dateFormatters = listOf(
        "dd-MM-yyyy", "dd/MM/yyyy", "yyyy-MM-dd",
        "dd-MMM-yyyy", "dd-MMM-yy", "dd/MMM/yyyy", "dd/MMM/yy"
    ).map { DateTimeFormatter.ofPattern(it, locale) }

    fun normalize(value: String): NormalizedDate {
        val raw = value.trim()
        if (raw.isBlank()) return NormalizedDate(value, null, null)
        runCatching { return fromInstant(raw, OffsetDateTime.parse(raw).toInstant()) }
        runCatching { return fromInstant(raw, Instant.parse(raw)) }
        for (formatter in formatters) {
            try { return fromLocal(raw, LocalDateTime.parse(raw.uppercase(locale), formatter)) } catch (_: DateTimeParseException) {}
        }
        for (formatter in dateFormatters) {
            try { return fromLocal(raw, LocalDate.parse(raw, formatter).atStartOfDay()) } catch (_: DateTimeParseException) {}
        }
        return NormalizedDate(value, null, null)
    }

    private fun fromInstant(original: String, instant: Instant) = NormalizedDate(original, LocalDateTime.ofInstant(instant, ZoneOffset.UTC), instant.toEpochMilli())
    private fun fromLocal(original: String, dateTime: LocalDateTime) = NormalizedDate(original, dateTime, dateTime.toEpochSecond(ZoneOffset.UTC) * 1000)
}
