package org.kundakarlab.nimsresults

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** All data are synthetic; no patient details, lab IDs or report contents. */
class ParameterDiagnosticsTest {
    private val cr = "123456789012345"
    private val fixture = """
        {"status":1,"crNo":"123456789012345","groups":[
          {"testName":"Complete Blood Count","rows":[
            {"parameterName":"Hemoglobin","unit":"g/dL",
             "valuesByDate":{"04-Nov-2026":"13.4","03-Nov-2026":"14.1"}},
            {"parameterName":"Empty parameter","valuesByDate":{"04-Nov-2026":""}}
          ]},
          {"testName":"Complete Urine Examination","rows":[
            {"parameterName":"Ketones (Result)","valuesByDate":{"04-Nov-2026":"1"}}
          ]},
          {"testName":"Blood culture","rows":[
            {"parameterName":"Result","valuesByDate":{"04-Nov-2026":"No growth after 48 hours"}}
          ]}
        ]}
    """.trimIndent()

    @Test fun everySourceParameterAndEveryNormalizedResultHasTrace() {
        val bundle = BulkParser.parse(fixture, cr, PatientInfo(crNo = cr), emptyList())
        assertEquals(4, bundle.rawRows.size)
        assertEquals(4, bundle.results.size)
        val trace = ParameterDiagnostics.describe(bundle)
        assertTrue(trace.contains("audit.parameters.source_rows=4"))
        assertTrue(trace.contains("audit.parameters.source_date_cells=5"))
        assertTrue(trace.contains("audit.parameters.empty_source_cells=1"))
        assertTrue(trace.contains("audit.parameters.nonempty_source_cells_without_normalized=0"))
        assertTrue(trace.contains("audit.parameters.normalized_results=4"))
        assertTrue(trace.contains("audit.parameters.normalized_without_raw_key=0"))
        assertEquals(4, Regex("""parameter\.source\[\d+\]\.group=""").findAll(trace).count())
        assertEquals(4, Regex("""parameter\.result\[\d+\]\.test=""").findAll(trace).count())
        assertTrue(trace.contains("field=Hemoglobin"))
        assertTrue(trace.contains("field=Ketones (Result)"))
        assertTrue(trace.contains("display=source_review_unmapped"))
        assertTrue(trace.contains("display=inline"))
        assertTrue(trace.contains("date_parseable=true"))
        assertFalse(trace.contains("13.4"))
        assertFalse(trace.contains("14.1"))
        assertFalse(trace.contains("No growth after 48 hours"))
        assertFalse(trace.contains(cr))
        assertFalse(trace.contains("04-Nov-2026"))
    }

    @Test fun missingNormalizedCellIsExposedWithoutGuessingClinicalValue() {
        val bundle = BulkParser.parse(fixture, cr, PatientInfo(crNo = cr), emptyList())
        val smaller = bundle.copy(results = bundle.results.filterNot {
            it.test == "Blood culture"
        })
        val trace = ParameterDiagnostics.describe(smaller)
        assertTrue(trace.contains("audit.parameters.nonempty_source_cells_without_normalized=1"))
        assertTrue(trace.contains("state=source_not_normalized"))
        assertTrue(trace.contains("audit.parameters.normalized_results=3"))
    }

    @Test fun identifiersAndRawValuesAreRedactedEvenWhenInFieldLabels() {
        val bundle = ClinicalBundle(
            PatientInfo(crNo = cr, name = "Private Patient"),
            listOf(LabResult(id="x", test="CBC 123456789012345",
                parameter="Result https://example.test/patient/123456789012345",
                date="2026-11-04", valueText="Private Patient secret_value",
                sourceKey="unmatched", labNumber="LAB-PRIVATE-004")),
            emptyList()
        )
        val out = ParameterDiagnostics.describe(bundle)
        assertTrue(out.contains("[number]"))
        assertTrue(out.contains("[url]"))
        assertFalse(out.contains(cr))
        assertFalse(out.contains("Private Patient"))
        assertFalse(out.contains("LAB-PRIVATE-004"))
        assertFalse(out.contains("secret_value"))
        assertTrue(out.contains("audit.parameters.normalized_without_raw_key=unknown_no_raw_rows"))
    }

    @Test fun newlyIntroducedDiagnosticEventsAreNotSilentlyIgnored() {
        DiagnosticEvents.clear()
        DiagnosticEvents.record("CR_PANEL_TIMEOUT")
        DiagnosticEvents.record("WEBVIEW_RENDERER_CRASH")
        DiagnosticEvents.record("TARGET_PDF_EVIDENCE_UNAVAILABLE")
        DiagnosticEvents.record("PRIVATE PATIENT SECRET")
        val counts = DiagnosticEvents.snapshot()
        assertEquals(1, counts["CR_PANEL_TIMEOUT"])
        assertEquals(1, counts["WEBVIEW_RENDERER_CRASH"])
        assertEquals(1, counts["TARGET_PDF_EVIDENCE_UNAVAILABLE"])
        assertEquals(3, counts.size)
        DiagnosticEvents.clear()
    }
}
