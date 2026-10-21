package org.kundakarlab.nimsresults

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AppReviewV114Test {
    private val today = LocalDate.of(2026, 10, 21)

    @Test fun recentWindowIsSevenLocalCalendarDaysIncludingToday() {
        assertTrue(RecentReportDates.isLastWeek(LocalDate.of(2026, 10, 15), today))
        assertTrue(RecentReportDates.isLastWeek(today, today))
        assertFalse(RecentReportDates.isLastWeek(LocalDate.of(2026, 10, 14), today))
        assertFalse(RecentReportDates.isLastWeek(today.plusDays(1), today))
    }

    @Test fun recentSourceDatePrioritizesReportedDateOverOrderOrIndex() {
        val report = ReportRef("r1", "Aerobic Culture", date="16-Oct-2026",
            reportedDate="21-Oct-2026 16:02", orderDate="15-Oct-2026")
        assertEquals(today, RecentReportDates.dateFor(report).first)
        assertEquals("Reported", RecentReportDates.dateFor(report).second)
        val indexed = report.copy(reportedDate="")
        assertEquals(LocalDate.of(2026,10,16), RecentReportDates.dateFor(indexed).first)
        assertEquals("Source index date", RecentReportDates.dateFor(indexed).second)
        val noIndex = report.copy(date="", reportedDate="", orderDate="20-Oct-2026")
        assertEquals(null, RecentReportDates.dateFor(noIndex).first)
    }

    @Test fun sourceAndStructuredDatesParseWithoutInventingCollectionDates() {
        assertEquals(today, RecentReportDates.parse("21-Oct-2026 15:22"))
        assertEquals(today, RecentReportDates.parse("2026-10-21"))
        assertEquals(today, RecentReportDates.parse("21/10/2026"))
        assertEquals(null, RecentReportDates.parse("Not confirmed"))
    }

    @Test fun resultDomainsUseInvestigationNamesNotPatientNarrative() {
        assertEquals(ResultSection.PATHOLOGY, ResultSections.of("HISTOPATHOLOGY SPECIMEN I x 2"))
        assertEquals(ResultSection.PATHOLOGY, ResultSections.of("Peripheral smear examination"))
        assertEquals(ResultSection.METABOLIC, ResultSections.of("ELECTROLYTES (Na,K & Cl)"))
        assertEquals(ResultSection.METABOLIC,
            ResultSections.of("COMPLETE URINE EXAMINATION (CHEMISTRY + MICROSCOPY)"))
        assertEquals(ResultSection.METABOLIC, ResultSections.of("SERUM CREATININE"))
        assertEquals(ResultSection.MICROBIOLOGY, ResultSections.of("TB FLUORESCENCE STAINING"))
        assertEquals(ResultSection.MICROBIOLOGY, ResultSections.of("Urine Aerobic Culture"))
        assertEquals(ResultSection.OTHER,
            ResultSections.of("CT Chest mentions earlier bacterial culture"))
    }

    @Test fun entireAppAuditCountsUnmappedTestsWithoutCopyingPatientData() {
        DiagnosticEvents.clear()
        val cr = "123456789012345"
        val source = LabResult("r", "Blood Grouping & Antibody Screening",
            "Combo1", "2026-10-21", "SECRET PATIENT NAME 123456789012345",
            labNumber = "PRIVATEACCESSION1")
        val bundle = ClinicalBundle(
            PatientInfo(name="PATIENT NAME", crNo=cr),
            listOf(source),
            listOf(ReportRef("r1", "Transfusion", "21-Oct-2026",
                url="https://www.nimsts.edu.in/secret-report.pdf"))
        )
        DiagnosticEvents.record("PDF_TEXT_MISSING")
        val report = AppDiagnostics.summarize(bundle)
        assertTrue(report.contains("audit.scope=all_fetched_investigations"))
        assertTrue(report.contains("audit.result_groups_no_accession=0"))
        assertTrue(report.contains("PDF_TEXT_MISSING=1"))
        assertFalse(report.contains(cr))
        assertFalse(report.contains("PATIENT NAME"))
        assertFalse(report.contains("PRIVATEACCESSION1"))
        assertFalse(report.contains("SECRET"))
        assertFalse(report.contains("secret-report"))
        DiagnosticEvents.clear()
    }

    @Test fun diagnosticsRejectUnknownTelemetryCodes() {
        DiagnosticEvents.clear()
        DiagnosticEvents.record("PATIEnt 123456789012345 secret")
        assertTrue(DiagnosticEvents.snapshot().isEmpty())
    }
}
