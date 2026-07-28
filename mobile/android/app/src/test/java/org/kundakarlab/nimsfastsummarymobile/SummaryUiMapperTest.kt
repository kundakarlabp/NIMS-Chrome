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
                    .put(JSONObject().put("report_id", "report_key:cbc").put("date_sent", "19-May-2026").put("report_name", "CBC").put("type", "cbc").put("status", "parsed"))
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
        assertEquals("report_key:cbc", ui.sourceReports.first().sourceKey)
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
    fun mapperRetainsExpandableStructuredReportResults() {
        val source = JSONObject()
            .put("report_name", "Activated Partial Thromboplastin Time")
            .put("date_sent", "28-Aug-2025")
            .put("status", "parsed")
            .put("results", JSONArray().put(
                JSONObject()
                    .put("name", "aPTT")
                    .put("value", 27.4)
                    .put("unit", "Sec")
                    .put("reference_range", "25-39")
                    .put("abnormality", "normal")
                    .put("confidence", "high")
            ))

        val report = SummaryJsonMapper.parseSummaryJsonToUiSummary(
            JSONObject().put("source_reports", JSONArray().put(source))
        ).sourceReports.single()

        assertEquals("aPTT", report.results.single().name)
        assertEquals("27.4", report.results.single().value)
        assertEquals(Abnormality.NORMAL, report.results.single().abnormality)
    }

    @Test
    fun mapperExcludesShorterValuesArrayThanColumns() {
        val summary = summaryWithTrendRows(
            JSONObject().put("parameter", "Hb").put("values", JSONArray().put("11 g/dL"))
        )

        val ui = SummaryJsonMapper.parseSummaryJsonToUiSummary(summary)

        assertTrue(ui.labTrends.isEmpty())
    }

    @Test
    fun mapperExcludesLongerValuesArrayThanColumns() {
        val summary = summaryWithTrendRows(
            JSONObject().put("parameter", "Hb").put("values", JSONArray().put("a").put("b").put("c").put("d"))
        )

        val ui = SummaryJsonMapper.parseSummaryJsonToUiSummary(summary)

        assertTrue(ui.labTrends.isEmpty())
    }

    @Test
    fun mapperAcceptsEqualLengthValuesWithBlankMiddleDate() {
        val summary = summaryWithTrendRows(
            JSONObject().put("parameter", "Creatinine").put("values", JSONArray().put("1.4 mg/dL").put("").put("1.0 mg/dL"))
        )

        val ui = SummaryJsonMapper.parseSummaryJsonToUiSummary(summary)
        val creat = ui.labTrends.single()

        assertEquals("1.4 mg/dL", creat.latestValue)
        assertEquals("02-06-2026", creat.latestDate)
        assertEquals("1.0 mg/dL", creat.previousValue)
        assertEquals("31-05-2026", creat.previousDate)
    }

    @Test
    fun mapperUsesLatestToOldestColumnsForLatestAndPreviousResults() {
        val summary = summaryWithTrendRows(
            JSONObject().put("parameter", "Creatinine").put("values", JSONArray().put("1.4 mg/dL").put("1.2 mg/dL").put("1.0 mg/dL"))
        )

        val ui = SummaryJsonMapper.parseSummaryJsonToUiSummary(summary)
        val creat = ui.labTrends.single()

        assertEquals("1.4 mg/dL", creat.latestValue)
        assertEquals("02-06-2026", creat.latestDate)
        assertEquals("1.2 mg/dL", creat.previousValue)
        assertEquals("01-06-2026", creat.previousDate)
    }

    @Test
    fun mapperConsolidatesPreliminaryAndFinalCultureIntoPositiveEpisode() {
        val cultures = JSONArray()
            .put(JSONObject()
                .put("report_id", "report_key:preliminary")
                .put("lab_study_number", "B100")
                .put("collection_date", "01-Jun-2026")
                .put("specimen", "Blood")
                .put("bottle_number", 1)
                .put("report_stage", "48-hour preliminary")
                .put("status", "negative"))
            .put(JSONObject()
                .put("lab_study_number", "B100")
                .put("collection_date", "01-Jun-2026")
                .put("specimen", "Blood")
                .put("bottle_number", 1)
                .put("report_stage", "final")
                .put("status", "positive")
                .put("organism", "Klebsiella pneumoniae"))

        val ui = SummaryJsonMapper.parseSummaryJsonToUiSummary(JSONObject().put("culture_table", cultures))

        assertEquals(1, ui.cultures.size)
        assertEquals("growth_detected", ui.cultures.single().status)
        assertEquals("Klebsiella pneumoniae", ui.cultures.single().organism)
        assertEquals("report_key:preliminary", ui.cultures.single().sourceKey)
        assertEquals(2, ui.cultures.single().timeline.size)
    }

    @Test
    fun consolidatedCultureUsesPreferredFinalSourceWhenBothStagesHaveKeys() {
        val cultures = JSONArray()
            .put(JSONObject()
                .put("report_id", "report_key:preliminary")
                .put("lab_study_number", "B100")
                .put("collection_date", "01-Jun-2026")
                .put("specimen", "Blood")
                .put("report_stage", "preliminary")
                .put("status", "pending"))
            .put(JSONObject()
                .put("report_id", "report_key:final")
                .put("lab_study_number", "B100")
                .put("collection_date", "01-Jun-2026")
                .put("specimen", "Blood")
                .put("report_stage", "final")
                .put("status", "positive"))

        val row = SummaryJsonMapper.parseSummaryJsonToUiSummary(
            JSONObject().put("culture_table", cultures)
        ).cultures.single()

        assertEquals("report_key:final", row.sourceKey)
    }

    @Test
    fun cultureCommentDoesNotRepeatStructuredTimingAndBottleFields() {
        val culture = JSONObject()
            .put("report_stage", "final")
            .put("reporting_date", "02-Jun-2026 10:30")
            .put("bottle_name", "First bottle")
            .put("set_number", 1)
            .put("bottle_number", 1)
            .put("comment", "Clinical correlation advised")

        val row = SummaryJsonMapper.parseSummaryJsonToUiSummary(
            JSONObject().put("culture_table", JSONArray().put(culture))
        ).cultures.single()

        assertEquals("Clinical correlation advised", row.comment)
        assertEquals("02-Jun-2026 10:30", row.reportingDate)
        assertEquals(1, row.bottleNumber)
    }

    private fun summaryWithTrendRows(vararg rows: JSONObject): JSONObject = JSONObject()
        .put("lab_trend_table", JSONObject()
            .put("columns", JSONArray().put("02-06-2026").put("01-06-2026").put("31-05-2026"))
            .put("rows", JSONArray().also { array -> rows.forEach { array.put(it) } }))

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
