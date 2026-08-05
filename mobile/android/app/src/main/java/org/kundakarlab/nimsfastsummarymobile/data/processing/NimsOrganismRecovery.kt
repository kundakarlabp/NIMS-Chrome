package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.kundakarlab.nimsfastsummarymobile.domain.model.GrowthStatus
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParseConfidence
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedCultureValue

/** Conservative organism recovery from explicit culture/isolation statements. */
object NimsOrganismRecovery {
    private val organisms = listOf(
        Regex("(?i)\\bburkholderia\\s+pseudomallei\\b") to "Burkholderia pseudomallei",
        Regex("(?i)\\bburkholderia\\s+cepacia(?:\\s+complex)?\\b") to "Burkholderia cepacia complex",
        Regex("(?i)\\bacinetobacter\\s+baumannii(?:/acinetobacter\\s+calcoaceticus\\s+complex)?\\b") to "Acinetobacter baumannii",
        Regex("(?i)\\bklebsiella\\s+pneumoniae\\b") to "Klebsiella pneumoniae",
        Regex("(?i)\\bescherichia\\s+coli\\b|\\be\\.?\\s*coli\\b") to "Escherichia coli",
        Regex("(?i)\\bpseudomonas\\s+aeruginosa\\b") to "Pseudomonas aeruginosa",
        Regex("(?i)\\benterococcus\\s+faecium\\b") to "Enterococcus faecium",
        Regex("(?i)\\benterococcus\\s+faecalis\\b") to "Enterococcus faecalis",
        Regex("(?i)\\benterococcus\\s+(?:species|spp\\.?)\\b") to "Enterococcus species",
        Regex("(?i)\\bstaphylococcus\\s+aureus\\b") to "Staphylococcus aureus",
        Regex("(?i)\\bcoagulase[-\\s]+negative\\s+staphylococc(?:us|i)\\b|\\bcons\\b") to "Coagulase-negative Staphylococcus",
        Regex("(?i)\\bstaphylococcus\\s+(?:species|spp\\.?)\\b") to "Staphylococcus species",
        Regex("(?i)\\bstenotrophomonas\\s+maltophilia\\b") to "Stenotrophomonas maltophilia",
        Regex("(?i)\\belizabethkingia\\s+meningoseptica\\b") to "Elizabethkingia meningoseptica",
        Regex("(?i)\\benterobacter\\s+cloacae(?:\\s+complex)?\\b") to "Enterobacter cloacae complex",
        Regex("(?i)\\bserratia\\s+marcescens\\b") to "Serratia marcescens",
        Regex("(?i)\\bproteus\\s+mirabilis\\b") to "Proteus mirabilis",
        Regex("(?i)\\bmorganella\\s+morganii\\b") to "Morganella morganii",
        Regex("(?i)\\bcitrobacter\\s+freundii\\b") to "Citrobacter freundii",
        Regex("(?i)\\bcandida\\s+auris\\b") to "Candida auris",
        Regex("(?i)\\bcandida\\s+albicans\\b") to "Candida albicans",
        Regex("(?i)\\bcandida\\s+tropicalis\\b") to "Candida tropicalis",
        Regex("(?i)\\bcandida\\s+parapsilosis\\b") to "Candida parapsilosis",
        Regex("(?i)\\bcryptococcus\\s+neoformans\\b") to "Cryptococcus neoformans",
        Regex("(?i)\\baspergillus\\s+(?:flavus|fumigatus|niger|terreus)\\b") to null,
        Regex("(?i)\\baspergillus\\s+(?:species|spp\\.?)\\b") to "Aspergillus species"
    )

    private val explicitStatement = Regex(
        "(?is)(?:culture\\s+shows?\\s+growth\\s+of|growth\\s+of|organism\\s*(?:isolated|grown)?\\s*[:=-]|isolated\\s*[:=-]?)\\s*([^\\n.]{3,160})"
    )
    private val cultureEvidence = Regex("(?i)\\b(culture|growth|isolated|organism)\\b")

    fun enrich(values: List<ParsedCultureValue>, text: String): List<ParsedCultureValue> {
        if (values.isEmpty() || NimsClinicalParsingEnhancer.isBiomarkerOnlyReport(text)) return values
        val explicit = explicitStatement.findAll(text)
            .mapNotNull { match -> canonical(match.groupValues[1]) }
            .firstOrNull()
        val fallback = if (explicit == null && cultureEvidence.containsMatchIn(text)) canonical(text) else explicit
        if (fallback.isNullOrBlank()) return values

        val growthCandidates = values.indices.filter { index ->
            values[index].growthStatus == GrowthStatus.GROWTH_DETECTED && !plausible(values[index].organism)
        }
        if (growthCandidates.size != 1) return values
        val target = growthCandidates.single()
        return values.mapIndexed { index, value ->
            if (index != target) value else value.copy(
                organism = fallback,
                organismRaw = fallback,
                confidence = ParseConfidence.HIGH,
                comments = value.comments.filterNot { it.contains("organism not extracted", ignoreCase = true) }
            )
        }
    }

    private fun canonical(text: String): String? {
        organisms.forEach { (pattern, fixed) ->
            val match = pattern.find(text) ?: return@forEach
            if (fixed != null) return fixed
            val words = match.value.trim().split(Regex("\\s+"))
            return words.joinToString(" ") { word -> word.lowercase().replaceFirstChar(Char::uppercase) }
        }
        return null
    }

    private fun plausible(value: String?): Boolean {
        val lower = value.orEmpty().trim().lowercase()
        return lower.isNotBlank() && lower !in setOf("growth detected", "positive", "unknown", "no growth") && !lower.contains("culture")
    }
}
