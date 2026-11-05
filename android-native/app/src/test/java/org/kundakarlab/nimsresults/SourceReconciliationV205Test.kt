package org.kundakarlab.nimsresults

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

/** Synthetic source PDFs; all identifiers are fictional test fixtures. */
class SourceReconciliationV205Test {
    private val cr="123456789012345"
    private fun result(test:String,text:String,date:String="2026-11-04",
        specimen:String="")=LabResult(
        id=test,date=date,test=test,parameter="Result",
        valueText=text,specimenType=specimen
    )
    private fun source(title:String,lab:String,reportDate:String,
        body:String,specimen:String="BONE MARROW",suffix:String=lab):ReportRef {
        val url="https://www.nimsts.edu.in/source/"+suffix+".pdf"
        val pdf="""
            Department of Microbiology
            CR No: $cr
            Lab/Study No: $lab
            Coll/Study Date: 02-Nov-2026 10:20
            Reporting Date: $reportDate
            Sample Processed: $specimen
            $body
        """.trimIndent()
        assertTrue(ReportTitleCache.learnPages(url,listOf(pdf),cr))
        return ReportRef(id=suffix,title=title,labNumber=lab,
            url=url,date="02-Nov-2026 10:20",
            collectionDateTime="02-Nov-2026 10:20",sourceTag="nims_html")
    }
    @After fun cleanup(){ReportTitleCache.clear()}

    @Test fun identicalBloodCultureNegativeBottlesNeverCrossLink() {
        val noGrowth="NO AEROBIC GROWTH AFTER 5 DAYS"
        val one=source("Fan Blood Culture(1 set 1 bottle) Final",
            "B11101","04-Nov-2026 14:05",noGrowth,
            "BLOOD","one")
        val two=source("Fan Blood Culture(1 set 2 bottle) Final",
            "B11102","04-Nov-2026 14:05",noGrowth,
            "BLOOD","two")
        val a=listOf(result("Blood culture Set 1 Bottle 1 Final",noGrowth))
        val b=listOf(result("Blood culture Set 1 Bottle 2 Final",noGrowth))
        assertEquals(one.url,MicrobiologyPdfIdentity.unique(a,listOf(one,two),
            cr,LocalDate.parse("2026-11-04"))?.report?.url)
        assertEquals(two.url,MicrobiologyPdfIdentity.unique(b,listOf(one,two),
            cr,LocalDate.parse("2026-11-04"))?.report?.url)
        assertNull(MicrobiologyPdfIdentity.unique(
            listOf(result("Blood culture Set 1 Bottle 3 Final",noGrowth)),
            listOf(one,two),cr,LocalDate.parse("2026-11-04")))
    }

    @Test fun genericStainingTitlesRecoveredBySignedPdfClassification() {
        val calco="CALCOFLUOR FLUORESCENCE SHOWS NO FUNGAL ELEMENTS"
        val grams="GIEMSA SMEAR SHOWS NO INTRACELLULAR PARASITES OR BACTERIA"
        val a=source("STAINING","E11101","04-Nov-2026 14:05",
            calco,"BONE MARROW","calco")
        val b=source("STAINING","E11102","04-Nov-2026 14:05",
            grams,"BONE MARROW","gram")
        val row=listOf(result("FUNGAL FLUORESCENCE STAINING (CALCOFLUOR)",
            calco))
        val candidates=SourceMetadataReview.reviewCandidates(row,listOf(a,b),cr)
        assertTrue(candidates.any{it.url==a.url})
        assertEquals(a.url,MicrobiologyPdfIdentity.unique(row,candidates,cr,
            LocalDate.parse("2026-11-04"))?.report?.url)
    }

    @Test fun urineCleanCatchSpecimenMatchesUrineButNotBoneMarrow() {
        val text="CULTURE SHOWS NO SIGNIFICANT BACTERIURIA COLONY COUNT LESS THAN 100 CFU ML"
        val urine=source("URINE AEROBIC CULTURE","U11111",
            "04-Nov-2026 10:30",text,"CLEAN CATCH","urine")
        val row=listOf(result("Urine Aerobic Culture",text,specimen="URINE"))
        assertEquals(urine.url,MicrobiologyPdfIdentity.unique(row,
            listOf(urine),cr,LocalDate.parse("2026-11-04"))?.report?.url)
        val wrong=listOf(result("Urine Aerobic Culture",text,specimen="BONE MARROW"))
        assertNull(MicrobiologyPdfIdentity.unique(wrong,
            listOf(urine),cr,LocalDate.parse("2026-11-04")))
    }

    @Test fun evidenceSurvivesFullTextEvictionAndStillMatches() {
        val target=source("AEROBIC CULTURE","E22201",
            "04-Nov-2026 11:00",
            "CULTURE SHOWS NO GROWTH AFTER 48 HOURS","BONE MARROW","first")
        for(index in 1..17)source("AEROBIC CULTURE",
            "E3"+index.toString().padStart(4,'0'),
            "04-Nov-2026 11:00","SOME OTHER RESULT",
            "BONE MARROW","other"+index)
        assertNull(ReportTitleCache.extracted(target.url))
        assertNotNull(ReportTitleCache.compactEvidence(target.url))
        assertEquals(target.url,MicrobiologyPdfIdentity.unique(
            listOf(result("AEROBIC CULTURE",
                "CULTURE SHOWS NO GROWTH AFTER 48 HOURS")),
            listOf(target),cr,LocalDate.parse("2026-11-04"))?.report?.url)
    }

    @Test fun contradictorySourceStillMarkedAndNotSilentlyInterpreted() {
        val narrative="CULTURE SHOWS NO SIGNIFICANT BACTERIURIA. " +
            "CULTURE SHOWS GROWTH OF ESCHERICHIA COLI. ESBL PRODUCING."
        val urine=source("URINE AEROBIC CULTURE","U11113",
            "04-Nov-2026 10:30",narrative,"CLEAN CATCH","conflicting")
        val row=listOf(result("Urine Aerobic Culture",
            "CULTURE SHOWS NO SIGNIFICANT BACTERIURIA"))
        val matched=MicrobiologyPdfIdentity.unique(row,listOf(urine),cr,
            LocalDate.parse("2026-11-04"))
        assertNotNull(matched)
        assertTrue(matched!!.discordant)
    }

    @Test fun scannerRejectsUnrelatedAndMultiplePatientIdentifiers() {
        assertEquals("123456789012345",CrScanParser.parse("123456789012345"))
        assertEquals("123456789012345",
            CrScanParser.parse("NIMS Hospital; Patient CR No: 123456789012345"))
        assertEquals("123456789012345",
            CrScanParser.parse("https://www.nimsts.edu.in/labs?crNo=123456789012345"))
        assertNull(CrScanParser.parse("https://evil.example/?cr=123456789012345"))
        assertNull(CrScanParser.parse("barcode 123456789012345 111111111111111"))
        assertNull(CrScanParser.parse("scan barcode 123456789012345 extra digits 666"))
    }
}
