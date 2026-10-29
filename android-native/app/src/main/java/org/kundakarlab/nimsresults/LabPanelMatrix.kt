package org.kundakarlab.nimsresults

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Presentation-only panels. No value, specimen or source event is discarded. */
object LabPanelMatrix {
    enum class Panel(val label: String) {
        METABOLIC("Metabolic · Renal → LFT → Electrolytes"),
        HEMOGRAM("Hematology · Hb → TLC → DLC → Platelets → ESR → Smear"),
        OTHERS("Other repeated numeric results")
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
        // ESR can be ordered separately from CBC; peripheral smear stays with
        // hematology. Never classify a culture by a phrase in the narrative.
        val cleanedTest=clean(test)
        return when {
            hemogram.containsMatchIn(test) || cleanedTest == "esr" ||
                cleanedTest.startsWith("erythrocytesedimentationrate") ||
                cleanedTest.startsWith("sedimentationrate") -> Panel.HEMOGRAM
            metabolic.containsMatchIn(test) -> Panel.METABOLIC
            else -> Panel.OTHERS
        }
    }

    /** Stable clinical order, unlike alphabetical source columns.
     * Additional tests and CBC indices are retained after priority rows. */
    fun clinicalPriority(panel: Panel, name: String): Int {
        val v=clean(name)
        return when (panel) {
            Panel.METABOLIC -> when {
                v.contains("creatinine") && !v.contains("urine") -> 0
                v.contains("urea") || v == "bun" || v.contains("bloodureanitrogen") -> 1
                v.contains("bilirubin") -> 10
                v.contains("ast") || v.contains("sgot") -> 11
                v.contains("alt") || v.contains("sgpt") -> 12
                v.contains("alkalinephosphatase") || v == "alp" -> 13
                v.contains("albumin") -> 14
                v.contains("totalprotein") || v.contains("globulin") -> 15
                v.contains("sodium") -> 20
                v.contains("potassium") -> 21
                v.contains("chloride") -> 22
                v.contains("bicarbonate") -> 23
                else -> 40
            }
            Panel.HEMOGRAM -> when {
                v == "hb" || v.contains("hemoglobin") || v.contains("haemoglobin") -> 0
                v.contains("totalleuco") || v.contains("totalleuko") ||
                    v.contains("totalwhite") || v == "tlc" ||
                    v.contains("whitebloodcellcount") || v == "wbc" -> 1
                v.contains("neutrophil") && !v.contains("absolute") -> 2
                v.contains("lymphocyte") && !v.contains("absolute") -> 3
                v.contains("monocyte") && !v.contains("absolute") -> 4
                v.contains("eosinophil") && !v.contains("absolute") -> 5
                v.contains("basophil") && !v.contains("absolute") -> 6
                v.contains("bandform") || v.contains("immaturegranulocyte") -> 7
                v.contains("platelet") -> 10
                v == "esr" || v.contains("erythrocytesedimentationrate") -> 11
                v.contains("smear") || v == "rbcs" || v == "wbcs" ||
                    v.contains("peripheralbloodpicture") -> 12
                else -> 30
            }
            Panel.OTHERS -> 0
        }
    }

    private fun repeatedNumeric(values: List<LabResult>): Boolean =
        values.count { it.numericValue?.isFinite() == true &&
            it.valueText.length <= 40 && RecentReportDates.parse(it.date) != null } >= 2 &&
        values.filter { it.numericValue?.isFinite() == true &&
            it.valueText.length <= 40 }
            .mapNotNull { RecentReportDates.parse(it.date) }.distinct().size >= 2

    private val missingValues = setOf("", "-", "--", "na", "n/a", "nil",
        "not reported", "not reported in structured feed", "not available",
        "not done", "no result", "null", "none")

    /** Clinical sheets contain measurements, not raw administrative placeholders.
     * All excluded observations remain in All Results / Raw. */
    fun clinicallyUseful(row: LabResult, group: Panel = panel(row)): Boolean {
        val value = row.valueText.trim()
        if (value.lowercase(Locale.US) in missingValues ||
            value.contains("not reported in structured", ignoreCase = true) ||
            ClinicalPresentation.isTechnical(row) ||
            ClinicalReportRenderer.isUninterpretedCode(row)) return false
        if (row.numericValue?.isFinite() == true && value.length <= 40) return true
        if (group != Panel.HEMOGRAM || value.length > 180) return false
        val parameter = clean(row.parameter)
        val report = clean(row.test)
        val smear = report.contains("smear") || parameter.contains("smear") ||
            parameter in setOf("rbcs","wbcs","platelets","rbcsmorphology",
                "wbcmorphology","plateletmorphology")
        return smear && value.length in 2..180 &&
            !value.equals("others",true) && !value.equals("result",true)
    }

    /** Clinical mode hides singleton/narrative Others without changing source records.
     * Raw mode uses the original comprehensive model for audit/research. */
    fun build(rows: List<LabResult>, clinical: Boolean = false): List<Table> {
        // group only the presentation. Raw observations remain untouched.
        val separated=rows.groupBy(::panel)
        return Panel.entries.mapNotNull { panel ->
            val source=separated[panel].orEmpty()
            if(source.isEmpty()) return@mapNotNull null
            val presentation = if (clinical)
                source.filter { clinicallyUseful(it, panel) } else source
            if (presentation.isEmpty()) return@mapNotNull null
            val dates=presentation.map { canonicalDate(it.date) }
                .filter { RecentReportDates.parse(it)!=null }
                .distinct().sortedDescending()
            val keys=linkedMapOf<String, MutableList<LabResult>>()
            val labels=linkedMapOf<String,String>()
            val units=linkedMapOf<String,String>()
            for (row in presentation) {
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
            val params=keys.mapNotNull { (identity,values) ->
                if (clinical && panel == Panel.OTHERS && !repeatedNumeric(values))
                    return@mapNotNull null
                Parameter(identity,labels[identity].orEmpty(),units[identity].orEmpty(),
                    values.groupBy { canonicalDate(it.date) })
            }.sortedWith(compareBy<Parameter> {
                clinicalPriority(panel,it.title)
            }.thenBy { it.title.lowercase(Locale.US) }.thenBy { it.unit })
            if (clinical && params.isEmpty()) return@mapNotNull null
            // Clinical Others only has dates with repeated numeric observations.
            val visibleDates = if (clinical && panel == Panel.OTHERS)
                params.flatMap { it.byDate.keys }.distinct()
                    .filter { RecentReportDates.parse(it) != null }.sortedDescending()
            else dates
            val visibleObservations = if (clinical)
                params.sumOf { p -> p.byDate.values.sumOf { it.size } }
            else source.size
            val visibleUndated = if (clinical)
                params.sumOf { p -> p.byDate.filterKeys {
                    RecentReportDates.parse(it)==null
                }.values.sumOf { it.size } }
            else source.count { RecentReportDates.parse(it.date)==null }
            Table(panel,visibleDates,params,visibleObservations,visibleUndated)
        }
    }
}
