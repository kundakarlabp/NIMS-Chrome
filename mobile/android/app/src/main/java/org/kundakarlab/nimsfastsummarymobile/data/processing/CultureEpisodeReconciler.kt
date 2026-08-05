package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.kundakarlab.nimsfastsummarymobile.domain.model.AntibioticResult
import org.kundakarlab.nimsfastsummarymobile.domain.model.GrowthStatus
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParseConfidence
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedCultureValue
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedReport

/**
 * Joins preliminary, identification and final susceptibility observations that
 * belong to the same laboratory episode. This is deliberately conservative:
 * a laboratory number is preferred, and fallback grouping keeps specimen/date
 * and bottle/isolate identity so unrelated cultures are not collapsed.
 */
object CultureEpisodeReconciler {
    private data class LocatedCulture(
        val reportIndex: Int,
        val cultureIndex: Int,
        val report: ParsedReport,
        val culture: ParsedCultureValue
    )

    fun reconcile(reports: List<ParsedReport>): List<ParsedReport> {
        if (reports.none { it.cultures.isNotEmpty() }) return reports

        val located = reports.flatMapIndexed { reportIndex, report ->
            report.cultures.mapIndexed { cultureIndex, culture ->
                LocatedCulture(reportIndex, cultureIndex, report, culture)
            }
        }
        val groups = located.groupBy(::episodeKey)
        val culturesByReport = mutableMapOf<Int, MutableList<ParsedCultureValue>>()

        groups.values.forEach { episode ->
            val representative = episode.maxWithOrNull(
                compareBy<LocatedCulture>(
                    { stageRank(it.culture.reportStage) },
                    { confidenceRank(it.culture.confidence) },
                    { if (isPlausibleOrganism(it.culture.organism)) 1 else 0 },
                    { it.culture.susceptibility.size },
                    { dateRank(it.culture.reportingDate ?: it.report.dateSent) }
                )
            ) ?: return@forEach

            val merged = mergeEpisode(episode, representative.culture)
            culturesByReport.getOrPut(representative.reportIndex) { mutableListOf() }.add(merged)
        }

        return reports.mapIndexed { index, report ->
            report.copy(cultures = culturesByReport[index].orEmpty())
        }
    }

    private fun mergeEpisode(
        episode: List<LocatedCulture>,
        representative: ParsedCultureValue
    ): ParsedCultureValue {
        val ranked = episode.sortedWith(
            compareByDescending<LocatedCulture> { stageRank(it.culture.reportStage) }
                .thenByDescending { confidenceRank(it.culture.confidence) }
                .thenByDescending { dateRank(it.culture.reportingDate ?: it.report.dateSent) }
        )

        val organismSource = ranked.firstOrNull { isPlausibleOrganism(it.culture.organism) }?.culture
        val susceptibility = mergeSusceptibility(ranked)
        val comments = ranked.flatMap { it.culture.comments }
            .map(String::trim)
            .filter(String::isNotBlank)
            .filterNot { it.contains("organism not extracted", ignoreCase = true) && organismSource != null }
            .filterNot { it.contains("no structured susceptibility", ignoreCase = true) && susceptibility.isNotEmpty() }
            .distinct()

        val status = when {
            ranked.any { it.culture.growthStatus == GrowthStatus.GROWTH_DETECTED } || organismSource != null -> GrowthStatus.GROWTH_DETECTED
            ranked.any { it.culture.growthStatus == GrowthStatus.PENDING } -> GrowthStatus.PENDING
            ranked.all { it.culture.growthStatus == GrowthStatus.NO_GROWTH } -> GrowthStatus.NO_GROWTH
            else -> representative.growthStatus
        }

        return representative.copy(
            specimen = firstNonBlank(ranked.map { it.culture.specimen }),
            site = firstNonBlank(ranked.map { it.culture.site }),
            collectionDate = earliestDateText(ranked.map { it.culture.collectionDate }),
            organism = organismSource?.organism,
            organismRaw = organismSource?.organismRaw ?: organismSource?.organism,
            growthStatus = status,
            susceptibility = susceptibility,
            explicitResistanceMarkers = ranked.flatMap { it.culture.explicitResistanceMarkers }.toSet(),
            comments = comments,
            confidence = ranked.maxByOrNull { confidenceRank(it.culture.confidence) }?.culture?.confidence
                ?: representative.confidence,
            labStudyNumber = firstNonBlank(ranked.map { it.culture.labStudyNumber }),
            reportingDate = latestDateText(ranked.map { it.culture.reportingDate ?: it.report.dateSent }),
            reportStage = ranked.maxByOrNull { stageRank(it.culture.reportStage) }?.culture?.reportStage,
            bottleName = firstNonBlank(ranked.map { it.culture.bottleName }),
            setNumber = ranked.firstNotNullOfOrNull { it.culture.setNumber },
            bottleNumber = ranked.firstNotNullOfOrNull { it.culture.bottleNumber },
            isolateNumber = ranked.firstNotNullOfOrNull { it.culture.isolateNumber },
            gramStain = firstNonBlank(ranked.map { it.culture.gramStain })
        )
    }

    private fun mergeSusceptibility(ranked: List<LocatedCulture>): List<AntibioticResult> {
        val candidates = linkedMapOf<String, Pair<LocatedCulture, AntibioticResult>>()
        ranked.forEach { located ->
            located.culture.susceptibility.forEach { result ->
                val antibiotic = result.antibiotic.trim()
                val interpretation = normalizeInterpretation(result.interpretation) ?: return@forEach
                if (antibiotic.isBlank()) return@forEach
                val normalized = result.copy(interpretation = interpretation)
                val key = antibiotic.lowercase()
                val current = candidates[key]
                if (current == null || susceptibilityRank(located, normalized) > susceptibilityRank(current.first, current.second)) {
                    candidates[key] = located to normalized
                }
            }
        }
        return candidates.values.map { it.second }.sortedBy { it.antibiotic.lowercase() }
    }

    private fun susceptibilityRank(located: LocatedCulture, result: AntibioticResult): Long {
        val stage = stageRank(located.culture.reportStage).toLong() * 1_000_000L
        val confidence = confidenceRank(result.confidence).toLong() * 100_000L
        val mic = if (result.micValue != null) 10_000L else 0L
        val date = dateRank(located.culture.reportingDate ?: located.report.dateSent).coerceAtLeast(0L) % 10_000L
        return stage + confidence + mic + date
    }

    private fun episodeKey(located: LocatedCulture): String {
        val culture = located.culture
        val lab = culture.labStudyNumber.orEmpty().filter(Char::isLetterOrDigit).lowercase()
        val bottleIdentity = listOf(
            culture.setNumber?.toString().orEmpty(),
            culture.bottleNumber?.toString().orEmpty(),
            culture.isolateNumber?.toString().orEmpty()
        ).joinToString("-")
        if (lab.isNotBlank()) return "lab:$lab|$bottleIdentity"

        val specimen = normalize(culture.specimen ?: culture.site)
        val date = DateNormalizer.normalize(culture.collectionDate ?: located.report.dateSent).sortEpoch
        if (specimen.isNotBlank() && date != null) return "fallback:$specimen|$date|$bottleIdentity"

        return "unique:${located.report.reportId}:${located.cultureIndex}"
    }

    private fun normalizeInterpretation(value: String): String? = when (value.trim().lowercase()) {
        "s", "sensitive", "susceptible" -> "Susceptible"
        "i", "intermediate" -> "Intermediate"
        "r", "resistant" -> "Resistant"
        else -> null
    }

    private fun normalize(value: String?): String = value.orEmpty()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()

    private fun isPlausibleOrganism(value: String?): Boolean {
        val clean = value.orEmpty().trim()
        if (clean.isBlank()) return false
        val lower = clean.lowercase()
        return lower !in setOf("growth detected", "positive", "unknown", "no growth") &&
            !lower.contains("culture") && clean.length <= 120
    }

    private fun firstNonBlank(values: List<String?>): String? = values.firstOrNull { !it.isNullOrBlank() }?.trim()

    private fun earliestDateText(values: List<String?>): String? = values
        .mapNotNull { value -> DateNormalizer.normalize(value).sortEpoch?.let { it to value } }
        .minByOrNull { it.first }?.second
        ?: firstNonBlank(values)

    private fun latestDateText(values: List<String?>): String? = values
        .mapNotNull { value -> DateNormalizer.normalize(value).sortEpoch?.let { it to value } }
        .maxByOrNull { it.first }?.second
        ?: firstNonBlank(values)

    private fun dateRank(value: String?): Long = DateNormalizer.normalize(value).sortEpoch ?: Long.MIN_VALUE

    private fun stageRank(value: String?): Int = when {
        value.orEmpty().contains("final", ignoreCase = true) -> 4
        value.orEmpty().contains("48", ignoreCase = true) -> 3
        value.orEmpty().contains("prelim", ignoreCase = true) -> 2
        value.orEmpty().contains("interim", ignoreCase = true) -> 1
        else -> 0
    }

    private fun confidenceRank(value: ParseConfidence): Int = when (value) {
        ParseConfidence.HIGH -> 2
        ParseConfidence.MEDIUM -> 1
        ParseConfidence.LOW -> 0
    }
}
