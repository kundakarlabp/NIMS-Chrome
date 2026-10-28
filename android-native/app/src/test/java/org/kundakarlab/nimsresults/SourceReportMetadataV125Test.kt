package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class SourceReportMetadataV125Test {
    private val cr = "111111111111111"

    @Test fun reportIndexRetainsSpecimenLabAndCollectionTimeWithoutPdfLink() {
        val html = """
            <html><body><div>CR No: $cr</div><table>
            <tr><th>LabName</th><th>Test Name</th>
              <th>Sample No/Lab No</th><th>Collection Date</th>
              <th>Report NO.</th><th>Report</th></tr>
            <tr><td>Bacteriology</td><td>AEROBIC CULTURE</td>
              <td>S-101/L-202</td><td>27-Oct-2026 09:45</td>
              <td>REP-3</td><td>View Report</td></tr>
            </table></body></html>
        """.trimIndent()
        val reports = ReportPayloadParser.parseHtml(html, NimsUrls.CR_REPORTS, cr)
        assertEquals(1, reports.size)
        val r=reports.single()
        assertEquals("S-101",r.sampleNumber)
        assertEquals("L-202",r.labNumber)
        assertEquals("S-101/L-202",r.sourceSampleLabText)
        assertEquals("27-Oct-2026 09:45",r.collectionDateTime)
        assertEquals("REP-3",r.reportNumber)
        assertTrue(r.url.isBlank())
        val feed=UnifiedResultsFeed.entries(ClinicalBundle(PatientInfo(crNo=cr),
            emptyList(),reports))
        assertEquals(1,feed.size)
        assertNotNull(feed.single().report)
        assertEquals(1,UnifiedResultsFeed.entries(ClinicalBundle(PatientInfo(crNo=cr),
            emptyList(),reports),query="S-101").size)
    }

    @Test fun ambiguousCombinedNumberMustNotInventLabNumber() {
        val html = """
            <html><body><div>CR No: $cr</div><table><tr>
              <td>Microbiology</td><td>Culture</td>
              <td>REF/25/200/L900</td><td>28-Oct-2026</td>
              <td>REPORT-4</td><td>View Report</td></tr>
            </table></body></html>
        """.trimIndent()
        val report=ReportPayloadParser.parseHtml(html,NimsUrls.CR_REPORTS,cr).single()
        assertEquals("REF/25/200/L900",report.sourceSampleLabText)
        assertEquals("",report.sampleNumber)
        assertEquals("",report.labNumber)
    }

    @Test fun resultColumnDateIsNotCollectionOrReportingTimestamp() {
        val raw="""{"status":1,"crNo":"111111111111111","groups":[
          {"testName":"CBC","rows":[{"parameterName":"WBC",
           "valuesByDate":{"28-Oct-2026":"11.2"}}]}]}"""
        val bundle=BulkParser.parse(raw,cr,PatientInfo(crNo=cr),emptyList())
        val r=bundle.results.single()
        assertEquals("2026-10-28",r.date)
        assertTrue(r.collectionDateTime.isBlank())
        assertTrue(r.reportedDate.isBlank())
        assertTrue(r.labNumber.isBlank())
    }
}
