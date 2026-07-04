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
        return ProcessingResult.Success(ParsedReport(input.reportId, input.reportName, effectiveDate, input.reportType, labs, cultures, warnings, name, rawText = text), name, warnings)
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
        // Chemistry / labs
        "hemoglobin", "haemoglobin", "platelet", "creatinine", "sodium", "bilirubin",
        "culture", "specimen", "organism", "crp", "procalcitonin", "potassium", "wbc", "urea",
        // Broader lab terms
        "tlc", "dlc", "neutrophil", "lymphocyte", "hematocrit", "pcv", "mcv", "mchc",
        "sgot", "sgpt", "albumin", "alkaline phosphatase", "troponin", "ferritin", "glucose",
        // Bacteriology/microbiology
        "no growth", "growth detected", "sensitive", "resistant", "aerobic culture",
        // Pathology / other
        "biopsy", "cytology", "smear", "staining", "gram", "acid fast", "tb",
        "fluid", "pus", "sputum", "blood culture"
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
        "MDR" to Regex("""\bMDR\b|\bmulti[-\s]?drug[-\s]?resistant\b""", RegexOption.IGNORE_CASE),
        "XDR" to Regex("""\bXDR\b|\bextensively[-\s]drug[-\s]resistant\b""", RegexOption.IGNORE_CASE),
        "PDR" to Regex("""\bPDR\b|\bpan[-\s]drug[-\s]resistant\b""", RegexOption.IGNORE_CASE),
        "AmpC" to Regex("""\bAmpC\b""", RegexOption.IGNORE_CASE),
        "Carbapenem resistant" to Regex("""\bcarbapenem(?:[-\s]+)resistant\b""", RegexOption.IGNORE_CASE),
        "Colistin resistant" to Regex("""\bcolistin(?:[-\s]+)resistant\b""", RegexOption.IGNORE_CASE)
    )

    // Clinically common isolates seen on NIMS microbiology reports. Species-level
    // entries are listed before their bare genus so a specific match wins when the
    // report names the species; the genus-only fallback still catches reports that
    // only print the genus (e.g. "Candida sp.", "Klebsiella spp."). This directly
    // answers "should be able to interpret what organism is typed in the report"
    // for reports that don't use an explicit "Organism:" label at all.
    private val organismPatterns: List<Pair<Regex, String>> = listOf(
        "Escherichia coli" to "E\\.?\\s*coli|Escherichia\\s+coli",
        "Klebsiella pneumoniae" to "Klebsiella\\s+pneumoniae",
        "Klebsiella oxytoca" to "Klebsiella\\s+oxytoca",
        "Klebsiella species" to "Klebsiella\\s+(?:sp\\.?|spp\\.?|species)\\b|\\bKlebsiella\\b",
        "Pseudomonas aeruginosa" to "Pseudomonas\\s+aeruginosa",
        "Pseudomonas species" to "Pseudomonas\\s+(?:sp\\.?|spp\\.?|species)\\b|\\bPseudomonas\\b",
        "Acinetobacter baumannii" to "Acinetobacter\\s+baumannii",
        "Acinetobacter species" to "Acinetobacter\\s+(?:sp\\.?|spp\\.?|species)\\b|\\bAcinetobacter\\b",
        "Proteus mirabilis" to "Proteus\\s+mirabilis",
        "Proteus vulgaris" to "Proteus\\s+vulgaris",
        "Proteus species" to "\\bProteus\\b",
        "Enterobacter cloacae" to "Enterobacter\\s+cloacae",
        "Enterobacter aerogenes" to "Enterobacter\\s+aerogenes",
        "Enterobacter species" to "\\bEnterobacter\\b",
        "Citrobacter freundii" to "Citrobacter\\s+freundii",
        "Citrobacter koseri" to "Citrobacter\\s+koseri",
        "Citrobacter species" to "\\bCitrobacter\\b",
        "Morganella morganii" to "Morganella\\s+morganii",
        "Providencia stuartii" to "Providencia\\s+stuartii",
        "Providencia species" to "\\bProvidencia\\b",
        "Serratia marcescens" to "Serratia\\s+marcescens",
        "Serratia species" to "\\bSerratia\\b",
        "Salmonella typhi" to "Salmonella\\s+(?:enterica\\s+serovar\\s+)?[Tt]yphi\\b",
        "Salmonella paratyphi" to "Salmonella\\s+(?:enterica\\s+serovar\\s+)?[Pp]aratyphi\\b",
        "Salmonella species" to "\\bSalmonella\\b",
        "Shigella species" to "\\bShigella\\b",
        "Vibrio cholerae" to "Vibrio\\s+cholerae",
        "Stenotrophomonas maltophilia" to "Stenotrophomonas\\s+maltophilia",
        "Burkholderia cepacia" to "Burkholderia\\s+cepacia",
        "Haemophilus influenzae" to "Ha?emophilus\\s+influenzae",
        "Neisseria gonorrhoeae" to "Neisseria\\s+gonorrhoeae",
        "Neisseria meningitidis" to "Neisseria\\s+meningitidis",
        "Staphylococcus aureus" to "Staph(?:ylococcus)?\\s+aureus",
        "Coagulase-negative Staphylococcus" to "coagulase[-\\s]negative\\s+staph(?:ylococcus)?|\\bCoNS\\b",
        "Staphylococcus epidermidis" to "Staph(?:ylococcus)?\\s+epidermidis",
        "Staphylococcus saprophyticus" to "Staph(?:ylococcus)?\\s+saprophyticus",
        "Staphylococcus species" to "\\bStaphylococcus\\b",
        "Streptococcus pneumoniae" to "Strep(?:tococcus)?\\s+pneumoniae|\\bpneumococcus\\b",
        "Streptococcus pyogenes" to "Strep(?:tococcus)?\\s+pyogenes|group\\s+A\\s+strep(?:tococcus)?",
        "Streptococcus agalactiae" to "Strep(?:tococcus)?\\s+agalactiae|group\\s+B\\s+strep(?:tococcus)?",
        "Viridans streptococci" to "viridans\\s+strep(?:tococci|tococcus)?",
        "Streptococcus species" to "\\bStreptococcus\\b",
        "Enterococcus faecalis" to "Enterococcus\\s+faecalis",
        "Enterococcus faecium" to "Enterococcus\\s+faecium",
        "Enterococcus species" to "\\bEnterococcus\\b",
        "Listeria monocytogenes" to "Listeria\\s+monocytogenes",
        "Bacillus species" to "\\bBacillus\\b",
        "Corynebacterium species" to "\\bCorynebacterium\\b",
        "Candida albicans" to "Candida\\s+albicans",
        "Candida glabrata" to "Candida\\s+glabrata",
        "Candida tropicalis" to "Candida\\s+tropicalis",
        "Candida krusei" to "Candida\\s+krusei",
        "Candida parapsilosis" to "Candida\\s+parapsilosis",
        "Candida auris" to "Candida\\s+auris",
        "Candida species" to "Candida\\s+(?:sp\\.?|spp\\.?|species)\\b|\\bCandida\\b",
        "Aspergillus species" to "\\bAspergillus\\b",
        "Cryptococcus neoformans" to "Cryptococcus\\s+neoformans",
        "Mycobacterium tuberculosis" to "Mycobacterium\\s+tuberculosis|\\bMTB\\b|AFB\\s+(?:positive|detected|seen)",
        "Bacteroides fragilis" to "Bacteroides\\s+fragilis",
        "Clostridioides difficile" to "Clostridi(?:oides|um)\\s+difficile|\\bC\\.\\s*diff(?:icile)?\\b"
    ).map { (name, pattern) -> Regex(pattern, RegexOption.IGNORE_CASE) to name }

    private val commentLabelPattern = Regex(
        "(?:comment|note|remark|remarks|interpretation|advice|clinical\\s+correlation)\\s*[:\\-]\\s*([^\\n]+)",
        RegexOption.IGNORE_CASE
    )

    // Same trigger words used at the whole-text gate in parse(); re-used per block
    // so a block that survives splitBlocks but carries genuine culture signal is
    // never silently dropped, while stray non-culture fragments still are.
    private val cultureSignalWords = listOf(
        "culture", "specimen", "organism", "isolate", "no growth", "growth detected",
        "growth of", "sensitive", "resistant", "susceptible", "intermediate",
        "aerobic culture", "gram staining", "colony", "cfu"
    )
    private fun hasCultureSignal(block: String): Boolean =
        cultureSignalWords.any { block.contains(it, true) } || resistanceMarkerPatterns.values.any { it.containsMatchIn(block) }

    fun extractDateFromText(text: String): String? = LabTextParser.extractDateFromText(text)

    fun parse(text: String, date: String?): List<ParsedCultureValue> {
        if (!text.contains("culture", true) && !text.contains("specimen", true) && !text.contains("organism", true) &&
            !text.contains("no growth", true) && !text.contains("growth detected", true) &&
            !text.contains("gram staining", true) && !text.contains("aerobic", true) &&
            resistanceMarkerPatterns.values.none { it.containsMatchIn(text) }) return emptyList()
        return splitBlocks(text).mapNotNull { parseBlock(it, date) }
            .distinctBy { culture ->
                listOf(
                    culture.specimen, culture.site, culture.organism, culture.growthStatus.name, culture.collectionDate,
                    culture.susceptibility.joinToString("|") { "${it.antibiotic.lowercase()}=${it.interpretation.lowercase()}" }
                ).joinToString("||")
            }
    }
    // Recognizes the start of a real culture/microbiology section. Used both to
    // split the report into blocks and to gate the "never discard" fallback below
    // (only a block that genuinely BEGINS a culture section is eligible for the
    // fallback — this is what stops a preceding lab-panel block that merely ends
    // with a "Blood Culture" header from being surfaced as a spurious culture row).
    private val blockStartPattern = Regex(
        "^(specimen|sample|(?:blood|urine|aerobic|anaerobic|fungal)?\\s*culture|organism|isolate|result|" +
            "bacteriology|microbiology|(?:antibiotic\\s+)?sensitivity|susceptibility|c/s)\\b",
        RegexOption.IGNORE_CASE
    )

    private fun splitBlocks(text: String): List<String> {
        val blocks = mutableListOf<String>(); val current = StringBuilder()
        text.lines().forEach { line ->
            val starts = blockStartPattern.containsMatchIn(line.trim())
            if (starts && current.isNotBlank()) { blocks += current.toString(); current.clear() }
            current.appendLine(line)
        }
        if (current.isNotBlank()) blocks += current.toString()
        return if (blocks.isEmpty()) listOf(text) else blocks
    }

    // Matches "Antibiotic-name  Sensitive/Resistant/Intermediate", "Antibiotic  S/I/R",
    // and MIC-table rows like "Amikacin  <=2  S" or "Ampicillin  32 ug/mL  R" — the
    // three layouts NIMS antibiogram tables actually use. Antibiotic names are
    // allowed to contain "/", "+", "." and digits so combinations like
    // "Piperacillin/Tazobactam" and "Amoxicillin-Clavulanic Acid" are captured whole.
    private fun parseSusceptibility(block: String): List<AntibioticResult> {
        val nameChars = "[A-Za-z][A-Za-z0-9 /+.\\-]{1,45}?"
        val wordRows = Regex("($nameChars)\\s+(Sensitive|Susceptible|Intermediate|Resistant)\\b", RegexOption.IGNORE_CASE)
            .findAll(block).map { AntibioticResult(cleanAntibioticName(it.groupValues[1]), it.groupValues[2].replaceFirstChar { c -> c.uppercase() }, ParseConfidence.MEDIUM) }
        val micRows = Regex("($nameChars)\\s+(?:(?:<=|>=|≤|≥|<|>|=)?\\s*[0-9]+(?:\\.[0-9]+)?\\s*(?:µg/mL|ug/mL|mg/L)?\\s+)?\\b(S|I|R)\\b(?!\\w)", RegexOption.IGNORE_CASE)
            .findAll(block).mapNotNull { match ->
                val name = cleanAntibioticName(match.groupValues[1])
                if (name.length < 3 || KNOWN_ANTIBIOTICS.none { name.contains(it, true) }) return@mapNotNull null
                val interpretation = when (match.groupValues[2].uppercase()) { "S" -> "Susceptible"; "I" -> "Intermediate"; else -> "Resistant" }
                AntibioticResult(name, interpretation, ParseConfidence.MEDIUM)
            }
        // A susceptible/resistant word-row for an antibiotic is more explicit than a
        // bare-letter SIR match; prefer it when both regexes matched the same drug.
        return (wordRows + micRows)
            .distinctBy { it.antibiotic.lowercase() }
            .toList()
    }

    private fun cleanAntibioticName(raw: String): String = raw.trim().trim(':', '-', '.').replace(Regex("\\s{2,}"), " ")

    // Guards the loose MIC-row regex above (bare "Name  R" is otherwise too generic
    // and would match unrelated report lines) to real antibiotic names/classes.
    private val KNOWN_ANTIBIOTICS = listOf(
        "penicillin", "ampicillin", "amoxicillin", "amoxicillin/clavulanic", "amoxiclav", "piperacillin",
        "tazobactam", "cefazolin", "cefuroxime", "cefotaxime", "ceftriaxone", "ceftazidime", "cefepime",
        "cefoperazone", "sulbactam", "aztreonam", "imipenem", "meropenem", "ertapenem", "doripenem",
        "gentamicin", "amikacin", "tobramycin", "netilmicin", "ciprofloxacin", "levofloxacin", "moxifloxacin",
        "ofloxacin", "norfloxacin", "nalidixic", "tetracycline", "doxycycline", "minocycline", "tigecycline",
        "erythromycin", "azithromycin", "clarithromycin", "clindamycin", "vancomycin", "teicoplanin",
        "linezolid", "daptomycin", "colistin", "polymyxin", "trimethoprim", "sulfamethoxazole", "cotrimoxazole",
        "nitrofurantoin", "fosfomycin", "chloramphenicol", "rifampicin", "metronidazole", "fluconazole",
        "voriconazole", "amphotericin", "caspofungin", "micafungin", "isoniazid", "ethambutol", "pyrazinamide",
        "streptomycin", "oxacillin", "methicillin", "cefixime", "cefpodoxime", "aztreonam", "amoxycillin"
    )

    private fun findOrganism(block: String): String? = organismPatterns.firstNotNullOfOrNull { (pattern, name) -> if (pattern.containsMatchIn(block)) name else null }

    private fun findComments(block: String): List<String> =
        commentLabelPattern.findAll(block).map { it.groupValues[1].trim() }.filter { it.isNotBlank() && it.length < 300 }.distinct().toList()

    private fun parseBlock(block: String, date: String?): ParsedCultureValue? {
        val noGrowth = Regex("\\bno\\s+(?:growth|organisms?|bacteria)\\b", RegexOption.IGNORE_CASE).containsMatchIn(block)
        // Extract organism: prefer an explicit label first (most precise wording),
        // then fall back to recognizing any known organism named directly in the
        // block, which is the common NIMS layout that doesn't use an "Organism:"
        // label at all (e.g. "Escherichia coli grown; sensitivity as follows").
        val organism = listOf(
            Regex("(?:organism|isolate|identified)\\s*[:=]\\s*([^\\n]+)", RegexOption.IGNORE_CASE),
            Regex("growth\\s+of\\s+([^\\n]+)", RegexOption.IGNORE_CASE),
            Regex("(?:culture\\s+shows?|showed?)\\s+([^\\n]+)", RegexOption.IGNORE_CASE)
        ).firstNotNullOfOrNull { it.find(block)?.groupValues?.get(1)?.trim()?.takeIf { v -> v.isNotBlank() && v.length < 80 } }
            // The labeled capture is often the whole rest of the line (e.g. "growth
            // of Candida albicans noted, no organism label used"). If a known
            // organism appears inside that capture, prefer the clean canonical
            // name over the raw trailing prose; only fall back to the raw capture
            // for organism names our dictionary doesn't recognize.
            ?.let { captured -> findOrganism(captured) ?: captured }
            ?: findOrganism(block)
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
        val comments = findComments(block)
        val explicitGrowth = Regex("\\b(growth\\s+of|positive|isolated|growth\\s+detected|growth\\s+present)\\b", RegexOption.IGNORE_CASE).containsMatchIn(block)

        val hasSignal = noGrowth || !organism.isNullOrBlank() || explicitGrowth || markers.isNotEmpty() || susceptibility.isNotEmpty() || comments.isNotEmpty()
        if (!hasSignal) {
            // Never silently discard a block that GENUINELY BEGINS a culture
            // section (its first line matches blockStartPattern — e.g. "Aerobic
            // culture:", "Bacteriology:") but whose remaining content wasn't
            // matched by any structured extractor above. This is scoped to blocks
            // that truly start a culture section — not just any block containing
            // the word "culture" somewhere — so a preceding lab-panel block that
            // merely ends with a trailing "Blood Culture" header (its own content
            // is unrelated lab values) is correctly excluded rather than surfaced
            // as a spurious low-confidence culture row.
            val firstLine = block.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            val wordCount = block.trim().split(Regex("\\s+")).count { it.isNotBlank() }
            val meaningful = blockStartPattern.containsMatchIn(firstLine) && wordCount in 4..120 && block.trim().length <= 500
            if (!meaningful) return null
            val excerpt = block.trim().replace(Regex("\\s+"), " ").take(180)
            return ParsedCultureValue(
                specimen, site, collectionDate, null, GrowthStatus.UNKNOWN, emptyList(), emptySet(),
                listOf("Not confidently parsed — review original report.", excerpt), ParseConfidence.LOW
            )
        }
        // Growth status: NO_GROWTH is explicit. GROWTH_DETECTED requires either an
        // identified organism or explicit growth wording — a resistance marker,
        // susceptibility row, or comment mentioned alone (no organism, no explicit
        // growth phrase) does not by itself confirm growth, so it stays UNKNOWN
        // rather than being over-asserted as a positive culture.
        val growthStatus = when {
            noGrowth -> GrowthStatus.NO_GROWTH
            !organism.isNullOrBlank() || explicitGrowth -> GrowthStatus.GROWTH_DETECTED
            else -> GrowthStatus.UNKNOWN
        }
        // No-growth with no organism is a fully valid, correctly-parsed result
        // (absence of an organism is expected, not a parsing weakness) and stays
        // HIGH confidence. MEDIUM is the genuinely uncertain case: growth wording
        // was found but the organism name itself wasn't identified. LOW is the
        // ambiguous case: only a marker/susceptibility/comment was seen, with no
        // explicit growth or no-growth statement either way.
        val confidence = when {
            !organism.isNullOrBlank() -> ParseConfidence.HIGH
            noGrowth -> ParseConfidence.HIGH
            growthStatus == GrowthStatus.GROWTH_DETECTED -> ParseConfidence.MEDIUM
            else -> ParseConfidence.LOW
        }
        return ParsedCultureValue(specimen, site, collectionDate, organism, growthStatus, susceptibility, markers, comments, confidence)
    }

    private fun isNegatedMarker(block: String, marker: String): Boolean {
        val token = Regex.escape(marker)
        val negation = Regex("\\b(?:no|not)\\s+$token\\b|\\b$token\\s+(?:negative|not\\s+detected)\\b", RegexOption.IGNORE_CASE)
        return negation.containsMatchIn(block)
    }
}
