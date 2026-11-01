package org.kundakarlab.nimsresults

/**
 * Only an explicitly named DAT/IAT result from the same structured test.
 * Numbers and control IDs are retained as opaque HIS source fields.
 * This is deliberately not a lookup table for local LIS combo selections.
 */
object ClinicalSourceQualitative {
    private val conclusions = Regex(
        """(?i)^(?:negative|positive|weak(?:ly)?[\s-]*positive|non[\s-]*reactive|reactive)$"""
    )
    private val named = Regex(
        """(?i)^(?:result|observation|conclusion|interpretation|(?:direct|indirect)[\s-]*(?:coombs|antiglobulin)(?:[\s-]*test)?|(?:DAT|IAT)(?:[\s-]*result)?)$"""
    )
    fun coombs(rows: List<LabResult>): String? {
        if (rows.isEmpty()) return null
        val test=rows.first().test
        if (!Regex("""(?i)(?:\b(?:direct|indirect)\s+(?:coombs|antiglobulin)|\bDAT\b|\bIAT\b)""")
                .containsMatchIn(test)) return null
        val results=rows.filter { row ->
            row.test==test && named.matches(row.parameter.trim()) &&
                conclusions.matches(row.valueText.trim())
        }.map {
            it.valueText.trim().replace(Regex("""\s+""")," ").lowercase()
        }.distinct()
        return results.singleOrNull()?.replaceFirstChar { it.uppercase() }
    }
}
