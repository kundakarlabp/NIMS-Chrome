package org.kundakarlab.nimsresults

import java.time.LocalDate

/** One chronological feed for structured investigations AND their source PDFs.
 * A physical report is represented once, even when many studies share it.
 */
data class UnifiedResultEntry(
    val key: String,
    val title: String,
    val date: LocalDate,
    val rows: List<LabResult> = emptyList(),
    val report: ReportRef? = null
)

object UnifiedResultsFeed {
    private val bloodBank = Regex(
        """(?i)blood\s*group|antibody\s*screen|transfusion|immunoh[ae]matology|blood\s*bank|cross\s*match|\bbgt\b"""
    )

    fun entries(
        bundle: ClinicalBundle,
        query: String = "",
        category: String = "All",
        days: Long? = null
    ): List<UnifiedResultEntry> {
        val structured = bundle.results
            .groupBy { Triple(it.test, it.date, it.labNumber.trim()) }
            .map { (identity, rows) ->
                val resultDate = RecentReportDates.parse(
                    ClinicalPresentation.reportedDate(rows)
                ) ?: RecentReportDates.parse(identity.second) ?: LocalDate.MIN
                UnifiedResultEntry(
                    key = "study|" + identity.first + "|" + identity.second + "|" +
                        identity.third,
                    title = identity.first,
                    date = resultDate,
                    rows = rows
                )
            }
        val documents = bundle.reports
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url }
            .map { report ->
                UnifiedResultEntry(
                    key = "pdf|" + report.url,
                    title = ReportTitleCache.get(report.url) ?: report.title,
                    date = RecentReportDates.dateFor(report).first ?: LocalDate.MIN,
                    report = report
                )
            }
        val all = structured + documents
        val latest = all.map { it.date }.filter { it != LocalDate.MIN }.maxOrNull()
        return all.filter { entry ->
            val source = entry.report
            val rows = entry.rows
            val name = if (source != null) {
                listOf(entry.title, source.title, source.department,
                    source.date, source.sentDate, source.reportedDate,
                    source.labNumber, ReportTitleCache.accessions(source.url).joinToString(" "))
                    .joinToString(" ")
            } else {
                listOf(entry.title, rows.joinToString(" ") {
                    it.parameter + " " + it.valueText + " " + it.labNumber +
                        " " + it.date + " " + it.sentDate + " " + it.reportedDate
                }).joinToString(" ")
            }
            val section = ResultSections.of(entry.title + " " +
                source?.department.orEmpty())
            val matchesCategory = when(category) {
                "All", "Raw" -> true
                "Microbiology" -> section == ResultSection.MICROBIOLOGY ||
                    (source != null && ClinicalReportRenderer.isMicrobiologyInvestigation(
                        name)) || source?.department?.contains("microbiology", true) == true
                "Metabolic" -> section == ResultSection.METABOLIC
                "Pathology" -> section == ResultSection.PATHOLOGY
                "Labs" -> section != ResultSection.MICROBIOLOGY
                "Blood bank" -> bloodBank.containsMatchIn(name)
                "Flagged" -> rows.any {
                    val f = it.abnormal.lowercase()
                    f == "h" || f == "l" || "high" in f || "low" in f || "critical" in f
                }
                else -> true
            }
            val matchesDate = days == null || latest == null ||
                entry.date != LocalDate.MIN &&
                java.time.temporal.ChronoUnit.DAYS.between(entry.date, latest) in 0..days
            matchesCategory && matchesDate &&
                (query.isBlank() || name.contains(query, ignoreCase = true))
        }.sortedWith(compareByDescending<UnifiedResultEntry> { it.date }
            .thenBy { if (it.report != null) 1 else 0 }
            .thenBy { it.title })
    }
}
