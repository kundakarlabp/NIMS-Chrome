package org.kundakarlab.nimsresults

/**
 * Conservative observation extraction from *labelled* signed PDF rows.
 * Not OCR and not inference: narrative numerals and controls are excluded.
 * Display is explicitly marked PDF-derived; all source pages remain available.
 */
data class PdfMeasuredValue(
    val parameter: String,
    val value: String,
    val unit: String,
    val reference: String,
    val panel: String
)

object PdfClinicalValues {
    // Prefer longer names first: "Serum Bilirubin (Total)" before "Bilirubin".
    private val names = listOf(
        "Serum Bilirubin \\(Total\\)", "Serum Bilirubin Conjugated",
        "Serum Alk\\.? Phosphatase", "Serum AST \\(SGOT\\)",
        "Serum ALT \\(SGPT\\)", "Serum Total Protein", "Serum Albumin",
        "Serum Creatinine", "Serum Urea", "Serum Sodium", "Serum Potassium",
        "Serum Chloride", "Serum Calcium", "Serum Magnesium",
        "Serum Total Cholesterol", "Serum HDL Cholesterol",
        "Serum LDL Cholesterol", "Serum VLDL Cholesterol",
        "Hemoglobin", "Haemoglobin", "PCV", "Red Cell Count",
        "MCV", "MCHC", "MCH", "RDW \\(SD\\)", "RDW \\(CV\\)",
        "Total Leucocyte Count", "Total Leukocyte Count", "Platelet Count",
        "Neutrophils", "Lymphocytes", "Monocytes", "Eosinophils",
        "Basophils", "Prothrombin Time\\(PT\\)",
        "PT INR", "Plasma APTT \\(Patient\\)", "Plasma APTT \\(Control\\)"
    ).map { Regex("""(?i)^\s*($it)\s*""") }

    private val number = Regex("""^([-+]?\d+(?:\.\d+)?)(?=\s|$)""")
    private val units = Regex("""(?i)^(x10\^\d+/\S+|(?:gm|mg|g|mmol)/dL|(?:mg|g)/L|U/L|fL|pg|gm/dL|vol%|Sec|%|mmol/L|x10\^3/\S+)\b""")
    private val panel = Regex("""(?i)^\s*(?:LIVER\s+FUNCTION|LIPID\s+PROFILE|CBC\s+WITH|DIFFERENTIAL\s+LEUCOCYTE|RENAL\s+FUNCTION)""")

    fun parse(text: String): List<PdfMeasuredValue> {
        val lines = text.replace("\r\n", "\n").lines()
        val found = mutableListOf<PdfMeasuredValue>()
        var currentPanel = ""
        for (i in lines.indices) {
            val line = lines[i].trim()
            if (panel.containsMatchIn(line) && line.length < 90) currentPanel = line.trim(':',' ')
            val pattern = names.firstOrNull { it.find(line)?.range?.first == 0 } ?: continue
            val hit = pattern.find(line) ?: continue
            val field = hit.groupValues[1]
            var remainder = line.substring(hit.range.last + 1).trim()
            // NIMS sometimes wraps a numeric row beneath its parameter.
            if (remainder.isBlank() && i + 1 < lines.size &&
                number.containsMatchIn(lines[i + 1].trim())) {
                remainder = lines[i + 1].trim()
            }
            val n = number.find(remainder) ?: continue
            val raw = n.groupValues[1]
            if (raw.toDoubleOrNull()?.isFinite() != true) continue
            val suffix = remainder.substring(n.range.last + 1).trim()
            val unit = units.find(suffix)?.takeIf { it.range.first == 0 }?.value.orEmpty()
            val reference = suffix.removePrefix(unit).trim().take(100)
            found += PdfMeasuredValue(field, raw, unit, reference, currentPanel)
        }
        // Prevent duplicated header/narrative reflections of the same entry.
        return found.distinctBy { listOf(it.parameter.lowercase(), it.value, it.unit, it.panel) }
    }
}
