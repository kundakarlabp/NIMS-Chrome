package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.kundakarlab.nimsfastsummarymobile.domain.model.Abnormality
import org.kundakarlab.nimsfastsummarymobile.domain.model.AntibioticResult
import org.kundakarlab.nimsfastsummarymobile.domain.model.GrowthStatus
import org.kundakarlab.nimsfastsummarymobile.domain.model.NumericComparator
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParseConfidence
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedCultureValue
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedLabValue

/**
 * Narrow fallback for flattened text emitted by Android PDF extraction.
 * It supplements, rather than replaces, the conservative primary parsers.
 */
object NimsPdfFallbackParser {
    private data class NumericDefinition(
        val code: String,
        val name: String,
        val aliases: List<String>,
        val defaultUnit: String?,
        val guard: ClosedFloatingPointRange<Double>
    )

    private val numericDefinitions = listOf(
        NumericDefinition("HB", "Hemoglobin", listOf("haemoglobin", "hemoglobin", "hgb", "hb"), "g/dL", 0.0..30.0),
        NumericDefinition("WBC", "WBC/TLC", listOf("total leucocyte count", "total leukocyte count", "total wbc count", "wbc count", "tlc", "wbc"), "/cumm", 0.0..500000.0),
        NumericDefinition("PLT", "Platelets", listOf("platelet count", "platelets", "plt"), "/cumm", 0.0..2000000.0),
        NumericDefinition("RBC", "RBC Count", listOf("rbc count", "total rbc", "rbc"), "million/cumm", 0.0..10.0),
        NumericDefinition("HCT", "Hematocrit/PCV", listOf("packed cell volume", "hematocrit", "pcv"), "%", 0.0..80.0),
        NumericDefinition("MCV", "MCV", listOf("mean corpuscular volume", "mcv"), "fL", 30.0..160.0),
        NumericDefinition("MCH", "MCH", listOf("mean corpuscular hemoglobin", "mean corpuscular haemoglobin", "mch"), "pg", 5.0..60.0),
        NumericDefinition("MCHC", "MCHC", listOf("mean corpuscular hemoglobin concentration", "mean corpuscular haemoglobin concentration", "mchc"), "g/dL", 10.0..60.0),
        NumericDefinition("RDW", "RDW", listOf("red cell distribution width", "rdw-cv", "rdw"), "%", 0.0..50.0),
        NumericDefinition("NEUT", "Neutrophils", listOf("neutrophils", "polymorphs", "pmn"), "%", 0.0..100.0),
        NumericDefinition("LYMPH", "Lymphocytes", listOf("lymphocytes", "lymphocyte"), "%", 0.0..100.0),
        NumericDefinition("MONO", "Monocytes", listOf("monocytes", "monocyte"), "%", 0.0..100.0),
        NumericDefinition("EOS", "Eosinophils", listOf("eosinophils", "eosinophil"), "%", 0.0..100.0),
        NumericDefinition("BASO", "Basophils", listOf("basophils", "basophil"), "%", 0.0..100.0),
        NumericDefinition("CRP", "CRP", listOf("c-reactive protein quantitative", "c reactive protein quantitative", "c-reactive protein", "crp quantitative", "crp"), "mg/L", 0.0..2000.0),
        NumericDefinition("ESR", "ESR", listOf("erythrocyte sedimentation rate", "esr westergren", "esr"), "mm/hr", 0.0..200.0),
        NumericDefinition("PCT", "Procalcitonin", listOf("procalcitonin", "pct"), "ng/mL", 0.0..2000.0),
        NumericDefinition("URINE_PH", "Urine pH", listOf("reaction ph", "urine ph", "ph"), null, 3.0..10.0),
        NumericDefinition("URINE_SG", "Urine specific gravity", listOf("specific gravity", "sp gravity", "sp. gravity"), null, 1.0..1.1),
        NumericDefinition("URINE_WBC", "Urine pus cells/WBC", listOf("pus cells", "urine wbc", "wbc/hpf"), "/hpf", 0.0..1000.0),
        NumericDefinition("URINE_RBC", "Urine RBC", listOf("red blood cells", "urine rbc", "rbc/hpf"), "/hpf", 0.0..1000.0),
        NumericDefinition("URINE_EPI", "Urine epithelial cells", listOf("epithelial cells"), "/hpf", 0.0..1000.0)
    )

    private data class TextDefinition(val code: String, val name: String, val aliases: List<String>)
    private val urineTextDefinitions = listOf(
        TextDefinition("URINE_COLOUR", "Urine colour", listOf("colour", "color")),
        TextDefinition("URINE_APPEARANCE", "Urine appearance", listOf("appearance", "clarity")),
        TextDefinition("URINE_PROTEIN", "Urine protein/albumin", listOf("protein", "albumin")),
        TextDefinition("URINE_GLUCOSE", "Urine glucose", listOf("glucose", "sugar")),
        TextDefinition("URINE_KETONE", "Urine ketones", listOf("ketone bodies", "ketones")),
        TextDefinition("URINE_BLOOD", "Urine blood", listOf("occult blood", "blood")),
        TextDefinition("URINE_NITRITE", "Urine nitrite", listOf("nitrite")),
        TextDefinition("URINE_LE", "Urine leukocyte esterase", listOf("leucocyte esterase", "leukocyte esterase")),
        TextDefinition("URINE_BACTERIA", "Urine bacteria", listOf("bacteria")),
        TextDefinition("URINE_CASTS", "Urine casts", listOf("casts")),
        TextDefinition("URINE_CRYSTALS", "Urine crystals", listOf("crystals")),
        TextDefinition("URINE_YEAST", "Urine yeast", listOf("yeast cells", "yeast")),
        TextDefinition("URINE_EPI_TEXT", "Urine epithelial cells", listOf("epithelial cells"))
    )

    fun parseLabs(text: String, date: String?): List<ParsedLabValue> {
        val normalized = normalize(text)
        val lines = normalized.lines().filter(String::isNotBlank)
        val windows = buildList {
            addAll(lines)
            lines.indices.forEach { index ->
                for (size in 2..4) {
                    if (index + size <= lines.size) add(lines.subList(index, index + size).joinToString(" "))
                }
            }
        }
        val urineContext = Regex("""(?i)complete\s+urine|urine\s+examination|urinalysis|urine\s+routine""").containsMatchIn(normalized)
        val numeric = numericDefinitions
            .filter { urineContext || !it.code.startsWith("URINE_") }
            .mapNotNull { definition ->
                val contextual = when {
                    urineContext && definition.code == "URINE_RBC" -> definition.copy(aliases = definition.aliases + "rbc")
                    urineContext && definition.code == "URINE_WBC" -> definition.copy(aliases = definition.aliases + "wbc")
                    else -> definition
                }
                windows.firstNotNullOfOrNull { parseNumericWindow(it, contextual, date) }
            }
        val qualitative = if (urineContext) urineTextDefinitions.mapNotNull { definition ->
            windows.firstNotNullOfOrNull { parseTextWindow(it, definition, date) }
        } else emptyList()
        return (numeric + qualitative).distinctBy { "${it.canonicalCode}|${it.numericValue}|${it.textValue}" }
    }

    fun enrichCultures(cultures: List<ParsedCultureValue>, text: String, fallbackDate: String?): List<ParsedCultureValue> {
        val normalized = normalize(text)
        val organismRaw = extractOrganism(normalized)
        val organism = organismRaw?.let(::canonicalOrganism)
        val ast = parseAst(normalized)
        if (cultures.isNotEmpty()) {
            return cultures.map { culture ->
                if (culture.growthStatus != GrowthStatus.GROWTH_DETECTED) culture else culture.copy(
                    organism = culture.organism ?: organism,
                    organismRaw = culture.organismRaw ?: organismRaw,
                    susceptibility = if (culture.susceptibility.isNotEmpty()) culture.susceptibility else ast,
                    confidence = if (culture.organism != null || organism != null) ParseConfidence.HIGH else culture.confidence
                )
            }
        }
        if (organism == null && !Regex("""(?i)growth\s+(?:detected|of)|culture\s+positive|isolated""").containsMatchIn(normalized)) return emptyList()
        return listOf(
            ParsedCultureValue(
                specimen = extractLabeled(normalized, listOf("sample processed", "specimen", "sample type")),
                site = extractLabeled(normalized, listOf("collection", "site")),
                collectionDate = fallbackDate,
                organism = organism,
                growthStatus = GrowthStatus.GROWTH_DETECTED,
                susceptibility = ast,
                explicitResistanceMarkers = emptySet(),
                comments = if (ast.isEmpty()) listOf("Susceptibility was not reliably extracted; verify the source report.") else emptyList(),
                confidence = if (organism != null) ParseConfidence.HIGH else ParseConfidence.MEDIUM,
                organismRaw = organismRaw,
                rawSectionText = normalized.take(4000)
            )
        )
    }

    private fun parseNumericWindow(window: String, definition: NumericDefinition, date: String?): ParsedLabValue? {
        val alias = definition.aliases.firstOrNull {
            Regex("""(?i)(?<![A-Za-z0-9])${Regex.escape(it)}(?![A-Za-z0-9])""").containsMatchIn(window)
        } ?: return null
        val after = window.substringAfter(alias, "", ignoreCase = true).trim().take(120)
        val match = Regex(
            """(?i)[:=\-–]*\s*([<>])?\s*([0-9][0-9,]*(?:\.[0-9]+)?)\s*(x?10\s*\^?\s*[36912](?:/u?l)?|lakh/cumm|million/cumm|cells/cumm|/cumm|/hpf|mm/?hr|mm\s*(?:in|at)?\s*1st\s*h(?:ou)?r|mg/dl|mg/l|ng/ml|g/dl|gm%|%|fl|pg)?"""
        ).find(after) ?: return null
        var value = match.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null
        val unit = normalizeUnit(match.groupValues.getOrNull(3)?.ifBlank { null }) ?: definition.defaultUnit
        if (definition.code in setOf("WBC", "PLT") && value < 1000.0) {
            val local = match.value.lowercase()
            when {
                "lakh" in local -> value *= 100000.0
                Regex("""10\s*\^?\s*3|x10\s*3""").containsMatchIn(local) -> value *= 1000.0
                definition.code == "PLT" && value in 10.0..1000.0 -> value *= 1000.0
            }
        }
        if (value !in definition.guard) return null
        val comparator = when (match.groupValues[1]) {
            "<" -> NumericComparator.LESS_THAN
            ">" -> NumericComparator.GREATER_THAN
            else -> NumericComparator.EQUAL
        }
        return ParsedLabValue(
            canonicalCode = definition.code,
            displayName = definition.name,
            sourceName = alias,
            numericValue = value,
            textValue = null,
            unit = unit,
            referenceLow = null,
            referenceHigh = null,
            abnormality = Abnormality.UNKNOWN,
            resultDate = date,
            confidence = if (match.groupValues.getOrNull(3).orEmpty().isNotBlank()) ParseConfidence.HIGH else ParseConfidence.MEDIUM,
            comparator = comparator
        )
    }

    private fun parseTextWindow(window: String, definition: TextDefinition, date: String?): ParsedLabValue? {
        val alias = definition.aliases.firstOrNull {
            Regex("""(?i)(?<![A-Za-z0-9])${Regex.escape(it)}(?![A-Za-z0-9])""").containsMatchIn(window)
        } ?: return null
        val after = window.substringAfter(alias, "", ignoreCase = true).trim()
        val value = Regex(
            """(?i)[:=\-–]*\s*(negative|positive|nil|none|absent|present|trace|few|occasional|moderate|many|clear|turbid|cloudy|pale\s+yellow|yellow|straw|amber|\+{1,4})\b"""
        ).find(after)?.groupValues?.get(1)?.trim() ?: return null
        return ParsedLabValue(
            canonicalCode = definition.code,
            displayName = definition.name,
            sourceName = alias,
            numericValue = null,
            textValue = value,
            unit = null,
            referenceLow = null,
            referenceHigh = null,
            abnormality = Abnormality.UNKNOWN,
            resultDate = date,
            confidence = ParseConfidence.MEDIUM
        )
    }

    private fun extractOrganism(text: String): String? {
        val patterns = listOf(
            Regex("""(?im)^\s*(?:organism(?:\s+isolated)?|isolate(?:\s+identified)?|identification)\s*[:\-]\s*([^\n]+)"""),
            Regex("""(?i)\b(?:growth\s+of|isolated\s+as|identified\s+as|culture\s+(?:grew|yielded))\s+([A-Z][A-Za-z.\-]+(?:\s+[a-z][A-Za-z.\-]+){0,3})"""),
            Regex("""(?i)\b((?:methicillin[-\s]+resistant\s+)?Staphylococcus\s+aureus|Acinetobacter\s+baumannii(?:\s+complex)?|Klebsiella\s+pneumoniae|Escherichia\s+coli|E\.?\s*coli|Pseudomonas\s+aeruginosa|Enterococcus\s+(?:faecium|faecalis)|Burkholderia\s+cepacia(?:\s+complex)?|Candida\s+[A-Za-z]+|Cryptococcus\s+[A-Za-z]+)\b""")
        )
        return patterns.firstNotNullOfOrNull { it.find(text)?.groupValues?.getOrNull(1)?.trim()?.trim('.', ':', ';') }
            ?.takeUnless { Regex("""(?i)^(growth detected|positive|organism|isolate)$""").matches(it) }
    }

    private fun canonicalOrganism(raw: String): String = raw
        .replace(Regex("""(?i)^methicillin[-\s]+resistant\s+"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .lowercase()
        .split(' ')
        .joinToString(" ") { token ->
            if (token.length <= 2 && token.endsWith('.')) token.uppercase() else token.replaceFirstChar(Char::uppercase)
        }

    private val antibioticAliases = linkedMapOf(
        "Meropenem" to listOf("meropenem"), "Imipenem" to listOf("imipenem"), "Ertapenem" to listOf("ertapenem"),
        "Ceftriaxone" to listOf("ceftriaxone"), "Ceftazidime" to listOf("ceftazidime"), "Cefepime" to listOf("cefepime"),
        "Piperacillin/Tazobactam" to listOf("piperacillin/tazobactam", "piperacillin tazobactam", "pip-tazo"),
        "Cefoperazone/Sulbactam" to listOf("cefoperazone/sulbactam", "cefoperazone sulbactam"),
        "Amikacin" to listOf("amikacin"), "Gentamicin" to listOf("gentamicin"),
        "Ciprofloxacin" to listOf("ciprofloxacin"), "Levofloxacin" to listOf("levofloxacin"),
        "Colistin" to listOf("colistin"), "Polymyxin B" to listOf("polymyxin b"),
        "Tigecycline" to listOf("tigecycline"), "Minocycline" to listOf("minocycline"),
        "Vancomycin" to listOf("vancomycin"), "Teicoplanin" to listOf("teicoplanin"),
        "Linezolid" to listOf("linezolid"), "Daptomycin" to listOf("daptomycin"),
        "Clindamycin" to listOf("clindamycin"), "Erythromycin" to listOf("erythromycin"),
        "Cotrimoxazole" to listOf("cotrimoxazole", "co-trimoxazole", "trimethoprim sulfamethoxazole")
    )

    private fun parseAst(text: String): List<AntibioticResult> = antibioticAliases.mapNotNull { (canonical, aliases) ->
        val hit = aliases.firstNotNullOfOrNull { alias ->
            Regex(
                """(?i)(?<![A-Za-z])${Regex.escape(alias)}(?![A-Za-z])[^\n]{0,30}\b(S|I|R|susceptible|sensitive|intermediate|resistant)\b"""
            ).find(text)
        } ?: return@mapNotNull null
        val raw = hit.groupValues[1]
        val interpretation = when (raw.lowercase()) {
            "s", "susceptible", "sensitive" -> "Susceptible"
            "i", "intermediate" -> "Intermediate"
            else -> "Resistant"
        }
        AntibioticResult(canonical, interpretation, ParseConfidence.MEDIUM)
    }

    private fun extractLabeled(text: String, labels: List<String>): String? = labels.firstNotNullOfOrNull { label ->
        Regex("""(?im)^\s*${Regex.escape(label)}\s*[:\-]\s*([^\n]+)""")
            .find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
    }

    private fun normalizeUnit(unit: String?): String? = unit?.replace(Regex("""\s+"""), "")?.let {
        when (it.lowercase()) {
            "mmhr", "mm/hr", "mmin1sthr", "mmat1sthr" -> "mm/hr"
            "mg/dl" -> "mg/dL"
            "mg/l" -> "mg/L"
            "ng/ml" -> "ng/mL"
            "g/dl" -> "g/dL"
            "fl" -> "fL"
            else -> it
        }
    }

    private fun normalize(text: String): String = text
        .replace('\u00A0', ' ')
        .replace('\u200B'.toString(), "")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .lines()
        .joinToString("\n") { it.trim().replace(Regex("""[ \t]{2,}"""), " ") }
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}
