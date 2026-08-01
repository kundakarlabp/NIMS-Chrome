package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.kundakarlab.nimsfastsummarymobile.domain.model.Abnormality
import org.kundakarlab.nimsfastsummarymobile.domain.model.NumericComparator
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParseConfidence
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedCultureValue
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedLabValue

/**
 * Conservative NIMS-specific recovery for clinically important values that are
 * commonly printed as flattened narrative PDF text. This layer only fills
 * missing structured values; it never overwrites a parser result with weaker
 * evidence.
 */
object NimsClinicalParsingEnhancer {
    private val narrativeOrganisms = listOf(
        Regex("(?i)\\bburkholderia\\s+pseudomallei\\b") to "Burkholderia pseudomallei",
        Regex("(?i)\\bburkholderia\\s+cepacia(?:\\s+complex)?\\b") to "Burkholderia cepacia complex",
        Regex("(?i)\\bstenotrophomonas\\s+maltophilia\\b") to "Stenotrophomonas maltophilia",
        Regex("(?i)\\belizabethkingia\\s+meningoseptica\\b") to "Elizabethkingia meningoseptica"
    )

    private val treatmentHeading = Regex("(?im)^\\s*(TREATMENT|INITIAL\\s+INTENSIVE\\s+PHASE|ERADICATION\\s+PHASE)\\s*:")
    private val astHeading = Regex("(?im)^\\s*(SENSITIVITY|SUSCEPTIBILITY|ANTIBIOGRAM|RESISTANCE)\\s+(?:REPORT|PATTERN)?\\s*:")
    private val astEvidence = Regex("(?i)\\b(?:SENSITIVE|SUSCEPTIBLE|RESISTANT|INTERMEDIATE|MIC)\\b|\\b[SRID]\\s*[:=-]")

    fun enrichCultures(values: List<ParsedCultureValue>, text: String): List<ParsedCultureValue> {
        if (values.isEmpty()) return values
        val narrativeOrganism = extractNarrativeOrganism(text)
        val treatmentGuidancePresent = treatmentHeading.containsMatchIn(text)
        val structuredAstPresent = astHeading.containsMatchIn(text) && astEvidence.containsMatchIn(text)

        return values.map { value ->
            val recoveredOrganism = value.organism ?: narrativeOrganism
            val comments = value.comments
                .filterNot { it.contains("Antibiogram text was present", ignoreCase = true) }
                .toMutableList()
                .apply {
                    if (treatmentGuidancePresent && !structuredAstPresent) {
                        add("Treatment guidance was present in the source report; no structured susceptibility table was identified.")
                    }
                    if (recoveredOrganism == null && value.growthStatus.name == "GROWTH_DETECTED") {
                        add("Growth was detected, but the organism name could not be extracted; verify the source report.")
                    }
                }
                .distinct()

            value.copy(
                organism = recoveredOrganism,
                organismRaw = value.organismRaw ?: recoveredOrganism,
                comments = comments,
                confidence = if (recoveredOrganism != null) ParseConfidence.HIGH else value.confidence
            )
        }
    }

    fun recoverPriorityLabs(text: String, date: String?): List<ParsedLabValue> = buildList {
        recoverNumeric(
            text = text,
            code = "RBS",
            displayName = "Random blood glucose",
            aliases = listOf(
                "RBS", "Random Blood Sugar", "Random Blood Glucose", "Random Plasma Glucose",
                "Plasma Glucose Random", "Blood Sugar Random", "Glucose Random", "GRBS"
            ),
            range = 0.0..2000.0,
            unit = "mg/dL",
            date = date,
            refLow = 70.0,
            refHigh = 140.0
        )?.let(::add)

        recoverNumeric(
            text = text,
            code = "TBIL",
            displayName = "Total Bilirubin",
            aliases = listOf(
                "Total Bilirubin", "Bilirubin Total", "Serum Bilirubin Total",
                "Total Serum Bilirubin", "T Bilirubin", "T. Bilirubin", "Bilirubin (T)"
            ),
            range = 0.0..80.0,
            unit = "mg/dL",
            date = date,
            refLow = 0.2,
            refHigh = 1.2
        )?.let(::add)
    }

    private fun extractNarrativeOrganism(text: String): String? {
        val cultureNarrative = Regex(
            "(?is)(?:culture\\s+shows?\\s+growth\\s+of|growth\\s+of|organism\\s*(?:isolated)?\\s*[:=-])\\s*([A-Za-z][A-Za-z ._-]{2,120})"
        ).find(text)?.groupValues?.getOrNull(1)
            ?.substringBefore(Regex("(?i)-\\s*(?:ETIOLOGICAL|INFECTION|TREATMENT|HIGH\\s+PROBABILITY|NOTE|COMMENT)"))
            ?.substringBefore('.')
            ?.trim()

        val searchText = cultureNarrative ?: text
        return narrativeOrganisms.firstNotNullOfOrNull { (pattern, canonical) ->
            if (pattern.containsMatchIn(searchText)) canonical else null
        }
    }

    private fun recoverNumeric(
        text: String,
        code: String,
        displayName: String,
        aliases: List<String>,
        range: ClosedFloatingPointRange<Double>,
        unit: String,
        date: String?,
        refLow: Double?,
        refHigh: Double?
    ): ParsedLabValue? {
        val lines = text.lines().map(String::trim).filter(String::isNotBlank)
        aliases.forEach { alias ->
            val label = Regex("(?i)(?:^|[^A-Za-z0-9])${Regex.escape(alias)}(?:[^A-Za-z0-9]|$)")
            lines.forEachIndexed { index, line ->
                if (!label.containsMatchIn(line)) return@forEachIndexed
                val candidate = listOf(line, lines.getOrNull(index + 1).orEmpty()).joinToString(" ")
                val valueMatch = Regex("([<>])?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(mg\\s*/?\\s*dL|mg%)?", RegexOption.IGNORE_CASE)
                    .find(candidate.substringAfter(alias, candidate, ignoreCase = true))
                    ?: return@forEachIndexed
                val numeric = valueMatch.groupValues[2].replace(",", "").toDoubleOrNull() ?: return@forEachIndexed
                if (numeric !in range) return@forEachIndexed
                val abnormality = when {
                    refLow != null && numeric < refLow -> Abnormality.LOW
                    refHigh != null && numeric > refHigh -> Abnormality.HIGH
                    else -> Abnormality.NORMAL
                }
                return ParsedLabValue(
                    canonicalCode = code,
                    displayName = displayName,
                    sourceName = alias,
                    numericValue = numeric,
                    textValue = null,
                    unit = unit,
                    referenceLow = refLow,
                    referenceHigh = refHigh,
                    abnormality = abnormality,
                    resultDate = date,
                    confidence = ParseConfidence.HIGH,
                    comparator = when (valueMatch.groupValues[1]) {
                        "<" -> NumericComparator.LESS_THAN
                        ">" -> NumericComparator.GREATER_THAN
                        else -> NumericComparator.EQUAL
                    }
                )
            }
        }
        return null
    }

    private fun String.substringAfter(delimiter: String, missingDelimiterValue: String, ignoreCase: Boolean): String {
        val index = indexOf(delimiter, ignoreCase = ignoreCase)
        return if (index < 0) missingDelimiterValue else substring(index + delimiter.length)
    }
}
