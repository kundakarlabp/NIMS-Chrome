package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.kundakarlab.nimsfastsummarymobile.domain.model.*
import org.kundakarlab.nimsfastsummarymobile.domain.processing.*

class LocalTextReportProcessor(
    private val summaryBuilder: LocalSummaryBuilder = LocalSummaryBuilder(),
    private val maxBytes: Int = 1024 * 1024
) : ReportProcessor {
    override val name = "On-device"
    override val capabilities = setOf(ProcessingCapability.HTML, ProcessingCapability.PLAIN_TEXT, ProcessingCapability.LABS, ProcessingCapability.CULTURES, ProcessingCapability.SUMMARY)

    override suspend fun parseReport(input: ReportInput): ProcessingResult<ParsedReport> {
        if (input.contentType.contains("pdf", true) || input.bytes.take(4).toByteArray().contentEquals("%PDF".toByteArray())) return ProcessingResult.Unsupported("PDF local parsing is not yet supported. Open the source report manually.")
        if (input.bytes.isEmpty()) return ProcessingResult.Failure("Empty report response.", "LOCAL_EMPTY_RESPONSE", false)
        if (input.bytes.size > maxBytes) return ProcessingResult.Failure("Report text is too large for on-device processing.", "LOCAL_OVERSIZED_TEXT", false)
        val text = normalize(decode(input.bytes, input.contentType))
        val securityCode = securityPageCode(text)
        if (securityCode != null) return ProcessingResult.Failure("NIMS session appears expired. Login again in the WebView.", securityCode, false)
        if (!looksLikeReport(text)) return ProcessingResult.Unsupported("The report format was not recognized for on-device processing.")
        // Extract date from PDF text when row metadata doesn't have it — this is
        // the root cause of "report date unavailable" and Trends showing 0 parameters.
        val effectiveDate = input.dateSent.ifBlank { LabTextParser.extractDateFromText(text) ?: CultureTextParser.extractDateFromText(text) ?: "" }
        val labs = LabTextParser.parse(text, effectiveDate)
        val cultures = CultureTextParser.parse(text, effectiveDate)
        if (labs.isEmpty() && cultures.isEmpty()) return ProcessingResult.Failure("No high-confidence lab or culture rows were found.", "LOCAL_PARSE_INCOMPLETE", true)
        val warnings = buildList { if (input.contentType.contains("html", true)) add("HTML report text was auto-extracted on-device.") }
        return ProcessingResult.Success(ParsedReport(input.reportId, input.reportName, effectiveDate, input.reportType, labs, cultures, warnings, name), name, warnings)
    }

    override suspend fun summarize(reports: List<ParsedReport>, mode: SummaryMode): ProcessingResult<ProcessingSummary> = ProcessingResult.Success(summaryBuilder.build(reports, mode), name)

    private fun decode(bytes: ByteArray, contentType: String): String {
        val raw = bytes.toString(Charsets.UTF_8)
        return if (contentType.contains("html", true) || raw.trimStart().startsWith("<html", true) || raw.trimStart().startsWith("<!doctype", true)) stripHtml(raw) else raw
    }
    private fun stripHtml(raw: String): String = raw.replace(Regex("(?is)<(script|style).*?>.*?</\\1>"), " ").replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("(?i)</(tr|p|div|li|table)>"), "\n").replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&#160;", " ")
    private fun normalize(value: String): String = value.replace('\u00A0', ' ').replace("\r\n", "\n").replace('\r', '\n').lines().joinToString("\n") { it.trim().replace(Regex("[ \\t]{2,}"), " ") }.replace(Regex("\n{3,}"), "\n\n").trim()
    private fun securityPageCode(text: String): String? = when {
        text.contains("captcha", true) -> "LOCAL_CAPTCHA_PAGE"
        text.contains("otp", true) -> "LOCAL_OTP_PAGE"
        text.contains("session expired", true) -> "LOCAL_SESSION_EXPIRED"
        listOf("password", "login").all { text.contains(it, true) } -> "LOCAL_LOGIN_HTML"
        else -> null
    }
    private fun looksLikeReport(text: String): Boolean = listOf(
        // Haematology / CBC
        "hemoglobin", "haemoglobin", "platelet", "wbc", "tlc", "dlc",
        "neutrophil", "lymphocyte", "hematocrit", "pcv", "mcv", "mchc", "mch",
        // Coagulation — previously missing, causing APTT/PT reports to be Unsupported
        "prothrombin", "thromboplastin", "aptt", "coagulation", "fibrinogen", "d-dimer",
        "inr", "pt/inr",
        // Chemistry / LFT / RFT
        "creatinine", "urea", "sodium", "potassium", "bilirubin",
        "sgot", "sgpt", "albumin", "alkaline phosphatase", "troponin",
        "ferritin", "glucose", "crp", "procalcitonin",
        // Urinalysis — previously missing
        "urine routine", "urinalysis", "urine analysis", "specific gravity",
        "urine protein", "pus cells", "epithelial cells", "urine glucose",
        // Microbiology / Culture
        "culture", "specimen", "organism", "no growth", "growth detected",
        "sensitive", "resistant", "aerobic culture",
        "gram staining", "gram stain", "gram smear", "acid fast",
        // Fluid analysis / other
        "fluid", "pus", "sputum", "blood culture", "biopsy", "cytology",
        "smear", "staining", "tb"
    ).any { text.contains(it, true) }
}

data class LabDefinition(
    val canonicalCode: String,
    val displayName: String,
    val labelPatterns: List<Regex>,
    val compatibleUnits: Set<String>,
    val numericRangeGuard: ClosedFloatingPointRange<Double>? = null,
    // Clinical reference ranges (adults, generic — not sex-stratified here).
    // Used for abnormality flagging in trends and summary.
    val refLow: Double? = null,
    val refHigh: Double? = null,
    val unit: String? = null
)

object LabTextParser {
    // Reference ranges sourced from: StatPearls/NCBI, Medscape, standard adult values.
    // These are broad consensus ranges; NIMS lab-specific ranges take priority if parsed from report.
    private val defs = listOf(
        LabDefinition("HB", "Hemoglobin", labels("Hemoglobin", "Haemoglobin", "Hb"), setOf("g/dL", "gm%", "g/dl"), 0.0..30.0, 11.5, 17.5, "g/dL"),
        LabDefinition("WBC", "WBC/TLC", labels("Total WBC", "TLC", "WBC", "Total Leucocyte"), setOf("/cumm", "cells/cumm", "/µL", "x10³/µL", "10³/µL"), 0.0..500000.0, 4000.0, 11000.0, "/cumm"),
        LabDefinition("NEUT", "Neutrophils", labels("Neutrophils", "Polymorphs", "PMN"), setOf("%"), 0.0..100.0, 40.0, 75.0, "%"),
        LabDefinition("LYMPH", "Lymphocytes", labels("Lymphocytes"), setOf("%"), 0.0..100.0, 20.0, 45.0, "%"),
        LabDefinition("MONO", "Monocytes", labels("Monocytes"), setOf("%"), 0.0..100.0, 2.0, 10.0, "%"),
        LabDefinition("EOS", "Eosinophils", labels("Eosinophils"), setOf("%"), 0.0..100.0, 1.0, 6.0, "%"),
        LabDefinition("BASO", "Basophils", labels("Basophils"), setOf("%"), 0.0..100.0, 0.0, 1.0, "%"),
        LabDefinition("RBC", "RBC Count", labels("RBC Count", "RBC"), setOf("million/cumm", "10^6/µL", "x10^6/uL", "x10^12/L"), 0.0..10.0, 3.8, 6.0, "million/cumm"),
        LabDefinition("HCT", "Hematocrit/PCV", labels("Hematocrit", "PCV", "Packed Cell Volume"), setOf("%"), 0.0..80.0, 35.0, 50.0, "%"),
        LabDefinition("MCV", "MCV", labels("MCV", "Mean Corpuscular Volume"), setOf("fL", "fl"), 40.0..140.0, 80.0, 100.0, "fL"),
        LabDefinition("MCH", "MCH", labels("MCH", "Mean Corpuscular Haemoglobin", "Mean Corpuscular Hemoglobin"), setOf("pg"), 5.0..50.0, 27.0, 33.0, "pg"),
        LabDefinition("MCHC", "MCHC", labels("MCHC", "Mean Corpuscular Haemoglobin Concentration", "Mean Corpuscular Hemoglobin Concentration"), setOf("g/dL"), 10.0..50.0, 31.5, 36.0, "g/dL"),
        LabDefinition("RDW", "RDW", labels("RDW", "Red Cell Distribution Width"), setOf("%"), 0.0..40.0, 11.5, 14.5, "%"),
        LabDefinition("PLT", "Platelets", labels("Platelet Count", "Platelets", "PLT"), setOf("lakh/cumm", "/cumm", "cells/cumm", "/µL", "x10³/µL", "10³/µL"), 0.0..2000000.0, 150000.0, 410000.0, "/cumm"),
        LabDefinition("UREA", "Blood Urea", labels("Urea", "Blood Urea", "BUN"), setOf("mg/dL"), 0.0..400.0, 10.0, 40.0, "mg/dL"),
        LabDefinition("CREAT", "Creatinine", labels("Creatinine", "Serum Creatinine", "S. Creatinine"), setOf("mg/dL"), 0.0..50.0, 0.5, 1.2, "mg/dL"),
        LabDefinition("EGFR", "eGFR", labels("eGFR", "GFR", "Estimated GFR"), setOf("mL/min", "ml/min/1.73m2", "mL/min/1.73m²"), 0.0..200.0, 60.0, null, "mL/min"),
        LabDefinition("NA", "Sodium", labels("Sodium", "Serum Sodium", "S. Sodium"), setOf("mmol/L", "mEq/L"), 80.0..200.0, 136.0, 145.0, "mEq/L"),
        LabDefinition("K", "Potassium", labels("Potassium", "Serum Potassium", "S. Potassium"), setOf("mmol/L", "mEq/L"), 1.0..10.0, 3.5, 5.1, "mEq/L"),
        LabDefinition("CL", "Chloride", labels("Chloride", "Serum Chloride"), setOf("mmol/L", "mEq/L"), 50.0..150.0, 98.0, 107.0, "mEq/L"),
        LabDefinition("HCO3", "Bicarbonate", labels("Bicarbonate", "HCO3"), setOf("mmol/L", "mEq/L"), 1.0..60.0, 22.0, 29.0, "mEq/L"),
        LabDefinition("TBIL", "Total Bilirubin", labels("Total Bilirubin", "T. Bilirubin"), setOf("mg/dL"), 0.0..80.0, 0.2, 1.2, "mg/dL"),
        LabDefinition("DBIL", "Direct Bilirubin", labels("Direct Bilirubin", "D. Bilirubin", "Conjugated Bilirubin"), setOf("mg/dL"), 0.0..50.0, 0.0, 0.3, "mg/dL"),
        LabDefinition("IBIL", "Indirect Bilirubin", labels("Indirect Bilirubin", "Unconjugated Bilirubin"), setOf("mg/dL"), 0.0..50.0, 0.2, 0.8, "mg/dL"),
        LabDefinition("AST", "AST/SGOT", labels("AST", "SGOT", "Aspartate Aminotransferase"), setOf("U/L", "IU/L"), 0.0..20000.0, 10.0, 40.0, "U/L"),
        LabDefinition("ALT", "ALT/SGPT", labels("ALT", "SGPT", "Alanine Aminotransferase"), setOf("U/L", "IU/L"), 0.0..20000.0, 7.0, 56.0, "U/L"),
        LabDefinition("ALP", "ALP", labels("ALP", "Alkaline Phosphatase"), setOf("U/L", "IU/L"), 0.0..5000.0, 44.0, 147.0, "U/L"),
        LabDefinition("GGT", "GGT", labels("GGT", "Gamma GT", "Gamma Glutamyl Transferase"), setOf("U/L", "IU/L"), 0.0..5000.0, 9.0, 48.0, "U/L"),
        LabDefinition("ALB", "Albumin", labels("Albumin", "Serum Albumin"), setOf("g/dL"), 0.0..10.0, 3.5, 5.0, "g/dL"),
        LabDefinition("TP", "Total Protein", labels("Total Protein"), setOf("g/dL"), 0.0..15.0, 6.3, 8.2, "g/dL"),
        LabDefinition("CRP", "CRP", labels("CRP", "C-Reactive Protein"), setOf("mg/L", "mg/dL"), 0.0..1000.0, null, 10.0, "mg/L"),
        LabDefinition("PCT", "Procalcitonin", labels("Procalcitonin", "PCT"), setOf("ng/mL"), 0.0..1000.0, null, 0.5, "ng/mL"),
        LabDefinition("PT", "Prothrombin Time", labels("PT", "Prothrombin Time"), setOf("sec", "seconds"), 0.0..200.0, 11.0, 13.5, "sec"),
        LabDefinition("INR", "INR", labels("INR"), emptySet(), 0.0..20.0, 0.8, 1.2, ""),
        LabDefinition("APTT", "aPTT", labels("aPTT", "APTT", "Activated Partial Thromboplastin"), setOf("sec", "seconds"), 0.0..300.0, 25.0, 35.0, "sec"),
        LabDefinition("ESR", "ESR", labels("ESR", "Erythrocyte Sedimentation Rate"), setOf("mm/hr", "mm/1st hour"), 0.0..150.0, null, 20.0, "mm/hr"),
        LabDefinition("LDH", "LDH", labels("LDH", "Lactate Dehydrogenase"), setOf("U/L", "IU/L"), 0.0..10000.0, 135.0, 225.0, "U/L"),
        LabDefinition("URIC", "Uric Acid", labels("Uric Acid", "Serum Uric Acid"), setOf("mg/dL"), 0.0..20.0, 2.4, 7.0, "mg/dL"),
        LabDefinition("GLUCOSE", "Blood Glucose", labels("Random Blood Sugar", "Blood Glucose", "Random Plasma Glucose", "Fasting Blood Sugar", "RBS"), setOf("mg/dL"), 0.0..2000.0, 70.0, 140.0, "mg/dL"),
        LabDefinition("HBA1C", "HbA1c", labels("HbA1c", "Glycated Haemoglobin", "Glycosylated Haemoglobin"), setOf("%"), 0.0..25.0, null, 6.5, "%"),
        LabDefinition("FERR", "Ferritin", labels("Ferritin", "Serum Ferritin"), setOf("ng/mL", "µg/L"), 0.0..10000.0, 15.0, 300.0, "ng/mL"),
        LabDefinition("IRON", "Serum Iron", labels("Serum Iron", "Iron"), setOf("µg/dL", "mcg/dL"), 0.0..500.0, 60.0, 170.0, "µg/dL"),
        LabDefinition("TIBC", "TIBC", labels("TIBC", "Total Iron Binding Capacity"), setOf("µg/dL", "mcg/dL"), 0.0..1000.0, 240.0, 450.0, "µg/dL"),
        LabDefinition("TROP", "Troponin I", labels("Troponin I", "HS Troponin", "Troponin"), setOf("ng/mL", "pg/mL", "ng/L"), 0.0..10000.0, null, 0.04, "ng/mL"),
        LabDefinition("BNP", "NT-proBNP", labels("NT Pro BNP", "NT-proBNP", "BNP"), setOf("pg/mL", "ng/L"), 0.0..100000.0, null, 125.0, "pg/mL")
    )
    private fun labels(vararg values: String) = values.map { Regex("(^|[^A-Za-z0-9])${Regex.escape(it)}([^A-Za-z0-9]|\\s*:|$)", RegexOption.IGNORE_CASE) }

    // Attempt to extract the collection/report date from the PDF text itself.
    // This is the root cause of "report date unavailable" and Trends showing
    // 0 parameters: dateSent from the row metadata should already have a date,
    // but when it's blank, try to find it in the PDF text.
    fun extractDateFromText(text: String): String? {
        val patterns = listOf(
            Regex("(?:Collection|Sample|Collected|Report|Reported|Date)[\\s:]*?(\\d{1,2}[-/][A-Za-z]{3}[-/]\\d{2,4})", RegexOption.IGNORE_CASE),
            Regex("(?:Collection|Sample|Collected|Report|Reported|Date)[\\s:]*?(\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4})", RegexOption.IGNORE_CASE),
            Regex("\\b(\\d{1,2}-(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)-\\d{2,4})\\b", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val candidate = match.groupValues[1]
                if (DateNormalizer.normalize(candidate).sortEpoch != null) return candidate
            }
        }
        return null
    }

    fun parse(text: String, date: String?): List<ParsedLabValue> = text.lines().mapNotNull { parseLine(it, date) }

    private fun parseLine(line: String, date: String?): ParsedLabValue? {
        val def = defs.firstOrNull { d -> d.labelPatterns.any { it.find(line) != null } } ?: return null
        val labelMatch = def.labelPatterns.firstNotNullOfOrNull { it.find(line) } ?: return null
        val before = line.substring(0, labelMatch.range.first)
        val remaining = line.substring(labelMatch.range.last + 1)
        val result = Regex("[:=]?\\s*([<>])?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([a-zA-Z0-9/%µ.^]+(?:/[a-zA-Z0-9.µ]+)?|lakh/cumm|cells/cumm|million/cumm|mmol/L|mEq/L|ng/mL|mg/L|mg/dL|g/dL|gm%|U/L|IU/L|mL/min|sec|seconds|pg/mL|µg/dL|ng/L|mm/hr)?", RegexOption.IGNORE_CASE).find(remaining) ?: return null
        val value = result.groupValues[2].replace(",", "").toDoubleOrNull() ?: return null
        val unit = result.groupValues.getOrNull(3)?.ifBlank { null }
        val comparator = when (result.groupValues[1]) { "<" -> NumericComparator.LESS_THAN; ">" -> NumericComparator.GREATER_THAN; else -> NumericComparator.EQUAL }
        val numbersBefore = Regex("\\d").containsMatchIn(before)
        val compatible = unit == null || def.compatibleUnits.isEmpty() || def.compatibleUnits.any { it.equals(unit, true) }
        val inRange = def.numericRangeGuard?.contains(value) ?: true
        val confidence = when {
            !compatible || !inRange || numbersBefore -> ParseConfidence.LOW
            unit == null -> ParseConfidence.MEDIUM
            else -> ParseConfidence.HIGH
        }
        // Determine abnormality from the reference range if we have one
        val abnormality = when {
            def.refLow != null && value < def.refLow -> if (value < def.refLow * 0.6) Abnormality.CRITICAL else Abnormality.LOW
            def.refHigh != null && value > def.refHigh -> if (value > def.refHigh * 2.0) Abnormality.CRITICAL else Abnormality.HIGH
            def.refLow != null || def.refHigh != null -> Abnormality.NORMAL
            else -> Abnormality.UNKNOWN
        }
        // Try to extract reference range from the line itself (some reports print it inline)
        val refRangeText = Regex("\\(?([0-9.]+)\\s*[-–]\\s*([0-9.]+)\\)?").find(remaining.substringAfter(result.value))
            ?.let { "${it.groupValues[1]}-${it.groupValues[2]}" }

        return ParsedLabValue(
            CanonicalLabCodes.normalize(def.canonicalCode), def.displayName,
            line.substring(labelMatch.range.first, labelMatch.range.last + 1).trim(' ', ':'),
            value, null, unit, def.refLow, def.refHigh, refRangeText, abnormality, date, confidence, comparator
        )
    }
}

object CultureTextParser {
    private val resistanceMarkerPatterns = linkedMapOf(
        "ESBL" to Regex("""\bESBL\b""", RegexOption.IGNORE_CASE), "MRSA" to Regex("""\bMRSA\b""", RegexOption.IGNORE_CASE),
        "VRE" to Regex("""\bVRE\b""", RegexOption.IGNORE_CASE), "CRE" to Regex("""\bCRE\b""", RegexOption.IGNORE_CASE),
        "CRAB" to Regex("""\bCRAB\b""", RegexOption.IGNORE_CASE),
        "Carbapenem resistant" to Regex("""\bcarbapenem(?:[-\s]+)resistant\b""", RegexOption.IGNORE_CASE),
        "Colistin resistant" to Regex("""\bcolistin(?:[-\s]+)resistant\b""", RegexOption.IGNORE_CASE)
    )

    fun extractDateFromText(text: String): String? = LabTextParser.extractDateFromText(text)

    fun parse(text: String, date: String?): List<ParsedCultureValue> {
        if (!text.contains("culture", true) && !text.contains("specimen", true) && !text.contains("organism", true) &&
            !text.contains("no growth", true) && !text.contains("growth detected", true) &&
            !text.contains("gram staining", true) && !text.contains("aerobic", true) &&
            resistanceMarkerPatterns.values.none { it.containsMatchIn(text) }) return emptyList()
        return splitBlocks(text).mapNotNull { parseBlock(it, date) }.distinctBy { listOf(it.specimen, it.organism, it.growthStatus, it.collectionDate).joinToString("|") }
    }
    private fun splitBlocks(text: String): List<String> {
        val blocks = mutableListOf<String>(); val current = StringBuilder()
        text.lines().forEach { line ->
            val starts = Regex("^(specimen|sample|(?:blood|urine)?\\s*culture|organism|isolate|result)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line.trim())
            if (starts && current.isNotBlank()) { blocks += current.toString(); current.clear() }
            current.appendLine(line)
        }
        if (current.isNotBlank()) blocks += current.toString()
        return if (blocks.isEmpty()) listOf(text) else blocks
    }
    private fun parseSusceptibility(block: String): List<AntibioticResult> {
        // ROOT CAUSE of "Neurology Unit Intermediate": the previous implementation
        // used a broad regex ([A-Za-z][A-Za-z /-]{2,30})\s+(Sensitive|Intermediate|Resistant)
        // with no section gating. This matched ANY English phrase preceding those words,
        // including "Neurology Unit Intermediate" (ward name + section heading that happened
        // to follow a culture card). The spec (Phase 10) requires a section-state machine.
        //
        // State machine:
        //   NONE → triggered by a recognised sensitivity-section heading
        //   SUSCEPTIBLE / INTERMEDIATE / RESISTANT → maintained until a stop heading
        //
        // Only tokens that appear in the known-antibiotic dictionary are accepted.
        // Everything else (ward names, comments, organism names, report footers) is ignored.

        val results = mutableListOf<AntibioticResult>()

        // Section headings that start a S/I/R block
        val sectionStart = Regex(
            """^(?:SENSITIVITY\s+REPORT|SUSCEPTIBILITY\s+REPORT|INTERMEDIATE\s+REPORT|RESISTANCE\s+REPORT|RESISTANT\s+REPORT|SUSCEPTIBLE|SENSITIVE|INTERMEDIATE|RESISTANT)$""",
            RegexOption.IGNORE_CASE
        )
        // Stop headings that end all AST parsing for this block
        val sectionStop = Regex(
            """^(?:ISOLATE\s*[:.]|CULTURE\s+REPORT|Fan\s+Blood\s+Culture|COLLECTION\s*[:.]|Comments?\s*[:.]|END\s+OF\s+THE\s+REPORT|Department\s+of|Antimicrobial\s+Stewardship|BMD\s+FOR|NOTE\s*[:.])""",
            RegexOption.IGNORE_CASE
        )

        // Known antibiotic token dictionary (longest-match; covers common NIMS reportings)
        val ANTIBIOTICS = listOf(
            "PIPERACILLIN/TAZOBACTAM", "PIPERACILLIN-TAZOBACTAM", "CEFOPERAZONE/SULBACTAM",
            "CEFOPERAZONE+SULBACTAM", "CEFOPERAZONE-SULBACTAM", "CEFTAZIDIME-AVIBACTAM",
            "CEFTAZIDIME AVIBACTAM", "CEFTOLOZANE-TAZOBACTAM", "AMOXICILLIN/CLAVULANATE",
            "AMOXICILLIN-CLAVULANATE", "AMPICILLIN-SULBACTAM", "AMPICILLIN/SULBACTAM",
            "TRIMETHOPRIM-SULFAMETHOXAZOLE", "TRIMETHOPRIM/SULFAMETHOXAZOLE", "COTRIMOXAZOLE",
            "CO-TRIMOXAZOLE", "POLYMYXIN B", "POLYMYXIN E", "COLISTIN",
            "PIPERACILLIN", "CEFOPERAZONE", "CEFTAZIDIME", "CEFTRIAXONE", "CEFUROXIME",
            "CEFOTAXIME", "CEPHALEXIN", "CEFAZOLIN", "CEFIXIME", "CEFPODOXIME",
            "CEFTAROLINE", "MEROPENEM", "IMIPENEM", "ERTAPENEM", "DORIPENEM",
            "AMPICILLIN", "AMOXICILLIN", "CLOXACILLIN", "OXACILLIN", "METHICILLIN",
            "AZITHROMYCIN", "CLARITHROMYCIN", "ERYTHROMYCIN", "AZTREONAM",
            "GENTAMICIN", "AMIKACIN", "TOBRAMYCIN", "NETILMICIN",
            "CIPROFLOXACIN", "LEVOFLOXACIN", "MOXIFLOXACIN", "NORFLOXACIN",
            "TETRACYCLINE", "DOXYCYCLINE", "MINOCYCLINE", "TIGECYCLINE",
            "CLINDAMYCIN", "LINEZOLID", "VANCOMYCIN", "TEICOPLANIN", "DAPTOMYCIN",
            "METRONIDAZOLE", "NITROFURANTOIN", "FOSFOMYCIN", "RIFAMPICIN", "RIFAMPIN",
            "CHLORAMPHENICOL", "NALIDIXIC ACID", "FUSIDIC ACID", "MUPIROCIN",
            "HIGH LEVEL GENTAMICIN", "HIGH LEVEL STREPTOMYCIN"
        ).sortedByDescending { it.length }  // longest match first

        var currentSection: String? = null

        for (rawLine in block.lines()) {
            val line = rawLine.trim()
                .replace('\u00A0', ' ')  // non-breaking space
                .replace('\u2013', '-')  // en-dash
                .replace('\u2014', '-')  // em-dash
                .replace(Regex("\\s{2,}"), " ")

            if (line.isBlank()) continue

            // Check for stop heading — exit all AST parsing
            if (sectionStop.containsMatchIn(line)) { currentSection = null; continue }

            // Check for section heading
            val sectionMatch = sectionStart.find(line)
            if (sectionMatch != null) {
                currentSection = when {
                    line.contains("resist", true) -> "RESISTANT"
                    line.contains("intermediate", true) -> "INTERMEDIATE"
                    else -> "SUSCEPTIBLE"
                }
                continue
            }

            if (currentSection == null) {
                // Not in a section-state, but check for explicit inline Drug+S/I/R format.
                // This handles "Ceftriaxone R" / "Meropenem Susceptible" placed directly
                // after organism lines, without a preceding sensitivity heading.
                // Requires a known antibiotic name so ward names/comments are rejected.
                val inlineMatch = Regex(
                    """^(${ANTIBIOTICS.joinToString("|") { Regex.escape(it) }})\s+(Sensitive|Susceptible|Intermediate|Resistant|S|I|R)\b""",
                    RegexOption.IGNORE_CASE
                ).find(line)
                if (inlineMatch != null) {
                    val drug = inlineMatch.groupValues[1].lowercase().split(" ").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
                    val rawInterp = inlineMatch.groupValues[2].uppercase()
                    val interp = when (rawInterp) { "R", "RESISTANT" -> "Resistant"; "I", "INTERMEDIATE" -> "Intermediate"; else -> "Susceptible" }
                    results += AntibioticResult(drug, interp, ParseConfidence.MEDIUM)
                }
                continue  // still not in section for upper-only drug names
            }

            // IMPORTANT: check inline Drug+Interpretation FIRST (before bare-name section match).
            // Without this ordering, "Ceftriaxone Resistant" inside a SENSITIVITY REPORT section
            // would be matched as bare "Ceftriaxone" and marked Susceptible (the section heading),
            // then `continue` would skip the inline check, giving the wrong interpretation.
            val inlineInSection = Regex(
                """^(${ANTIBIOTICS.joinToString("|") { Regex.escape(it) }})\s+(Sensitive|Susceptible|Intermediate|Resistant|S|I|R)\b""",
                RegexOption.IGNORE_CASE
            ).find(line)
            if (inlineInSection != null) {
                val drug = inlineInSection.groupValues[1].lowercase().split(" ").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
                val rawInterp = inlineInSection.groupValues[2].uppercase()
                val interp = when (rawInterp) { "R", "RESISTANT" -> "Resistant"; "I", "INTERMEDIATE" -> "Intermediate"; else -> "Susceptible" }
                results += AntibioticResult(drug, interp, ParseConfidence.HIGH)
                continue
            }

            // Bare drug name on its own line — interpretation comes from the section heading
            val upperLine = line.uppercase().replace(Regex("[^A-Z0-9/+\\-\\s]"), " ").trim()
            val matched = ANTIBIOTICS.firstOrNull { drug ->
                upperLine == drug || upperLine.startsWith("$drug ") || upperLine.startsWith("$drug\t")
            }
            if (matched != null) {
                val interp = when (currentSection) {
                    "RESISTANT" -> "Resistant"
                    "INTERMEDIATE" -> "Intermediate"
                    else -> "Susceptible"
                }
                results += AntibioticResult(matched.lowercase().split(" ").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }, interp, ParseConfidence.HIGH)
            }
        }
        return results.distinctBy { it.antibiotic.lowercase() to it.interpretation.lowercase() }
    }

    private fun parseBlock(block: String, date: String?): ParsedCultureValue? {
        val noGrowth = Regex("\\bno\\s+(?:growth|organisms?|bacteria)\\b", RegexOption.IGNORE_CASE).containsMatchIn(block)
        // Extract organism from multiple NIMS patterns
        val organism = listOf(
            Regex("(?:organism|isolate|identified)\\s*[:=]\\s*([^\\n]+)", RegexOption.IGNORE_CASE),
            Regex("growth\\s+of\\s+([^\\n]+)", RegexOption.IGNORE_CASE),
            Regex("(?:culture\\s+shows?|showed?)\\s+([^\\n]+)", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { it.find(block)?.groupValues?.get(1)?.trim()?.takeIf { v -> v.isNotBlank() && v.length < 80 } }
        // Extract specimen from multiple NIMS patterns
        val specimen = listOf(
            Regex("(?:specimen|sample|sample\\s+type|type\\s+of\\s+specimen)\\s*[:=]\\s*([^\\n]+)", RegexOption.IGNORE_CASE),
            Regex("(?:blood|urine|pus|sputum|csf|balf|fluid|swab|tissue|wound)\\s*(?:culture|c/s)", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { p ->
            p.find(block)?.let { m ->
                if (m.groupValues.size > 1) m.groupValues[1].trim() else m.value.trim()
            }?.takeIf { it.isNotBlank() && it.length < 60 }
        }
        // Extract site
        val site = Regex("(?:site|source|body\\s+site)\\s*[:=]\\s*([^\\n]+)", RegexOption.IGNORE_CASE)
            .find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() && it.length < 60 }
        // Extract collection date from block text if not already in date
        val collectionDate = if (!date.isNullOrBlank()) date else extractDateFromText(block)
        val susceptibility = parseSusceptibility(block)
        val markers = resistanceMarkerPatterns.filter { (marker, pattern) -> pattern.containsMatchIn(block) && !isNegatedMarker(block, marker) }.keys.toSet()
        val explicitGrowth = Regex("\\b(growth\\s+of|positive|isolated|growth\\s+detected|growth\\s+present)\\b", RegexOption.IGNORE_CASE).containsMatchIn(block)
        if (!noGrowth && organism.isNullOrBlank() && !explicitGrowth && markers.isEmpty() && susceptibility.isEmpty()) return null
        if (!noGrowth && organism.isNullOrBlank() && !explicitGrowth) {
            return ParsedCultureValue(specimen, site, collectionDate, null, GrowthStatus.UNKNOWN, susceptibility, markers, emptyList(), ParseConfidence.LOW)
        }
        return ParsedCultureValue(specimen, site, collectionDate, organism, if (noGrowth) GrowthStatus.NO_GROWTH else GrowthStatus.GROWTH_DETECTED, susceptibility, markers, emptyList(), ParseConfidence.HIGH)
    }

    private fun isNegatedMarker(block: String, marker: String): Boolean {
        val token = Regex.escape(marker)
        val negation = Regex("\\b(?:no|not)\\s+$token\\b|\\b$token\\s+(?:negative|not\\s+detected)\\b", RegexOption.IGNORE_CASE)
        return negation.containsMatchIn(block)
    }
}
