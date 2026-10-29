package org.kundakarlab.nimsresults

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Regression fixture shaped like the observed 163-row NIMS REST response.
 * It intentionally contains NO real patient information or clinical values.
 */
class RestContextBoundV129Test {
    private val cr = "111111111111111"

    private fun observedShape(): JSONArray = JSONArray().apply {
        repeat(163) { i ->
            put(JSONObject().apply {
                put("testName", if (i % 2 == 0) "CBC" else "CREATININE")
                put("reportDate", "29-Sep-2026")
                put("unknownMetadata", "source-only")
            })
        }
    }

    @Test fun rawReportListWithoutCrRemainsRejectedWithoutVerifiedPatientHeader() {
        assertTrue(ReportPayloadParser.parseRest(observedShape(), cr).isEmpty())
    }

    @Test fun verifiedExternalPatientHeaderAcceptsOnlyContextBoundMetadata() {
        val result=ReportPayloadParser.parseRest(
            observedShape(), cr, verifiedPatientContext = true)
        assertEquals(163,result.size)
        assertEquals("29-Sep-2026", result.first().reportedDate)
        assertTrue(result.all { it.sourceTag == "nims_rest_reportList_context" })
        assertTrue(result.all { it.labNumber.isBlank() && it.sampleNumber.isBlank() })
        assertTrue(result.all { it.url.isBlank() })
        assertTrue(result.first().sourceRow.contains("unknownMetadata"))
        // A same-day test name does not prove a specimen identity or PDF.
        val b=ClinicalBundle(PatientInfo(crNo=cr), emptyList(),result)
        assertEquals(163,UnifiedResultsFeed.baseEntries(b).size)
    }

    @Test fun conflictingPatientCrMustRejectEvenWithExternalContext() {
        val response=JSONArray().put(JSONObject().apply {
            put("testName","CBC")
            put("reportDate","29-Sep-2026")
            put("crNo","222222222222222")
        })
        assertThrows(IdentityMismatchException::class.java) {
            ReportPayloadParser.parseRest(response,cr,verifiedPatientContext=true)
        }
    }

    @Test fun diagnosticsLabelReportDateSeparatelyFromReportingTimestamp() {
        val response=observedShape()
        RestReportDiagnostics.reset()
        RestReportDiagnostics.inspect(response,cr)
        val before=RestReportDiagnostics.snapshot()
        assertTrue(before.contains("rest.rowsWithSourceReportDate=163"))
        assertTrue(before.contains("rest.rowsWithExplicitReportingTimestamp=0"))
        assertTrue(before.contains("rest.crEvidence=not_returned"))
        RestReportDiagnostics.binding(true)
        val log=RestReportDiagnostics.snapshot()
        assertTrue(log.contains(
            "rest.patientBinding=authenticated_patient_header_context_only"))
        assertFalse(log.contains("111111111111111"))
        assertFalse(log.contains("unknownMetadata"))
    }

    @Test fun contextOnlyCannotPretendToVerifyEnquiryOrderOrPdfIdentity() {
        val rest=ReportPayloadParser.parseRest(
            observedShape(), cr, verifiedPatientContext=true)
        val order=EnquiryOrder("CBC","Report Generated",sampleNumber="S-123")
        val candidate=EnquirySourceBridge.assess(order,rest)
        assertEquals(EnquirySourceBridge.State.UNMATCHED,candidate.state)
    }
}
