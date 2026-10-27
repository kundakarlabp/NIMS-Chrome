package org.kundakarlab.nimsresults

import java.util.Locale

/**
 * Conservative presentation of HIS values. Never infer bacterial AST, target
 * detection, ABO grouping or a specimen's accession from an arbitrary code.
 */
object ClinicalPresentation {
    private fun norm(s: String) = s.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]+"), "")

    fun isTechnical(row: LabResult): Boolean {
        val p = row.parameter.trim().trimEnd(':').trim()
        return Regex(
            """(?i)^(?:sample\s*processed|test\s*done\s*by|processed\s*by|entered\s*by|verified\s*by|machine\s*(?:id|no)|lab\s*id|technician)$"""
        ).matches(p)
    }

    fun isOpaquePanelCode(row: LabResult): Boolean {
        val test = row.test
        val inPanel = Regex("""(?i)biofire|filmarray|ana\s*(?:profile|blot|panel)|blood\s*grouping|antibody\s*screening""")
            .containsMatchIn(test)
        return inPanel && Regex(
            """(?i)^(?:combo\d+|cb\d+|textbox\d*|textarea\d*|[12]|control|a1\s*cells|b\s*cells|anti-[ab]|rh|result)$"""
        ).matches(row.parameter.trim()) && !Regex(
            """(?i)\b(?:detected|not\s+detected|positive|negative|reactive|nonreactive)\b"""
        ).containsMatchIn(row.valueText)
    }

    fun isCulture(test: String): Boolean =
        Regex("""(?i)\bculture\b""").containsMatchIn(test)

    fun displayValue(row: LabResult): String = when {
        isTechnical(row) && row.valueText.trim() == "-1" -> "Source code -1 (not a test result)"
        isOpaquePanelCode(row) -> "Unmapped laboratory code: " + row.valueText
        row.valueText.trim() in setOf("--", "—") -> "Not reported in structured feed"
        else -> row.valueText
    }

    fun sentDate(rows: List<LabResult>): String = rows.firstNotNullOfOrNull {
        it.sentDate.takeIf(String::isNotBlank)
    }.orEmpty()

    fun reportedDate(rows: List<LabResult>): String = rows.firstNotNullOfOrNull {
        it.reportedDate.takeIf(String::isNotBlank)
    }.orEmpty()

    fun accession(rows: List<LabResult>): String = rows.map { it.labNumber.trim() }
        .filter { it.isNotBlank() }.distinct().singleOrNull().orEmpty()

    fun reportIdentity(rows: List<LabResult>, reports: List<ReportRef>): ReportRef? {
        val accession = accession(rows)
        if (accession.isNotBlank()) {
            val candidates = reports.filter { report ->
                report.url.isNotBlank() &&
                    report.labNumber.isNotBlank() &&
                    norm(report.labNumber) == norm(accession)
            }
            return candidates.singleOrNull()
        }
        // Without a verified accession, both titles AND explicitly supplied
        // specimen collection dates must match and the result be unique.
        val sent = sentDate(rows)
        val test = rows.firstOrNull()?.test.orEmpty()
        if (sent.isBlank() || test.isBlank()) return null
        val candidates = reports.filter { report ->
            report.url.isNotBlank() &&
                report.sentDate.isNotBlank() &&
                BulkParser.isoDate(report.sentDate) == BulkParser.isoDate(sent) &&
                norm(report.title) == norm(test)
        }
        return candidates.singleOrNull()
    }

    fun dateDescriptor(rows: List<LabResult>): String {
        val sent = sentDate(rows)
        val reported = reportedDate(rows)
        return listOfNotNull(
            sent.takeIf { it.isNotBlank() }?.let { "Sent: $it" },
            reported.takeIf { it.isNotBlank() }?.let { "Reported: $it" }
        ).joinToString(" · ")
    }
}

data class RadiologySections(
    val technique: String,
    val findings: String,
    val impression: String,
    val other: String
)

object RadiologyNarrative {
    // Parse only explicit headings, never synthesize diagnoses or conclusions.
    private val heading = Regex(
        """(?im)^\s*(TECHNIQUE|PROTOCOL|FINDINGS|OBSERVATIONS|IMPRESSION|CONCLUSION)\s*[:\-]?\s*"""
    )
    fun parse(text: String): RadiologySections {
        val matches = heading.findAll(text).toList()
        if (matches.isEmpty()) return RadiologySections("", "", "", text.trim())
        var technique = ""
        var findings = ""
        var impression = ""
        val other = mutableListOf<String>()
        if (matches.first().range.first > 0) {
            text.substring(0, matches.first().range.first).trim()
                .takeIf { it.isNotBlank() }?.let(other::add)
        }
        matches.forEachIndexed { i, match ->
            val value = text.substring(match.range.last + 1,
                matches.getOrNull(i + 1)?.range?.first ?: text.length).trim()
            when(match.groupValues[1].uppercase(Locale.US)) {
                "TECHNIQUE", "PROTOCOL" -> technique = listOf(technique, value)
                    .filter(String::isNotBlank).joinToString("\n")
                "FINDINGS", "OBSERVATIONS" -> findings = listOf(findings, value)
                    .filter(String::isNotBlank).joinToString("\n")
                "IMPRESSION", "CONCLUSION" -> impression = listOf(impression, value)
                    .filter(String::isNotBlank).joinToString("\n")
            }
        }
        return RadiologySections(technique, findings, impression, other.joinToString("\n"))
    }
}
