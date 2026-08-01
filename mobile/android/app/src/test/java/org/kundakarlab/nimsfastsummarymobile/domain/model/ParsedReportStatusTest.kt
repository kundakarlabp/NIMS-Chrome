package org.kundakarlab.nimsfastsummarymobile.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsedReportStatusTest {

    @Test
    fun fullyParsedReportHasExplicitStatusAndProvenance() {
        val report = report(
            labs = listOf(
                ParsedLabValue(
                    canonicalCode = "HGB",
                    displayName = "Haemoglobin",
                    sourceName = "Hb",
                    numericValue = 9.8,
                    textValue = null,
                    unit = "g/dL",
                    referenceLow = 12.0,
                    referenceHigh = 16.0,
                    abnormality = Abnormality.LOW,
                    resultDate = "01-Aug-2026",
                    confidence = ParseConfidence.HIGH
                )
            )
        )

        assertEquals(ReportParseStatus.FULLY_PARSED, report.parseStatus)
        assertEquals(1, report.structuredValueCount)

        val json = report.toHelperJson()
        val processing = json.getJSONObject("processing")
        assertEquals("fully_parsed", processing.getString("parse_status"))
        assertEquals("R-1", processing.getString("source_report_id"))
        assertEquals("CBC", processing.getString("source_report_name"))
        assertEquals("01-Aug-2026", processing.getString("source_report_date"))
        assertEquals(1, processing.getInt("structured_value_count"))
    }

    @Test
    fun structuredReportWithWarningsIsPartial() {
        val report = report(
            labs = listOf(minimalLab()),
            warnings = listOf("Platelet row was not extracted")
        )

        assertEquals(ReportParseStatus.PARTIALLY_PARSED, report.parseStatus)
        assertEquals(1, report.toHelperJson().getJSONArray("errors").length())
    }

    @Test
    fun rawTextWithoutStructuredValuesIsUnstructured() {
        val report = report(rawText = "Complete blood picture report text")
        assertEquals(ReportParseStatus.UNSTRUCTURED, report.parseStatus)
    }

    @Test
    fun sessionAndFetchFailuresAreNotSilentlyUnstructured() {
        assertEquals(
            ReportParseStatus.SESSION_EXPIRED,
            report(warnings = listOf("NIMS session appears expired. Login again.")).parseStatus
        )
        assertEquals(
            ReportParseStatus.FETCH_FAILED,
            report(warnings = listOf("Report fetch failed with HTTP 500")).parseStatus
        )
        assertEquals(
            ReportParseStatus.UNSUPPORTED,
            report(warnings = listOf("Unsupported report format")).parseStatus
        )
    }

    @Test
    fun helperJsonRetainsWarningsForClinicalVerification() {
        val warning = "Only Hb was extracted; verify original PDF"
        val processing = report(
            labs = listOf(minimalLab()),
            warnings = listOf(warning)
        ).toHelperJson().getJSONObject("processing")

        assertTrue(processing.getJSONArray("parser_warnings").toString().contains(warning))
    }

    private fun report(
        labs: List<ParsedLabValue> = emptyList(),
        warnings: List<String> = emptyList(),
        rawText: String = ""
    ) = ParsedReport(
        reportId = "R-1",
        reportName = "CBC",
        dateSent = "01-Aug-2026",
        reportType = "lab",
        labs = labs,
        warnings = warnings,
        processorName = "test",
        rawText = rawText
    )

    private fun minimalLab() = ParsedLabValue(
        canonicalCode = "HGB",
        displayName = "Haemoglobin",
        sourceName = "Hb",
        numericValue = 9.8,
        textValue = null,
        unit = "g/dL",
        referenceLow = null,
        referenceHigh = null,
        abnormality = Abnormality.LOW,
        resultDate = null,
        confidence = ParseConfidence.HIGH
    )
}
