package org.kundakarlab.nimsfastsummarymobile.data.processing

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

data class NormalizedDate(val original: String, val instantOrLocalDateTime: LocalDateTime?, val sortEpoch: Long?)

object DateNormalizer {
    private val locale = Locale.US
    private val formatters = listOf(
        "dd-MM-yyyy HH:mm", "dd/MM/yyyy HH:mm", "dd-MM-yyyy hh:mm a", "dd/MM/yyyy hh:mm a", "yyyy-MM-dd HH:mm"
    ).map { DateTimeFormatter.ofPattern(it, locale) }
    private val dateFormatters = listOf("dd-MM-yyyy", "dd/MM/yyyy", "yyyy-MM-dd").map { DateTimeFormatter.ofPattern(it, locale) }

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
