package org.kundakarlab.nimsresults

import java.time.LocalDate

/** Date-first presentation of *all* structured studies. Reported time takes
 * precedence only if explicitly supplied; source column dates are never
 * mislabeled as specimen collection or validation timestamps.
 * Raw rows remain in ClinicalBundle, including unmapped/unusable values. */
object ClinicalChronology {
    data class Day(val date: LocalDate, val studies: List<Study>)
    data class Study(
        val key: String, val title: String, val date: LocalDate,
        val rows: List<LabResult>, val visible: List<LabResult>,
        val reportedIsExplicit: Boolean
    )
    fun days(results: List<LabResult>, query: String = "",
             section: String = "All", days: Long? = null): List<Day> {
        val studies = results.groupBy {
            listOf(it.test.trim(), it.date.trim(), it.labNumber.trim(),
                it.sampleNumber.trim(), it.reportStage.trim(), it.sourceOrderId.trim())
        }.map { (identity, source) ->
            val (date, provenance) = RecentReportDates.dateFor(source)
            val display=ClinicalReportRenderer.preferredRows(source)
            Study(identity.joinToString("|"), label(identity[0]), date ?: LocalDate.MIN,
                source, display, provenance == "Reported")
        }.sortedWith(compareByDescending<Study> { it.date }.thenBy { it.title })
        val latest=studies.firstOrNull { it.date != LocalDate.MIN }?.date
        val filtered=studies.filter { record ->
            val test=record.rows.firstOrNull()?.test.orEmpty()
            val type=ResultSections.of(test)
            val inSection = when(section) {
                "Microbiology" -> type == ResultSection.MICROBIOLOGY
                "Metabolic" -> record.rows.any { LabPanelMatrix.panel(it) ==
                    LabPanelMatrix.Panel.METABOLIC }
                "Pathology" -> type == ResultSection.PATHOLOGY
                "Blood bank" -> Regex("(?i)blood\\s*group|blood\\s*bank|antibody\\s*screen|crossmatch")
                    .containsMatchIn(test)
                else -> true
            }
            val timeOk=days==null || (latest!=null &&
                record.date!=LocalDate.MIN &&
                java.time.temporal.ChronoUnit.DAYS.between(record.date,latest) in 0 until days)
            val matches=query.isBlank() || (record.title + " " +
                record.rows.joinToString(" ") { it.parameter+" "+it.valueText+" "+
                    it.date+" "+it.specimenType+" "+it.labNumber })
                .contains(query,ignoreCase=true)
            inSection && timeOk && matches
        }
        return filtered.groupBy { it.date }.entries.sortedByDescending { it.key }
            .map { Day(it.key,it.value) }
    }

    fun label(original: String): String {
        val name=original.trim()
        return if (name.equals("BU/SC/SE",ignoreCase=true))
            "Renal / electrolytes (BU/SC/SE)"
        else name.ifBlank { "Investigation" }
    }

    fun parameterLabel(row: LabResult): String {
        val name=row.parameter.ifBlank { row.test }
        val key=name.lowercase().replace(Regex("[^a-z0-9]"), "")
        return when {
            key.startsWith("prothrombintimeptcontrol") || key == "ptcontrol" ->
                "PT (control)"
            key.startsWith("prothrombintimeptpatient") || key == "ptpatient" ->
                "PT (patient)"
            key.startsWith("ptinr") || key == "inr" -> "INR"
            key.startsWith("apttpatient") -> "aPTT (patient)"
            key.startsWith("apttcontrol") -> "aPTT (control)"
            key.startsWith("hemoglobin") -> "Hb"
            key.startsWith("plateletcount") -> "Platelets"
            key.startsWith("pcv") -> "PCV"
            key.startsWith("redbloodcell") -> "RBC"
            else -> LabPanelMatrix.shortLabel(name)
        }
    }
    /** Display-only: remove routine sampling instructions that are routinely
     * appended by the LIS to negative cultures. Entire original stays in Raw
     * and the source PDF, never changed in the source data. */
    fun clinicalText(value: String): String {
        val cut=Regex("""(?i)\bINSTRUCTIONS\s+FOR\s+(?:BLOOD\s+)?CULTURE\s+COLLECTION\b""")
            .find(value)?.range?.first ?: value.length
        return value.take(cut).trim().replace(Regex("""\s+""")," ")
    }
    fun preview(study: Study): String {
        val visible=study.visible
        if (visible.isEmpty()) return "See source report"
        val meaningful=visible.filter { it.valueText.isNotBlank() &&
            !it.valueText.contains("not reported in structured",true) }
        if (meaningful.isEmpty()) return "See source report"
        val ranked=meaningful.sortedWith(compareBy<LabResult> { row ->
            val name=parameterLabel(row)
            when(name) { "Hb" -> 0; "Creatinine" -> 1; "Urea" -> 2; "TLC" -> 3
                "Platelets" -> 4; "Na" -> 5; "K" -> 6; else -> 12 }
        }.thenBy { it.parameter })
        val show=if (ranked.size==1) ranked else ranked.take(3)
        return show.joinToString("  ·  ") { row ->
            val valText=clinicalText(row.valueText)
            (if (ranked.size==1 && (row.parameter.equals("result",true) ||
                     valText.length>38)) "" else parameterLabel(row)+": ") +
                valText.take(105).ifBlank { "—" } + if (row.unit.isBlank()) ""
                    else " "+row.unit
        }.take(260)
    }
}
