package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.kundakarlab.nimsfastsummarymobile.domain.model.AntibioticResult
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParseConfidence

/**
 * Conservative fallback for flattened NIMS susceptibility tables.
 *
 * It accepts only explicit antibiotic-to-S/I/R alignment or drugs contained in
 * a clearly labelled susceptibility section. Treatment guidance alone is never
 * interpreted as an antibiogram.
 */
object NimsSusceptibilityTableParser {
    private data class Drug(val canonical: String, val aliases: List<String>)
    private data class Candidate(val result: AntibioticResult, val score: Int)

    private val drugs = listOf(
        Drug("Penicillin", listOf("penicillin")),
        Drug("Ampicillin", listOf("ampicillin")),
        Drug("Amoxicillin/Clavulanate", listOf("amoxicillin clavulanate", "amoxycillin clavulanate", "amoxicillin/clavulanate")),
        Drug("Piperacillin/Tazobactam", listOf("piperacillin tazobactam", "piperacillin/tazobactam")),
        Drug("Cefoperazone/Sulbactam", listOf("cefoperazone sulbactam", "cefoperazone/sulbactam", "cefoperazone+sulbactam")),
        Drug("Cefoxitin", listOf("cefoxitin")),
        Drug("Cefuroxime", listOf("cefuroxime")),
        Drug("Cefotaxime", listOf("cefotaxime")),
        Drug("Ceftriaxone", listOf("ceftriaxone")),
        Drug("Ceftazidime", listOf("ceftazidime")),
        Drug("Cefepime", listOf("cefepime")),
        Drug("Aztreonam", listOf("aztreonam")),
        Drug("Ertapenem", listOf("ertapenem")),
        Drug("Imipenem", listOf("imipenem")),
        Drug("Meropenem", listOf("meropenem")),
        Drug("Amikacin", listOf("amikacin")),
        Drug("Gentamicin", listOf("gentamicin", "high level gentamicin", "gentamicin high level")),
        Drug("High-level streptomycin", listOf("high level streptomycin", "streptomycin high level")),
        Drug("Tobramycin", listOf("tobramycin")),
        Drug("Ciprofloxacin", listOf("ciprofloxacin")),
        Drug("Levofloxacin", listOf("levofloxacin")),
        Drug("Erythromycin", listOf("erythromycin")),
        Drug("Clindamycin", listOf("clindamycin")),
        Drug("Tetracycline", listOf("tetracycline")),
        Drug("Doxycycline", listOf("doxycycline")),
        Drug("Minocycline", listOf("minocycline")),
        Drug("Tigecycline", listOf("tigecycline")),
        Drug("Vancomycin", listOf("vancomycin")),
        Drug("Teicoplanin", listOf("teicoplanin")),
        Drug("Linezolid", listOf("linezolid")),
        Drug("Daptomycin", listOf("daptomycin")),
        Drug("Colistin", listOf("colistin")),
        Drug("Polymyxin B", listOf("polymyxin b", "polymyxin")),
        Drug("Trimethoprim/Sulfamethoxazole", listOf("trimethoprim sulfamethoxazole", "trimethoprim/sulfamethoxazole", "co trimoxazole", "cotrimoxazole")),
        Drug("Nitrofurantoin", listOf("nitrofurantoin")),
        Drug("Fosfomycin", listOf("fosfomycin")),
        Drug("Chloramphenicol", listOf("chloramphenicol")),
        Drug("Rifampicin", listOf("rifampicin"))
    )

    private val astHeading = Regex("(?i)\\b(susceptibility|sensitivity|antibiogram|resistance\\s+report|sensitive\\s+report|intermediate\\s+report)\\b")
    private val treatmentHeading = Regex("(?im)^\\s*(treatment|initial intensive phase|eradication phase)\\s*:")
    private val interpretationToken = Regex("(?i)(?<![A-Za-z])(susceptible|sensitive|resistant|intermediate|[SIR])(?![A-Za-z])")
    private val micPattern = Regex("(?i)\\bMIC\\s*([<>=≤≥]*)\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(mcg/ml|µg/ml|ug/ml|mg/l)?")

    fun parse(text: String): List<AntibioticResult> {
        val normalized = text.replace('\u00a0', ' ').replace("\r\n", "\n")
        val hasAstHeading = astHeading.containsMatchIn(normalized)
        val explicitPairs = parseExplicitPairs(normalized)
        if (!hasAstHeading && explicitPairs.isEmpty()) return emptyList()
        if (treatmentHeading.containsMatchIn(normalized) && !hasAstHeading && explicitPairs.isEmpty()) return emptyList()

        val candidates = mutableListOf<Candidate>()
        candidates += explicitPairs
        if (hasAstHeading) candidates += parseLabelledSections(normalized)

        val resolved = candidates.groupBy { it.result.antibiotic.lowercase() }
            .mapNotNull { (_, values) ->
                val topScore = values.maxOf { it.score }
                val top = values.filter { it.score == topScore }
                val interpretations = top.map { it.result.interpretation }.distinct()
                if (interpretations.size != 1) null else top.maxByOrNull { if (it.result.micValue != null) 1 else 0 }?.result
            }
            .sortedBy { it.antibiotic.lowercase() }

        if (resolved.size >= 6 && resolved.map { it.interpretation }.distinct() == listOf("Intermediate")) return emptyList()
        return resolved
    }

    private fun parseExplicitPairs(text: String): List<Candidate> {
        val results = mutableListOf<Candidate>()
        text.lines().forEach { rawLine ->
            val line = rawLine.trim().replace(Regex("\\s+"), " ")
            if (line.isBlank()) return@forEach
            drugs.forEach { drug ->
                val match = findDrug(line, drug) ?: return@forEach
                val start = (match.first - 20).coerceAtLeast(0)
                val end = (match.last + 1 + 45).coerceAtMost(line.length)
                val local = line.substring(start, end)
                val localDrugStart = match.first - start
                val localDrugEnd = match.last - start
                val token = interpretationToken.findAll(local)
                    .minByOrNull { distanceToRange(it.range.first, localDrugStart, localDrugEnd) }
                    ?: return@forEach
                if (distanceToRange(token.range.first, localDrugStart, localDrugEnd) > 24) return@forEach
                val interpretation = normalizeInterpretation(token.value) ?: return@forEach
                val mic = micPattern.find(local)
                results += Candidate(
                    AntibioticResult(
                        antibiotic = drug.canonical,
                        interpretation = interpretation,
                        confidence = ParseConfidence.HIGH,
                        micValue = mic?.groupValues?.getOrNull(2)?.toDoubleOrNull(),
                        micComparator = mic?.groupValues?.getOrNull(1)?.ifBlank { null },
                        micUnit = mic?.groupValues?.getOrNull(3)?.ifBlank { null }
                    ),
                    score = 3
                )
            }
        }
        return results
    }

    private fun parseLabelledSections(text: String): List<Candidate> {
        val results = mutableListOf<Candidate>()
        var current: String? = null
        text.lines().forEach { rawLine ->
            val line = rawLine.trim().replace(Regex("\\s+"), " ")
            val headingInterpretation = sectionInterpretation(line)
            if (headingInterpretation != null) {
                current = headingInterpretation
                val after = line.substringAfter(':', "")
                if (after.isNotBlank()) collectSectionDrugs(after, headingInterpretation, results)
                return@forEach
            }
            if (line.matches(Regex("(?i)^(culture report|comments?|note|remarks?|treatment)\\s*:?.*$"))) {
                current = null
                return@forEach
            }
            current?.let { collectSectionDrugs(line, it, results) }
        }
        return results
    }

    private fun collectSectionDrugs(line: String, interpretation: String, output: MutableList<Candidate>) {
        if (line.isBlank() || line.equals("nil", true) || line.equals("none", true)) return
        drugs.forEach { drug ->
            val match = findDrug(line, drug) ?: return@forEach
            val local = line.substring(match.first, line.length.coerceAtMost(match.last + 1 + 45))
            val mic = micPattern.find(local)
            output += Candidate(
                AntibioticResult(
                    antibiotic = drug.canonical,
                    interpretation = interpretation,
                    confidence = ParseConfidence.HIGH,
                    micValue = mic?.groupValues?.getOrNull(2)?.toDoubleOrNull(),
                    micComparator = mic?.groupValues?.getOrNull(1)?.ifBlank { null },
                    micUnit = mic?.groupValues?.getOrNull(3)?.ifBlank { null }
                ),
                score = 2
            )
        }
    }

    private fun sectionInterpretation(line: String): String? = when {
        Regex("(?i)^(sensitive|susceptible|sensitivity report|susceptibility report)\\s*:?.*$").matches(line) -> "Susceptible"
        Regex("(?i)^(intermediate|intermediate report)\\s*:?.*$").matches(line) -> "Intermediate"
        Regex("(?i)^(resistant|resistance report)\\s*:?.*$").matches(line) -> "Resistant"
        else -> null
    }

    private fun findDrug(line: String, drug: Drug): IntRange? {
        drug.aliases.forEach { alias ->
            val pattern = Regex("(?i)(?<![A-Za-z])${Regex.escape(alias).replace("\\ ", "\\s*[/+ -]?\\s*")}(?![A-Za-z])")
            pattern.find(line)?.range?.let { return it }
        }
        return null
    }

    private fun normalizeInterpretation(value: String): String? = when (value.trim().lowercase()) {
        "s", "sensitive", "susceptible" -> "Susceptible"
        "i", "intermediate" -> "Intermediate"
        "r", "resistant" -> "Resistant"
        else -> null
    }

    private fun distanceToRange(position: Int, start: Int, end: Int): Int = when {
        position < start -> start - position
        position > end -> position - end
        else -> 0
    }
}
