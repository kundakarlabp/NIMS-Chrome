package org.kundakarlab.nimsresults

import java.time.LocalDate
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Synthetic-only specimens; no real patient identity or clinical record. */
class ClinicalPdfAndSourceV204Test {
    private val patientCr="123456789012345"
    private fun source(test:String, result:String, reported:String="2026-11-04") =
        LabResult("result-1",test,"Result",reported,result)
    private fun original(test:String,lab:String,col:String,rep:String,
        narrative:String):ReportRef {
        val url="https://nimsts.edu.in/source/"+lab+".pdf"
        val pdf="""
            CR No : $patientCr
            Lab/Study No : $lab
            Coll/Study Date : $col
            Reporting Date : $rep
            Sample Processed: BONE MARROW
            $narrative
        """.trimIndent()
        assertTrue(ReportTitleCache.learnPages(url,listOf(pdf),patientCr))
        return ReportRef(id=lab,title=test,labNumber=lab,url=url,
            collectionDateTime=col,date=col,sourceTag="nims_html")
    }
    @After fun clear() { ReportTitleCache.clear() }

    @Test fun cultureLinkIsRestoredAsManualCandidateWithoutInventingLabIdentity() {
        val row=source("AEROBIC CULTURE",
            "CULTURE SHOWS NO GROWTH AEROBICALLY AFTER 36 TO 48 HOURS")
        val indexed=ReportRef(id="e",title="AEROBIC CULTURE REPORT",
            labNumber="E11111",collectionDateTime="02-Nov-2026 10:00",
            url="https://nimsts.edu.in/source/aerobic.pdf")
        val choices=SourceMetadataReview.reviewCandidates(listOf(row),listOf(indexed))
        assertEquals(listOf(indexed.url),choices.map { it.url })
        assertNull(MicrobiologyPdfIdentity.unique(listOf(row),choices,
            patientCr,LocalDate.parse("2026-11-04")))
        val bundle=ClinicalBundle(PatientInfo(crNo=patientCr),listOf(row),
            listOf(indexed))
        val review=FullClinicalResultsDocument.build(bundle).single()
        assertEquals("",review.accession)
        assertEquals("",review.collectedAt)
        assertEquals("",review.reportedAt)
        assertTrue(review.evidence.contains("unresolved",ignoreCase=true))
    }

    @Test fun verifiedSignedPdfAddsCorrectAccessionAndTimestampsWithoutChangingResult() {
        val text="CULTURE SHOWS NO GROWTH AEROBICALLY AFTER 36 TO 48 HOURS"
        val report=original("AEROBIC CULTURE","E11111",
            "02-Nov-2026 10:00","04-Nov-2026 16:32",text)
        val row=source("AEROBIC CULTURE",text)
        val bundle=ClinicalBundle(PatientInfo(crNo=patientCr),
            listOf(row),listOf(report))
        val review=FullClinicalResultsDocument.build(bundle).single()
        assertEquals("E11111",review.accession)
        assertEquals("02-Nov-2026 10:00",review.collectedAt)
        assertEquals("04-Nov-2026 16:32",review.reportedAt)
        assertTrue(review.evidence.contains("CR-verified"))
        assertEquals(text,review.findings.first().value)
        val csv=FullClinicalResultsDocument.csv(listOf(review))
        assertTrue(csv.contains("E11111"))
        assertTrue(csv.contains("04-Nov-2026 16:32"))
        assertTrue(csv.contains(text))
    }

    @Test fun fungalFluorescenceAndKohAreNotRejectedAsGenericGramStains() {
        val koh="NO FUNGAL ELEMENTS SEEN ON THE KOH PREPARATION OF THIS SAMPLE"
        val fluoro="CALCOFLUOR FLUORESCENCE SHOWS NO FUNGAL ELEMENTS IN THIS SPECIMEN"
        val kohPdf=original("FUNGAL KOH PREPARATION","F11111",
            "03-Nov-2026 09:00","04-Nov-2026 11:00",koh)
        val fluorPdf=original("FUNGAL FLUORESCENCE STAINING (CALCOFLUOR)",
            "F11112","03-Nov-2026 09:00","04-Nov-2026 10:00",fluoro)
        val kohRow=listOf(source("FUNGAL KOH PREPARATION",koh))
        val fluorRow=listOf(source("FUNGAL FLUORESCENCE STAINING (CALCOFLUOR)",fluoro))
        assertEquals(kohPdf.url,MicrobiologyPdfIdentity.unique(
            kohRow,listOf(kohPdf,fluorPdf),patientCr,
            LocalDate.parse("2026-11-04"))?.report?.url)
        assertEquals(fluorPdf.url,MicrobiologyPdfIdentity.unique(
            fluorRow,listOf(kohPdf,fluorPdf),patientCr,
            LocalDate.parse("2026-11-04"))?.report?.url)
    }

    @Test fun fullCsvPreservesValuesAndClearlyLabelsUnknownSourceIdentity() {
        val row=source("CMV viral load test","2,450 IU/mL")
        val list=FullClinicalResultsDocument.build(
            ClinicalBundle(PatientInfo(crNo=patientCr),listOf(row),emptyList()))
        val csv=FullClinicalResultsDocument.csv(list)
        assertTrue(csv.contains("2,450 IU/mL"))
        assertTrue(csv.contains("Lab/Study ID"))
        assertTrue(csv.contains("Collection date/time"))
        assertTrue(csv.contains("Source status"))
        assertFalse(csv.contains("E11111"))
    }

    @Test fun selectedCsvOnlyContainsRequestedTests() {
        val rows=listOf(source("TEST ALPHA","10.5"),source("TEST BETA","25.0"))
        val all=FullClinicalResultsDocument.build(
            ClinicalBundle(PatientInfo(crNo=patientCr),rows,emptyList()))
        val first=all.first { it.title=="TEST ALPHA" }
        val selected=FullClinicalResultsDocument.csv(all,
            setOf(first.findings.first().selectionKey))
        assertTrue(selected.contains("TEST ALPHA"))
        assertFalse(selected.contains("TEST BETA"))
    }
}
