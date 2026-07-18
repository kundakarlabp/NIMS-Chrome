package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedCultureValue

/** Recovers bottle/set/stage metadata from each parser episode's retained source text. */
object NimsCultureMetadataEnricher {
    private val bottleLine = Regex(
        "(?im)^\\s*(.*blood\\s+culture.*?(?:first|second|third|fourth|\\d+(?:st|nd|rd|th)?)\\s+bottle[^\\n]*)$"
    )
    private val bottleNumber = Regex(
        "(?i)\\b(first|second|third|fourth|\\d+(?:st|nd|rd|th)?)\\s+bottle\\b"
    )
    private val setNumber = Regex(
        "(?i)\\b(?:of|in)\\s+(?:the\\s+)?(first|second|third|fourth|\\d+(?:st|nd|rd|th)?)\\s+set\\b"
    )

    fun enrich(values: List<ParsedCultureValue>, fullReportText: String = ""): List<ParsedCultureValue> {
        val reportHeadings = bottleLine.findAll(fullReportText).map { it.groupValues[1].trim() }.toList()
        return values.mapIndexed { index, value ->
            val source = value.rawSectionText.orEmpty()
            val heading = value.bottleName?.takeIf { it.isNotBlank() }
                ?: bottleLine.find(source)?.groupValues?.getOrNull(1)?.trim()
                ?: reportHeadings.getOrNull(index)
                ?: reportHeadings.singleOrNull()
            val metadataText = listOfNotNull(heading, source, fullReportText.takeIf { values.size == 1 }).joinToString(" ")
            val bottle = value.bottleNumber
                ?: bottleNumber.find(metadataText)?.groupValues?.getOrNull(1)?.let(::ordinal)
                ?: namedOrdinal(metadataText, "bottle")
            val set = value.setNumber
                ?: setNumber.find(metadataText)?.groupValues?.getOrNull(1)?.let(::ordinal)
                ?: namedOrdinal(metadataText, "set")
            val reportStage = value.reportStage?.takeUnless { it.isBlank() || it == "unspecified" }
                ?: stage(metadataText)
            value.copy(
                bottleName = heading,
                bottleNumber = bottle,
                setNumber = set,
                reportStage = reportStage
            )
        }
    }

    private fun namedOrdinal(text: String, noun: String): Int? {
        val lower = text.lowercase()
        return when {
            lower.contains("first $noun") -> 1
            lower.contains("second $noun") -> 2
            lower.contains("third $noun") -> 3
            lower.contains("fourth $noun") -> 4
            else -> null
        }
    }

    private fun stage(text: String): String = when {
        Regex("(?i)48\\s*(?:hours?|hrs?).*preliminary|preliminary.*48\\s*(?:hours?|hrs?)").containsMatchIn(text) -> "48-hour preliminary"
        text.contains("preliminary", true) || text.contains("interim", true) -> "preliminary"
        text.contains("final report", true) || Regex("(?i)\\bfinal\\b").containsMatchIn(text) -> "final"
        else -> "unspecified"
    }

    private fun ordinal(value: String): Int? = when (
        value.lowercase().replace(Regex("(st|nd|rd|th)$"), "")
    ) {
        "first" -> 1
        "second" -> 2
        "third" -> 3
        "fourth" -> 4
        else -> value.filter(Char::isDigit).toIntOrNull()
    }
}
