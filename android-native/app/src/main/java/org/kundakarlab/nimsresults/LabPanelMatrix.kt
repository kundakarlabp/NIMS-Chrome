package org.kundakarlab.nimsresults

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Presentation-only panels. No value, specimen or source event is discarded. */
object LabPanelMatrix {
    enum class Panel(val label: String) {
        METABOLIC("Metabolic · Renal, Electrolytes, LFT"),
        HEMOGRAM("Hemogram · CBP, CBC and Differential"),
        OTHERS("Other laboratory investigations")
    }
    data class Parameter(
        val key: String, val title: String, val unit: String,
        val byDate: Map<String, List<LabResult>>
    )
    data class Table(
        val panel: Panel, val dates: List<String>, val parameters: List<Parameter>,
        val observationCount: Int, val undatedCount: Int
    )
    private val formatter=DateTimeFormatter.ofPattern("dd-MM-uuuu",Locale.ENGLISH)
    fun displayDate(raw: String): String =
        RecentReportDates.parse(raw)?.format(formatter) ?: raw

    fun canonicalDate(raw: String): String =
        RecentReportDates.parse(raw)?.toString() ?: raw.trim()
    /** Display verified source dates as DD-MM-YYYY, preserving any clock time.
     * A timestamp's provenance remains that of its source field. */
    fun displayDateTime(raw: String): String {
        val date = RecentReportDates.parse(raw) ?: return raw
        val clock = Regex("""\b\d{1,2}:\d{2}(?::\d{2})?\b""")
            .find(raw)?.value
        return date.format(formatter) + (clock?.let { " " + it } ?: "")
    }


    private fun clean(value:String)=value.lowercase(Locale.US)
        .replace(Regex("[^a-z0-9]"),"")

    private val hemogram=Regex(
        """(?i)(?:\b(?:cbc|cbp|hemogram|haemogram|hemogramme|hemogramme|complete\s*blood\s*(?:count|picture)|peripheral\s*(?:blood\s*)?smear|blood\s*picture|differential\s*(?:leuco|leuko|white\s*blood)|t(?:otal)?\s*l(?:eucocyte|eukocyte)\s*count)\b)"""
    )
    private val metabolic=Regex(
        """(?i)\b(?:creatinine|urea|bun|renal\s*function|kidney\s*function|electrolytes?|sodium|potassium|chloride|hepatic\s*function|liver\s*function|lft|rft|kft|bilirubin|albumin|alkaline\s*phosphatase|sgot|sgpt|transaminase|total\s*protein|glucose|blood\s*sugar|glycosylated|glycated|hba\s*1c|hba1c)\b"""
    )
    fun panel(row: LabResult): Panel {
        val test=row.test
        // Never classify from narratives. Panels are based on report/test titles.
        return when {
            hemogram.containsMatchIn(test) -> Panel.HEMOGRAM
            metabolic.containsMatchIn(test) -> Panel.METABOLIC
            else -> Panel.OTHERS
        }
    }

    fun build(rows: List<LabResult>): List<Table> {
        // group only the presentation. Raw observations remain untouched.
        val separated=rows.groupBy(::panel)
        return Panel.entries.mapNotNull { panel ->
            val source=separated[panel].orEmpty()
            if(source.isEmpty()) return@mapNotNull null
            val dates=source.map { canonicalDate(it.date) }
                .filter { RecentReportDates.parse(it)!=null }
                .distinct().sortedDescending()
            val keys=linkedMapOf<String, MutableList<LabResult>>()
            val labels=linkedMapOf<String,String>()
            val units=linkedMapOf<String,String>()
            for (row in source) {
                val name=row.parameter.ifBlank { row.test }.trim()
                // A generic 'Result' under different named tests is not the
                // same measurement, especially in the Other panel.
                val generic=clean(name) in setOf("result","value","observation")
                val identity = (if(generic) clean(row.test)+"|" else "")+
                    clean(name)+"|"+clean(row.unit)
                keys.getOrPut(identity) { mutableListOf() }.add(row)
                labels.putIfAbsent(identity,
                    if(generic) row.test.trim() else name)
                units.putIfAbsent(identity,row.unit)
            }
            val params=keys.map { (identity,values) ->
                Parameter(identity,labels[identity].orEmpty(),units[identity].orEmpty(),
                    values.groupBy { canonicalDate(it.date) })
            }.sortedWith(compareBy<Parameter> { it.title.lowercase(Locale.US) }
                .thenBy { it.unit })
            Table(panel,dates,params,source.size,
                source.count { RecentReportDates.parse(it.date)==null })
        }
    }
}
