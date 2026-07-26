package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.kundakarlab.nimsfastsummarymobile.domain.model.AntibioticResult
import org.kundakarlab.nimsfastsummarymobile.domain.model.GrowthStatus
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParseConfidence
import org.kundakarlab.nimsfastsummarymobile.domain.model.ParsedCultureValue

/**
 * Parser for the sectioned free-text format used by NIMS bacteriology PDFs.
 *
 * The report is treated as a sequence of bottle/isolate episodes. Preliminary,
 * 48-hour and final reports are intentionally retained as separate observations;
 * they are not deduplicated merely because the specimen and organism match.
 */
object NimsCultureTextParser {
    private val dateTime = Regex("\\b(\\d{1,2}[-/]?(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[-/]?\\d{2,4}(?:\\s+\\d{1,2}:\\d{2})?)\\b", RegexOption.IGNORE_CASE)
    private val numericDateTime = Regex("\\b(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}(?:\\s+\\d{1,2}:\\d{2})?)\\b")
    private val bottleHeading = Regex("(?i)^(.*?blood\\s+culture.*?(?:first|second|third|fourth|\\d+(?:st|nd|rd|th)?)\\s+bottle.*?)(?:\\(([^)]*report)\\))?\\s*$")
    private val isolateHeading = Regex("(?i)^\\s*isolate(?:\\s*(?:no\\.?|number))?\\s*[:#-]?\\s*(\\d+)\\b.*$")
    private val sectionHeading = Regex("(?i)^(CULTURE REPORT|SENSITIVITY REPORT|SUSCEPTIBILITY REPORT|INTERMEDIATE REPORT|RESISTANCE REPORT|STAINING|GRAM STAIN(?:ING)?|COMMENTS?|NOTE|REMARKS?)\\s*:?.*$")

    private val resistanceMarkers = linkedMapOf(
        "MRSA" to Regex("\\bMRSA\\b|methicillin\\s+resistant\\s+staphylococcus\\s+aureus", RegexOption.IGNORE_CASE),
        "VRE" to Regex("\\bVRE\\b|vancomycin\\s+resistant\\s+enterococcus", RegexOption.IGNORE_CASE),
        "ESBL" to Regex("\\bESBL\\b", RegexOption.IGNORE_CASE),
        "CRE" to Regex("\\bCRE\\b|carbapenem[-\\s]+resistant", RegexOption.IGNORE_CASE),
        "CRAB" to Regex("\\bCRAB\\b", RegexOption.IGNORE_CASE),
        "MDR" to Regex("\\bMDR\\b|multi[-\\s]?drug[-\\s]?resistant", RegexOption.IGNORE_CASE),
        "XDR" to Regex("\\bXDR\\b|extensively[-\\s]?drug[-\\s]?resistant", RegexOption.IGNORE_CASE),
        "AmpC" to Regex("\\bAmpC\\b", RegexOption.IGNORE_CASE)
    )

    private data class Drug(val canonical: String, val aliases: List<String>)
    private val drugs = listOf(
        Drug("Piperacillin/Tazobactam", listOf("piperacillin/tazobactam", "piperacillin tazobactam")),
        Drug("Cefoperazone/Sulbactam", listOf("cefoperazone+sulbactam", "cefoperazone/sulbactam", "cefoperazone sulbactam")),
        Drug("Amoxicillin/Clavulanate", listOf("amoxicillin-clavulanate", "amoxicillin/clavulanate", "amoxicillin clavulanate", "amoxycillin clavulanate")),
        Drug("Trimethoprim/Sulfamethoxazole", listOf("trimethoprim/sulfamethoxazole", "trimethoprim sulfamethoxazole", "cotrimoxazole", "co-trimoxazole")),
        Drug("Penicillin", listOf("penicillin")), Drug("Ampicillin", listOf("ampicillin")),
        Drug("Oxacillin", listOf("oxacillin")), Drug("Cefoxitin", listOf("cefoxitin")),
        Drug("Cefazolin", listOf("cefazolin")), Drug("Cefuroxime", listOf("cefuroxime")),
        Drug("Cefotaxime", listOf("cefotaxime")), Drug("Ceftriaxone", listOf("ceftriaxone")),
        Drug("Ceftazidime", listOf("ceftazidime")), Drug("Cefepime", listOf("cefepime")),
        Drug("Aztreonam", listOf("aztreonam")), Drug("Ertapenem", listOf("ertapenem")),
        Drug("Imipenem", listOf("imipenem")), Drug("Meropenem", listOf("meropenem")),
        Drug("Amikacin", listOf("amikacin")), Drug("Gentamicin", listOf("gentamicin")),
        Drug("Tobramycin", listOf("tobramycin")), Drug("Ciprofloxacin", listOf("ciprofloxacin")),
        Drug("Levofloxacin", listOf("levofloxacin")), Drug("Moxifloxacin", listOf("moxifloxacin")),
        Drug("Erythromycin", listOf("erythromycin")), Drug("Clindamycin", listOf("clindamycin")),
        Drug("Tetracycline", listOf("tetracycline")), Drug("Doxycycline", listOf("doxycycline")),
        Drug("Minocycline", listOf("minocycline")), Drug("Tigecycline", listOf("tigecycline")),
        Drug("Vancomycin", listOf("vancomycin")), Drug("Teicoplanin", listOf("teicoplanin")),
        Drug("Linezolid", listOf("linezolid")), Drug("Daptomycin", listOf("daptomycin")),
        Drug("Colistin", listOf("colistin")), Drug("Polymyxin B", listOf("polymyxin b", "polymyxin")),
        Drug("Nitrofurantoin", listOf("nitrofurantoin")), Drug("Fosfomycin", listOf("fosfomycin")),
        Drug("Chloramphenicol", listOf("chloramphenicol")), Drug("Rifampicin", listOf("rifampicin")),
        Drug("Metronidazole", listOf("metronidazole")), Drug("Fluconazole", listOf("fluconazole")),
        Drug("Voriconazole", listOf("voriconazole")), Drug("Amphotericin B", listOf("amphotericin b", "amphotericin")),
        Drug("Caspofungin", listOf("caspofungin")), Drug("Micafungin", listOf("micafungin"))
    )

    private val organismMap = listOf(
        Regex("methicillin\\s+resistant\\s+staphylococcus\\s+aureus|staphylococcus\\s+aureus", RegexOption.IGNORE_CASE) to "Staphylococcus aureus",
        Regex("acinetobacter\\s+baumannii(?:/acinetobacter\\s+calcoaceticus\\s+complex)?", RegexOption.IGNORE_CASE) to "Acinetobacter baumannii",
        Regex("klebsiella\\s+pneumoniae", RegexOption.IGNORE_CASE) to "Klebsiella pneumoniae",
        Regex("escherichia\\s+coli|e\\.?\\s*coli", RegexOption.IGNORE_CASE) to "Escherichia coli",
        Regex("pseudomonas\\s+aeruginosa", RegexOption.IGNORE_CASE) to "Pseudomonas aeruginosa",
        Regex("enterococcus\\s+faecium", RegexOption.IGNORE_CASE) to "Enterococcus faecium",
        Regex("enterococcus\\s+faecalis", RegexOption.IGNORE_CASE) to "Enterococcus faecalis",
        Regex("candida\\s+auris", RegexOption.IGNORE_CASE) to "Candida auris",
        Regex("candida\\s+albicans", RegexOption.IGNORE_CASE) to "Candida albicans",
        Regex("candida\\s+tropicalis", RegexOption.IGNORE_CASE) to "Candida tropicalis"
    )

    fun parse(text: String, fallbackDate: String? = null): List<ParsedCultureValue> {
        val normalized = normalize(text)
        if (!looksLikeCulture(normalized)) return emptyList()
        val header = Header.from(normalized, fallbackDate)
        val bottleSegments = splitByAnchors(normalized.lines(), bottleHeading)
        val episodes = if (bottleSegments.size > 1 || bottleSegments.firstOrNull()?.heading != null) bottleSegments else listOf(Segment(null, normalized))
        return episodes.flatMap { bottle ->
            val isolates = splitByAnchors(bottle.body.lines(), isolateHeading)
            val isolateSegments = if (isolates.size > 1 || isolates.firstOrNull()?.heading != null) isolates else listOf(Segment(null, bottle.body))
            isolateSegments.mapNotNull { isolate -> parseEpisode(header, bottle.heading, isolate.heading, isolate.body) }
        }.distinctBy { it.episodeKey() }
    }

    private fun parseEpisode(header: Header, bottleHeadingText: String?, isolateHeadingText: String?, body: String): ParsedCultureValue? {
        val bottleMatch = bottleHeadingText?.let { bottleHeading.matchEntire(it.trim()) }
        val isolateNumber = isolateHeadingText?.let { isolateHeading.matchEntire(it.trim())?.groupValues?.getOrNull(1)?.toIntOrNull() }
        val reportStage = stageOf(listOfNotNull(bottleHeadingText, isolateHeadingText, body.take(300)).joinToString(" "))
        val bottleName = bottleHeadingText?.trim()
        val bottleNumber = ordinalNumber(bottleName)
        val setNumber = Regex("(?i)(?:of|in)\\s+(?:the\\s+)?(first|second|third|fourth|\\d+)\\s+set").find(bottleName.orEmpty())?.groupValues?.get(1)?.let(::ordinalValue)
        val sections = sections(body)
        val cultureText = sections["CULTURE REPORT"].orEmpty().ifBlank { body }
        val gram = firstNonBlank(sections["STAINING"], sections["GRAM STAIN"], sections["GRAM STAINING"])
        val organismRaw = organismRaw(cultureText)
        val organism = organismRaw?.let(::canonicalOrganism)
        val noGrowth = Regex("(?i)\\bno\\s+growth\\b|culture\\s+(?:is|was)\\s+sterile").containsMatchIn(cultureText)
        val growth = Regex("(?i)culture\\s+shows?\\s+growth|growth\\s+of|isolated|positive\\s+culture").containsMatchIn(cultureText)
        val susceptibility = buildList {
            addAll(parseDrugSection(sections["SENSITIVITY REPORT"] ?: sections["SUSCEPTIBILITY REPORT"], "Susceptible"))
            addAll(parseDrugSection(sections["INTERMEDIATE REPORT"], "Intermediate"))
            addAll(parseDrugSection(sections["RESISTANCE REPORT"], "Resistant"))
        }.distinctBy { it.antibiotic.lowercase() to it.interpretation.lowercase() }
        val specimen = specimen(body)
        val site = Regex("(?im)^\\s*COLLECTION\\s*:\\s*([^\\n]+)").find(body)?.groupValues?.get(1)?.trim()
        val comments = buildList {
            gram?.takeIf { it.isNotBlank() }?.let { add("Gram stain: ${compact(it)}") }
            sections["NOTE"]?.takeIf { it.isNotBlank() }?.let { add(compact(it)) }
            sections["COMMENTS"]?.takeIf { it.isNotBlank() }?.let { add(compact(it)) }
            if (susceptibility.isEmpty() && listOf("SENSITIVITY REPORT", "SUSCEPTIBILITY REPORT", "INTERMEDIATE REPORT", "RESISTANCE REPORT").any { !sections[it].isNullOrBlank() }) {
                add("Antibiogram text was present but no supported antibiotic rows were recognized; verify the source report.")
            }
            Regex("(?im)^\\s*(Highly resistant isolate|Kindly correlate clinically|Very high probability of true bacteremia[^\\n]*)").findAll(body).forEach { add(it.value.trim()) }
        }.distinct()
        val markers = resistanceMarkers.filterValues { it.containsMatchIn(body) }.keys
        val hasContent = noGrowth || growth || organism != null || susceptibility.isNotEmpty() || !gram.isNullOrBlank()
        if (!hasContent) return null
        val status = when {
            noGrowth -> GrowthStatus.NO_GROWTH
            growth || organism != null -> GrowthStatus.GROWTH_DETECTED
            reportStage == "preliminary" || reportStage == "48-hour preliminary" -> GrowthStatus.PENDING
            else -> GrowthStatus.UNKNOWN
        }
        return ParsedCultureValue(
            specimen = specimen,
            site = site,
            collectionDate = header.collectionDate,
            organism = organism,
            growthStatus = status,
            susceptibility = susceptibility,
            explicitResistanceMarkers = markers,
            comments = comments,
            confidence = if (organism != null || noGrowth) ParseConfidence.HIGH else ParseConfidence.MEDIUM,
            labStudyNumber = header.labStudyNumber,
            reportingDate = header.reportingDate,
            reportStage = reportStage,
            bottleName = bottleName,
            setNumber = setNumber,
            bottleNumber = bottleNumber,
            isolateNumber = isolateNumber,
            gramStain = gram?.let(::compact),
            organismRaw = organismRaw,
            rawSectionText = body.take(4000)
        )
    }

    private fun parseDrugSection(section: String?, interpretation: String): List<AntibioticResult> {
        val text = section?.trim().orEmpty()
        if (text.isBlank() || Regex("(?i)^NIL\\b").containsMatchIn(text)) return emptyList()
        val hits = mutableListOf<Pair<Int, Drug>>()
        drugs.forEach { drug ->
            drug.aliases.forEach { alias ->
                Regex("(?i)(?<![A-Za-z])${aliasRegex(alias)}(?![A-Za-z])").findAll(text).forEach { hits += it.range.first to drug }
            }
        }
        val ordered = hits.sortedBy { it.first }.distinctBy { it.second.canonical.lowercase() }
        return ordered.mapIndexed { index, (position, drug) ->
            val nextDrugStart = ordered.getOrNull(index + 1)?.first ?: text.length
            val lineEnd = text.indexOf('\n', startIndex = position).let { if (it < 0) text.length else it }
            val boundary = minOf(nextDrugStart, lineEnd)
            val localTail = text.substring(position, boundary)
            val mic = Regex("(?i)\\bMIC\\s*([<>=≤≥]*)\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(mcg/ml|µg/ml|ug/ml|mg/l)?").find(localTail)
            AntibioticResult(
                antibiotic = drug.canonical,
                interpretation = interpretation,
                confidence = ParseConfidence.HIGH,
                micValue = mic?.groupValues?.getOrNull(2)?.toDoubleOrNull(),
                micComparator = mic?.groupValues?.getOrNull(1)?.ifBlank { null },
                micUnit = mic?.groupValues?.getOrNull(3)?.ifBlank { null }
            )
        }
    }

    private fun sections(body: String): Map<String, String> {
        val result = linkedMapOf<String, StringBuilder>()
        var current: String? = null
        body.lines().forEach { line ->
            val heading = sectionHeading.matchEntire(line.trim())?.groupValues?.get(1)?.uppercase()
            if (heading != null) {
                current = when {
                    heading.startsWith("GRAM STAIN") -> "GRAM STAIN"
                    heading.startsWith("COMMENT") -> "COMMENTS"
                    heading.startsWith("REMARK") -> "COMMENTS"
                    else -> heading
                }
                result.getOrPut(current!!) { StringBuilder() }
                val after = line.substringAfter(':', "").trim()
                if (after.isNotBlank()) result[current]!!.appendLine(after)
            } else if (current != null) {
                if (!line.contains("END OF THE REPORT", true) && !line.startsWith("Validated By", true)) result[current]!!.appendLine(line)
            }
        }
        return result.mapValues { it.value.toString().trim() }
    }

    private fun specimen(body: String): String? {
        val matches = Regex("(?im)^\\s*Sample\\s+Processed\\s*:\\s*([^\\n]+)").findAll(body).toList()
        if (matches.isNotEmpty()) return matches.last().groupValues[1].trim()
        return Regex("(?im)^\\s*Sample\\s+Processed\\s+([^\\n]+)").find(body)?.groupValues?.get(1)?.trim()
            ?: Regex("(?im)^\\s*Sample\\s+Type/No\\s*:\\s*([^\\n]+)").find(body)?.groupValues?.get(1)?.trim()
    }

    private fun organismRaw(text: String): String? {
        val capture = Regex("(?is)culture\\s+shows?\\s+growth\\s+of\\s+(.+?)(?=\\n(?:INFECTION CONTROL|SENSITIVITY REPORT|SUSCEPTIBILITY REPORT|INTERMEDIATE REPORT|RESISTANCE REPORT|Note\\s*:|COLLECTION\\s*:|Comments?:)|$)").find(text)?.groupValues?.get(1)
            ?: Regex("(?im)^\\s*(?:Organism|Isolate)\\s*:\\s*([^\\n]+)").find(text)?.groupValues?.get(1)
        return capture?.let(::compact)?.take(180)
    }

    private fun canonicalOrganism(raw: String): String = organismMap.firstNotNullOfOrNull { (regex, name) -> if (regex.containsMatchIn(raw)) name else null }
        ?: raw.substringBefore(" INFECTION CONTROL", "").substringBefore(" is intrinsically", "").trim().lowercase().replaceFirstChar { it.uppercase() }

    private fun stageOf(text: String): String = when {
        Regex("(?i)48\\s*(?:hours?|hrs?).*preliminary|preliminary.*48\\s*(?:hours?|hrs?)").containsMatchIn(text) -> "48-hour preliminary"
        text.contains("preliminary", true) || text.contains("interim", true) -> "preliminary"
        text.contains("final report", true) || Regex("(?i)\\bfinal\\b").containsMatchIn(text) -> "final"
        else -> "unspecified"
    }

    private data class Header(val labStudyNumber: String?, val collectionDate: String?, val reportingDate: String?) {
        companion object {
            fun from(text: String, fallbackDate: String?): Header {
                val lab = Regex("(?im)Lab/Study\\s+No\\.?\\s*:\\s*([A-Za-z0-9/-]+)").find(text)?.groupValues?.get(1)
                val collection = labeledDate(text, "Coll\\.?/Study\\s+Date|Collection\\s+Date|Collected\\s+Date") ?: fallbackDate
                val reporting = labeledDate(text, "Reporting\\s+Date|Report\\s+Date")
                return Header(lab, collection, reporting)
            }
            private fun labeledDate(text: String, label: String): String? {
                val line = Regex("(?im)(?:$label)\\s*:\\s*([^\\n]+)").find(text)?.groupValues?.get(1).orEmpty()
                return dateTime.find(line)?.groupValues?.get(1) ?: numericDateTime.find(line)?.groupValues?.get(1)
            }
        }
    }

    private data class Segment(val heading: String?, val body: String)
    private fun splitByAnchors(lines: List<String>, pattern: Regex): List<Segment> {
        val out = mutableListOf<Segment>()
        var heading: String? = null
        val body = StringBuilder()
        lines.forEach { line ->
            if (pattern.matches(line.trim())) {
                if (body.isNotBlank() || heading != null) out += Segment(heading, body.toString())
                heading = line.trim()
                body.clear()
                body.appendLine(line)
            } else body.appendLine(line)
        }
        if (body.isNotBlank() || heading != null) out += Segment(heading, body.toString())
        return out.ifEmpty { listOf(Segment(null, lines.joinToString("\n"))) }
    }

    private fun ordinalNumber(text: String?): Int? = Regex("(?i)(first|second|third|fourth|\\d+(?:st|nd|rd|th)?)\\s+bottle").find(text.orEmpty())?.groupValues?.get(1)?.let(::ordinalValue)
    private fun ordinalValue(value: String): Int? = when (value.lowercase().replace(Regex("(st|nd|rd|th)$"), "")) { "first" -> 1; "second" -> 2; "third" -> 3; "fourth" -> 4; else -> value.filter(Char::isDigit).toIntOrNull() }
    private fun aliasRegex(alias: String): String = alias.trim().split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it).replace("/", "[/\\s]*").replace("\\+", "[+\\s]*") }
    private fun normalize(text: String): String = text.replace('\u00A0', ' ').replace("\r\n", "\n").replace('\r', '\n').lines().joinToString("\n") { it.trim().replace(Regex("[ \\t]{2,}"), " ") }.replace(Regex("\n{3,}"), "\n\n").trim()
    private fun compact(value: String): String = value.replace(Regex("\\s+"), " ").trim()
    private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }
    private fun looksLikeCulture(text: String): Boolean = listOf("CULTURE REPORT", "SENSITIVITY REPORT", "RESISTANCE REPORT", "STAINING", "blood culture", "growth of", "no growth").any { text.contains(it, true) }
    private fun ParsedCultureValue.episodeKey(): String = listOf(labStudyNumber, bottleNumber, isolateNumber, reportStage, reportingDate, organism, growthStatus.name).joinToString("|")
}
