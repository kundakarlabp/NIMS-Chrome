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

    fun enrich(values: List<ParsedCultureValue>): List<ParsedCultureValue> = values.map { value ->
        val source = value.rawSectionText.orEmpty()
        val heading = value.bottleName?.takeIf { it.isNotBlank() }
            ?: bottleLine.find(source)?.groupValues?.getOrNull(1)?.trim()
        val bottle = value.bottleNumber
            ?: bottleNumber.find(heading.orEmpty())?.groupValues?.getOrNull(1)?.let(::ordinal)
        val set = value.setNumber
            ?: setNumber.find(heading.orEmpty())?.groupValues?.getOrNull(1)?.let(::ordinal)
        val stage = value.reportStage?.takeUnless { it.isBlank() || it == "unspecified" }
            ?: stage(heading.orEmpty() + " " + source.take(300))
        value.copy(
            bottleName = heading,
            bottleNumber = bottle,
            setNumber = set,
            reportStage = stage
        )
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
