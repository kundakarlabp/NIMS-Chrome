package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.kundakarlab.nimsfastsummarymobile.domain.model.Abnormality
import org.kundakarlab.nimsfastsummarymobile.domain.model.NumericComparator
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParseConfidence
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedCultureValue
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedLabValue

/** Conservative NIMS-specific recovery and safety filtering. */
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
    private val narrativeTerminator = Regex("(?is)-\\s*(?:ETIOLOGICAL|INFECTION|TREATMENT|HIGH\\s+PROBABILITY|NOTE|COMMENT).*$")
    private val explicitCultureEvidence = Regex("(?i)\\b(culture\\s+shows?|growth\\s+(?:detected|of)|organism\\s+(?:isolated|grown)|no\\s+growth|blood\\s+culture|urine\\s+culture|aerobic\\s+culture)\\b")
    private val biomarkerOnly = Regex("(?i)\\b(galactomannan|aspergillus\\s+antigen|beta[ -]?d[ -]?glucan|β[ -]?d[ -]?glucan|fungitell|bdg)\\b")

    fun enrichCultures(values: List<ParsedCultureValue>, text: String): List<ParsedCultureValue> {
        if (values.isEmpty()) return values
        if (isBiomarkerOnlyReport(text)) return emptyList()

        val narrativeOrganism = extractNarrativeOrganism(text)
        val treatmentGuidancePresent = treatmentHeading.containsMatchIn(text)
        val structuredAstPresent = astHeading.containsMatchIn(text) && astEvidence.containsMatchIn(text)

        return values.map { value ->
            val sourceOrganism = value.organism?.takeIf(::isPlausibleOrganism)
            val recoveredOrganism = sourceOrganism ?: narrativeOrganism
            val comments = value.comments
                .filterNot { it.contains("Antibiogram text was present", ignoreCase = true) }
                .map(::sanitizeClinicalComment)
                .filter(String::isNotBlank)
                .toMutableList()
                .apply {
                    if (treatmentGuidancePresent && !structuredAstPresent) {
                        add("Treatment guidance present; no structured susceptibility table identified.")
                    }
                    if (recoveredOrganism == null && value.growthStatus.name == "GROWTH_DETECTED") {
                        add("Organism not extracted; review the source report.")
                    }
                }
                .distinct()

            value.copy(
                organism = recoveredOrganism,
                organismRaw = value.organismRaw?.takeIf(::isPlausibleOrganism) ?: recoveredOrganism,
                comments = comments,
                confidence = if (recoveredOrganism != null) ParseConfidence.HIGH else value.confidence
            )
        }
    }

    fun recoverPriorityLabs(text: String, date: String?): List<ParsedLabValue> = buildList {
        recoverNumeric(
            text, "RBS", "Random blood glucose",
            listOf("RBS", "Random Blood Sugar", "Random Blood Glucose", "Random Plasma Glucose", "Plasma Glucose Random", "Blood Sugar Random", "Glucose Random", "GRBS"),
            0.0..2000.0, "mg/dL", date, 70.0, 140.0
        )?.let(::add)

        recoverNumeric(
            text, "TBIL", "Total Bilirubin",
            listOf("Serum Bilirubin (Total)", "Bilirubin (Total)", "Total Bilirubin", "Bilirubin Total", "Serum Bilirubin Total", "Total Serum Bilirubin", "T Bilirubin", "T. Bilirubin"),
            0.0..80.0, "mg/dL", date, 0.2, 1.2
        )?.let(::add)

        recoverNumeric(
            text, "GM_INDEX", "Galactomannan index",
            listOf("Galactomannan Index", "Aspergillus Galactomannan", "Galactomannan Ag", "GM Index"),
            0.0..20.0, "", date, null, 0.5,
            allowUnitless = true
        )?.let(::add)

        recoverNumeric(
            text, "BDG", "Beta-D-glucan",
            listOf("Beta D glucan", "Beta-D-glucan", "β-D-glucan", "BDG", "Fungitell"),
            0.0..50000.0, "pg/mL", date, null, 80.0
        )?.let(::add)
    }

    fun isBiomarkerOnlyReport(text: String): Boolean =
        biomarkerOnly.containsMatchIn(text) && !explicitCultureEvidence.containsMatchIn(text)

    private fun extractNarrativeOrganism(text: String): String? {
        val cultureNarrative = Regex(
            "(?is)(?:culture\\s+shows?\\s+growth\\s+of|growth\\s+of|organism\\s*(?:isolated)?\\s*[:=-])\\s*([A-Za-z][A-Za-z ._-]{2,120})"
        ).find(text)?.groupValues?.getOrNull(1)
            ?.replace(narrativeTerminator, "")
            ?.substringBefore('.')
            ?.trim()

        val searchText = cultureNarrative ?: text
        return narrativeOrganisms.firstNotNullOfOrNull { (pattern, canonical) ->
            if (pattern.containsMatchIn(searchText)) canonical else null
        }
    }

    private fun isPlausibleOrganism(value: String): Boolean {
        val clean = value.trim()
        if (clean.length !in 4..100) return false
        val lower = clean.lowercase()
        if (listOf("aerobic culture", "gram negative aerobic culture", "urine aerobic culture", "growth detected", "no growth", "galactomannan", "beta d glucan", "beta-d-glucan").any { lower == it || lower.startsWith("$it ") }) return false
        return Regex("^[A-Za-z][A-Za-z.-]+(?:\\s+[A-Za-z][A-Za-z.-]+){1,4}$").matches(clean)
    }

    private fun sanitizeClinicalComment(value: String): String {
        val stopped = value
            .substringBefore("CR No", value, ignoreCase = true)
            .substringBefore("Patient Name", value, ignoreCase = true)
            .substringBefore("Age/Sex", value, ignoreCase = true)
            .substringBefore("Dept/Unit", value, ignoreCase = true)
            .substringBefore("Room/Bed", value, ignoreCase = true)
            .substringBefore("Validated By", value, ignoreCase = true)
        return stopped.replace(Regex("\\s+"), " ").trim().take(240)
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
        refHigh: Double?,
        allowUnitless: Boolean = false
    ): ParsedLabValue? {
        val lines = text.lines().map(String::trim).filter(String::isNotBlank)
        aliases.forEach { alias ->
            val label = Regex("(?i)(?:^|[^A-Za-z0-9])${Regex.escape(alias)}(?:[^A-Za-z0-9]|$)")
            lines.forEachIndexed { index, line ->
                if (!label.containsMatchIn(line)) return@forEachIndexed

                val candidates = buildList {
                    add(line.substringAfter(alias, line, ignoreCase = true))
                    for (offset in 1..3) lines.getOrNull(index + offset)?.let(::add)
                }
                val match = candidates.firstNotNullOfOrNull { candidate ->
                    val found = VALUE_PATTERN.find(candidate) ?: return@firstNotNullOfOrNull null
                    val parsedUnit = found.groupValues[3].replace(Regex("\\s+"), "")
                    val contextAllowsUnitless = allowUnitless &&
                        (candidate.contains("index", true) || candidate.contains("result", true) || candidate.contains("value", true) || candidate.matches(Regex("^[<>]?\\s*[0-9].*")))
                    if (parsedUnit.isBlank() && !contextAllowsUnitless) return@firstNotNullOfOrNull null
                    found
                } ?: return@forEachIndexed

                val numeric = match.groupValues[2].replace(",", "").toDoubleOrNull() ?: return@forEachIndexed
                if (numeric !in range) return@forEachIndexed
                val parsedUnit = match.groupValues[3].replace(Regex("\\s+"), "").takeIf(String::isNotBlank)
                    ?.replace("mgdL", "mg/dL", true)
                    ?.replace("pgmL", "pg/mL", true)
                    ?: unit
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
                    unit = parsedUnit,
                    referenceLow = refLow,
                    referenceHigh = refHigh,
                    abnormality = abnormality,
                    resultDate = date,
                    confidence = ParseConfidence.HIGH,
                    comparator = when (match.groupValues[1]) {
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

    private fun String.substringBefore(delimiter: String, missingDelimiterValue: String, ignoreCase: Boolean): String {
        val index = indexOf(delimiter, ignoreCase = ignoreCase)
        return if (index < 0) missingDelimiterValue else substring(0, index)
    }

    private val VALUE_PATTERN = Regex(
        "([<>])?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(mg\\s*/?\\s*dL|mg%|pg\\s*/?\\s*mL|index)?",
        RegexOption.IGNORE_CASE
    )
}
