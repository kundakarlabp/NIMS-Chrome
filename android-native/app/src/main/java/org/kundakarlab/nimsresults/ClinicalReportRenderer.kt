package org.kundakarlab.nimsresults

import java.util.Locale

/**
 * Rendering policy, not a diagnostic interpreter. The original HIS result
 * text and source PDF remain authoritative for clinical decisions.
 */
enum class ClinicalLayout { SERIES, QUALITATIVE, NARRATIVE, SOURCE_CODED }

data class SourceSection(val heading: String, val content: String)

object ClinicalReportRenderer {
    private val quantitativeTest = Regex(
        """(?i)\b(cbc|complete\s+blood|hemogram|haemogram|electrolytes|urea|creatinine|renal\s+function|liver\s+function|lipid\s+profile|thyroid\s+profile|hba1c|glycated|procalcitonin|ferritin|coagulation\s+profile|arterial\s+blood\s+gas|blood\s+gas)\b"""
    )
    private val narrativeTest = Regex(
        """(?i)(?:\bpet[\s-]*ct\b|\b(?:mri|m\.r\.i\.)\b|\b(?:ct|hrct)\s+(?:scan|brain|chest|head|abdomen|spine)\b|\b(?:usg|ultrasound|echocardiography)\b|\b(?:radiology|histopathology|cytology|bone\s+marrow|brain\s+p\s*&\s*c)\b)"""
    )
    private val qualitativeTest = Regex(
        """(?i)smear|gram(?:s)?\s*stain|calcofluor|fungal\s*koh|koh\s*preparation|hiv|hbs\s*ag|hcv|elisa|elfa|ns1|dengue|widal|serolog|agglutination|malaria|urine\s+examination|urine\s+routine|biofire|filmarray|ana\s*(?:profile|blot|panel)|blood\s+group|antibody\s+screen"""
    )
    private val coded = Regex(
        """(?i)^(?:combo\d+|com\d+|cb\d+|textbox\d*|textarea\d*|[12]\.?\s*antibody\s*screening|\/[hl]pf\s*\(investigation\)|negative\s*\(investigation\)|result\s*code)$"""
    )
    private val unknownValues = setOf("", "--", "—", "null", "n/a")

    fun isMicrobiologyInvestigation(test: String): Boolean =
        Regex(
            """(?i)\b(?:culture|sensitivity|susceptibility|genexpert|gene\s*xpert|cbnaat|afb\s*smear|mycobacterial\s*pcr|blood\s*culture)\b"""
        ).containsMatchIn(test)

    fun isPlaceholder(row: LabResult): Boolean =
        row.valueText.trim().lowercase(Locale.US) in unknownValues

    fun isUninterpretedCode(row: LabResult): Boolean {
        val name = row.parameter.trim()
        val code = row.valueText.trim()
        if (ClinicalPresentation.isTechnical(row) || ClinicalPresentation.isOpaquePanelCode(row)) return true
        if (coded.matches(name) && !isClinicalNarrative(code)) return true
        // -1 is commonly an administrative/empty marker, not a true negative.
        if (code == "-1" && row.refRange.isBlank() &&
            (row.unit.isBlank() || qualitativeTest.containsMatchIn(row.test))) return true
        if (code.matches(Regex("[1-9]")) &&
            qualitativeTest.containsMatchIn(row.test) &&
            (name.contains("Investigation", true) || name.matches(Regex("""(?i)negative\(result\)|ketones\(result\)|rh|result""")))) return true
        return false
    }

    private fun isClinicalNarrative(value: String): Boolean =
        value.length >= 8 || Regex("""(?i)\b(?:detected|not detected|positive|negative|reactive|nonreactive|nil)\b""")
            .containsMatchIn(value)

    fun layout(test: String, rows: List<LabResult>): ClinicalLayout {
        if (narrativeTest.containsMatchIn(test)) return ClinicalLayout.NARRATIVE
        if (Regex("""(?i)biofire|filmarray|ana\s*(?:profile|blot|panel)|blood\s+group|antibody\s+screen""")
                .containsMatchIn(test)) return ClinicalLayout.SOURCE_CODED
        if (qualitativeTest.containsMatchIn(test)) return ClinicalLayout.QUALITATIVE
        if (quantitativeTest.containsMatchIn(test)) return ClinicalLayout.SERIES
        val clinical = rows.filterNot(::isUninterpretedCode).filterNot(::isPlaceholder)
        if (clinical.any { it.valueText.length > 160 }) return ClinicalLayout.NARRATIVE
        val numbers = clinical.count { it.numericValue?.isFinite() == true }
        return if (clinical.isNotEmpty() && numbers * 2 >= clinical.size) ClinicalLayout.SERIES
            else ClinicalLayout.QUALITATIVE
    }

    fun clinicalRows(rows: List<LabResult>): List<LabResult> =
        rows.filterNot(::isUninterpretedCode).filterNot(::isPlaceholder)

    fun preferredRows(rows: List<LabResult>): List<LabResult> {
        // Same observation is often repeated by the HIS as "Result" and
        // "textarea". Keep the more descriptive label, not two copies.
        return clinicalRows(rows).sortedWith(compareBy<LabResult> {
            if (it.parameter.equals("textarea", true) ||
                it.parameter.equals("Result", true)) 1 else 0
        }.thenByDescending { it.valueText.length }).distinctBy {
            it.date + "|" + it.valueText.trim().lowercase(Locale.US)
                .replace(Regex("""\s+"""), " ")
        }
    }

    fun sourceSections(input: String): List<SourceSection> {
        val source = input.trim()
        if (source.isBlank()) return emptyList()
        // Allow NIMS's PDF-to-string path, where headings are embedded in a
        // single long line. Split only on explicit headings with colons.
        val heading = Regex(
            """(?i)\b(clinical\s+history|technique|protocol|findings|observations|impression|conclusion|advice|recommendations?|comments?)\s*:\s*"""
        )
        val matches = heading.findAll(source).toList()
        if (matches.isEmpty()) return listOf(SourceSection("Source result", source))
        val sections = mutableListOf<SourceSection>()
        source.substring(0, matches.first().range.first).trim()
            .takeIf(String::isNotBlank)?.let { sections += SourceSection("Source details", it) }
        matches.forEachIndexed { i, m ->
            val end = matches.getOrNull(i + 1)?.range?.first ?: source.length
            val value = source.substring(m.range.last + 1, end).trim()
            if (value.isNotBlank()) sections += SourceSection(m.groupValues[1], value)
        }
        return sections.ifEmpty { listOf(SourceSection("Source result", source)) }
    }

    fun informativeDate(row: LabResult): String = row.date
}
