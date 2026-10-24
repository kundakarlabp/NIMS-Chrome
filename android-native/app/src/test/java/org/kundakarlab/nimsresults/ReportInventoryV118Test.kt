package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class ReportInventoryV118Test {
    private val cr = "111111111111111"
    private val endpoint =
        "https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/invDuplicateResultReportPrinting.cnt"

    @Test fun emptyCellsMustNotShiftTheLabOrDateColumns() {
        val html = """
            <html><body>CR No: $cr
            <table><tr>
              <td>Routine Chemistry</td><td>Liver Function Tests</td>
              <td></td><td>28-Aug-2025</td><td>1</td>
              <td><a href="$endpoint?hmode=PRINTREPORT&amp;fileName=abc.pdf">View Report</a></td>
            </tr></table></body></html>
        """.trimIndent()
        val reports = ReportPayloadParser.parseHtml(html, endpoint, cr)
        assertEquals(1, reports.size)
        assertEquals("Liver Function Tests", reports[0].title)
        assertEquals("", reports[0].labNumber)
        assertEquals("28-Aug-2025", reports[0].sentDate)
        assertTrue(reports[0].url.contains("PRINTREPORT"))
    }

    @Test fun sourceRowIsRetainedWithoutInventingAnUnrecognizedPdfUrl() {
        val html = """
            <html><body>CR No: $cr
            <table><tr>
              <td>Bacteriology</td><td>Aerobic Culture</td>
              <td>25BCT12345/E12345</td><td>14-Jul-2025</td><td>1</td>
              <td><a onclick="openSomethingElse('opaque','not-a-pdf')">View Report</a></td>
            </tr></table></body></html>
        """.trimIndent()
        val reports = ReportPayloadParser.parseHtml(html, endpoint, cr)
        assertEquals(1, reports.size)
        assertEquals("E12345", reports[0].labNumber)
        assertEquals("25BCT12345", reports[0].sampleNumber)
        assertEquals("", reports[0].url)
    }

    @Test fun signedReportUrlInEventHandlerIsAcceptedWithoutGuessing() {
        val html = """
            <html><body>CR No: $cr
            <table><tr>
              <td>Bacteriology</td><td>Aerobic Culture</td>
              <td>25BCT12345/E12345</td><td>14-Jul-2025</td><td>1</td>
              <td><a onclick="window.open('$endpoint?hmode=PRINTREPORT&amp;fileName=reported.pdf')">View Report</a></td>
            </tr></table></body></html>
        """.trimIndent()
        val reports = ReportPayloadParser.parseHtml(html, endpoint, cr)
        assertEquals(1, reports.size)
        assertTrue(reports[0].url.contains("fileName=reported.pdf"))
    }

    @Test fun wrongPatientCrIsRejected() {
        val html = """
            <html><body>CR No: 222222222222222
              <table><tr><td>Bacteriology</td><td>CULTURE</td>
              <td>specimen/E12345</td><td>14-Jul-2025</td><td>1</td>
              <td><a href="$endpoint?hmode=PRINTREPORT&amp;fileName=source.pdf">View Report</a></td>
              </tr></table></body></html>
        """.trimIndent()
        assertThrows(IdentityMismatchException::class.java) {
            ReportPayloadParser.parseHtml(html, endpoint, cr)
        }
    }
}
