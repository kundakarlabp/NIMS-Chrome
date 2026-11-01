package org.kundakarlab.nimsresults

/**
 * On-demand device-local CSV export. Exports only clinically readable results
 * explicitly selected by the user. Unmapped HIS codes and administrative fields
 * are never silently promoted into clinical observations.
 *
 * Does not export patient identifiers, cookies, PDF URLs or raw source objects.
 */
object SelectedResultsCsv {
    data class Option(val id:String,val title:String)
    private fun id(row:LabResult):String =
        row.test.trim().lowercase()+"|"+row.parameter.trim().lowercase()
    fun options(rows:List<LabResult>):List<Option> = rows
        .distinctBy(::id)
        .map { row -> Option(id(row),
            row.test.trim()+" · "+ClinicalChronology.parameterLabel(row)) }
        .sortedBy { it.title.lowercase() }

    private fun quoted(raw:String):String =
        "\"" + raw.replace("\"","\"\"") + "\""

    fun render(rows:List<LabResult>,selected:Set<String>):String {
        val header=listOf("Result date","Investigation","Parameter","Result",
            "Unit","Reference range","Source reported at","Source collected at",
            "Lab/Study number","Sample number")
        val filtered=rows.filter { id(it) in selected }
        val lines=filtered.sortedWith(compareByDescending<LabResult> {
            RecentReportDates.parse(it.date)
        }.thenBy { it.test }.thenBy { it.parameter }).map { row ->
            listOf(row.date,row.test,ClinicalChronology.parameterLabel(row),
                row.valueText,row.unit,row.refRange,row.reportedDate,
                row.collectionDateTime,row.labNumber,row.sampleNumber)
                .joinToString(",") { quoted(it) }
        }
        return (listOf(header.joinToString(",") { quoted(it) })+lines)
            .joinToString("\r\n",postfix="\r\n")
    }
}
