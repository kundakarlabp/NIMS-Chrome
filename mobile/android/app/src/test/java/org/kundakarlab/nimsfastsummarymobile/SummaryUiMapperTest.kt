package org.kundakarlab.nimsfastsummarymobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.ui.formatters.ClinicalSummaryFormatter
import org.kundakarlab.nimsfastsummarymobile.ui.mappers.SummaryJsonMapper
import org.kundakarlab.nimsfastsummarymobile.ui.models.Abnormality

class SummaryUiMapperTest {
    @Test
    fun mapsNormalLabsCulturesAndErrors() {
        val summary = JSONObject()
            .put(
                "source_reports",
                JSONArray()
                    .put(JSONObject().put("date_sent", "19-May-2026").put("report_name", "CBC").put("type", "cbc").put("status", "parsed"))
                    .put(JSONObject().put("date_sent", "18-May-2026").put("report_name", "Culture").put("type", "culture").put("status", "error").put("notes", "parse failed"))
            )
            .put(
                "lab_trend_table",
                JSONObject()
                    .put("columns", JSONArray().put("19-May-2026").put("18-May-2026"))
                    .put(
                        "rows",
                        JSONArray().put(
                            JSONObject()
                                .put("parameter", "Hb")
                                .put("values", JSONArray().put("8.9 g/dL [low]").put("9.4 g/dL"))
                                .put("trend", "falling")
                        )
                    )
            )
            .put(
                "culture_table",
                JSONArray().put(
                    JSONObject()
                        .put("collection_date", "19-May-2026")
                        .put("site_specimen", "Blood")
                        .put("result", "positive")
                        .put("organism", "Klebsiella pneumoniae")
                        .put("sensitivity_summary", "Sensitive: Meropenem")
                )
            )
            .put("interpretation", JSONArray().put("Hb trend is falling."))

        val ui = SummaryJsonMapper.parseSummaryJsonToUiSummary(summary, "Review antibiotic dose.")

        assertEquals(2, ui.sourceReports.size)
        assertEquals(1, ui.failedReportCount)
        assertEquals("Hb", ui.labTrends.first().parameter)
        assertEquals(Abnormality.LOW, ui.labTrends.first().abnormality)
        assertEquals("Klebsiella pneumoniae", ui.cultures.first().organism)
        assertEquals("Review antibiotic dose.", ui.editableNote)
    }

    @Test
    fun mapperSkipsMalformedRowsWithoutCrashing() {
        val summary = JSONObject()
            .put("source_reports", JSONArray().put("bad").put(JSONObject().put("report_name", "CBC")))
            .put("lab_trend_table", JSONObject().put("columns", JSONArray().put("Today")).put("rows", JSONArray().put("bad")))
            .put("culture_table", JSONArray().put("bad"))

        val ui = SummaryJsonMapper.parseSummaryJsonToUiSummary(summary)

        assertEquals(1, ui.sourceReports.size)
        assertTrue(ui.labTrends.isEmpty())
        assertTrue(ui.cultures.isEmpty())
    }

    @Test
    fun mapperUsesLatestToOldestColumnsAndPadsShortRows() {
        val summary = JSONObject()
            .put("lab_trend_table", JSONObject()
                .put("columns", JSONArray().put("02-06-2026").put("01-06-2026").put("31-05-2026"))
                .put("rows", JSONArray().put(JSONObject().put("parameter", "Creatinine").put("values", JSONArray().put("1.4 mg/dL").put("").put("1.0 mg/dL")))
                    .put(JSONObject().put("parameter", "Hb").put("values", JSONArray().put("11 g/dL")))
                    .put(JSONObject().put("parameter", "Bad").put("values", JSONArray().put("a").put("b").put("c").put("d")))))
        val ui = SummaryJsonMapper.parseSummaryJsonToUiSummary(summary)
        val creat = ui.labTrends.first { it.parameter == "Creatinine" }
        assertEquals("1.4 mg/dL", creat.latestValue)
        assertEquals("02-06-2026", creat.latestDate)
        assertEquals("1.0 mg/dL", creat.previousValue)
        assertEquals("31-05-2026", creat.previousDate)
        assertEquals(2, ui.labTrends.size)
    }

    @Test
    fun formatterIncludesClinicalSectionsAndDisclaimer() {
        val ui = SummaryJsonMapper.parseSummaryJsonToUiSummary(
            JSONObject()
                .put("source_reports", JSONArray().put(JSONObject().put("date_sent", "19-May-2026").put("report_name", "CBC")))
                .put("interpretation", JSONArray().put("Structured tables generated locally.")),
            "Physician note"
        )

        val text = ClinicalSummaryFormatter.cleanText(ui)

        assertTrue(text.contains("NIMS Fast Summary"))
        assertTrue(text.contains("Key labs"))
        assertTrue(text.contains("Cultures"))
        assertTrue(text.contains("Physician note"))
        assertTrue(text.contains("Verify with source NIMS reports"))
    }
}
