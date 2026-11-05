package org.kundakarlab.nimsresults

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class NimsDateParsingRegressionTest {
    @Test fun uppercasePortalReportDatesAreReadAsActualDates() {
        val expected = LocalDate.of(2026, 10, 24)
        assertEquals(expected, RecentReportDates.parse("24-OCT-2026 11:08"))
        assertEquals(expected, RecentReportDates.parse("24-Oct-2026"))
        assertEquals(expected, RecentReportDates.parse("24-oct-2026"))
        assertEquals(expected, RecentReportDates.parse("24/OCT/2026"))
    }

    @Test fun unpaddedApiIsoDatesAndLegacyNumericDatesAreAccepted() {
        val expected = LocalDate.of(2026, 1, 5)
        assertEquals(expected, RecentReportDates.parse("2026-1-5"))
        assertEquals(expected, RecentReportDates.parse("2026-01-05"))
        assertEquals(expected, RecentReportDates.parse("5/1/2026"))
        assertNull(RecentReportDates.parse("date not reported"))
    }

    @Test fun explicitlyReportedDateStillTakesPrecedenceOverIndexDate() {
        val report = ReportRef("ref", "Blood culture", date = "20-OCT-2026",
            reportedDate = "24-OCT-2026 11:08")
        assertEquals(LocalDate.of(2026, 10, 24), RecentReportDates.dateFor(report).first)
        assertEquals("Reported", RecentReportDates.dateFor(report).second)
    }
}
