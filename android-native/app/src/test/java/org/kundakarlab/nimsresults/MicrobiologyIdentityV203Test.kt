package org.kundakarlab.nimsresults

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

/** Fully synthetic accession and CR values; no clinical identities in tests. */
class MicrobiologyIdentityV203Test {
    private val cr="123456789012345"
    private fun row(test:String,date:String,value:String,lab:String="")=LabResult(
        id=test+date,test=test,date=date,parameter="Result",
        valueText=value,labNumber=lab
    )
    private fun report(lab:String,title:String,col:String,rep:String,body:String,
        url:String="https://nimsts.edu.in/source/$lab.pdf"):ReportRef {
        val text="""
            Department of Microbiology
            CR No : $cr
            Lab/Study No : $lab
            Coll/Study Date : $col
            Reporting Date : $rep
            Sample Processed: BONE MARROW
            $body
        """.trimIndent()
        assertTrue(ReportTitleCache.learnPages(url,listOf(text),cr))
        return ReportRef(id=lab,title=title,url=url,labNumber=lab,
            date=col,collectionDateTime=col,reportedDate=rep,
            sourceTag="nims_html_index")
    }

    @After fun clearSources() { ReportTitleCache.clear() }

    @Test fun sharedAccessionForStainAndCultureDoesNotLinkWrongPdf() {
        val stainText="GIEMSA SMEAR SHOWS NO INTRACELLULAR PARASITE, BACTERIA AND FUNGAL ELEMENT."
        val growthText="CULTURE SHOWS NO GROWTH AEROBICALLY AFTER INCUBATION FOR ABOUT 36 TO 48 HOURS."
        val stain=report("E19079","STAINING",
            "02-Nov-2026 18:52","02-Nov-2026 23:22",stainText,
            "https://nimsts.edu.in/source/stain.pdf")
        val culture=report("E19079","AEROBIC CULTURE",
            "02-Nov-2026 18:52","04-Nov-2026 16:32",growthText,
            "https://nimsts.edu.in/source/culture.pdf")
        val stainRows=listOf(row("GRAMS STAINING","2026-11-02",stainText,"E19079"))
        val cultureRows=listOf(row("AEROBIC CULTURE","2026-11-04",growthText,"E19079"))
        val stash=SourceMetadataReview.pdfCandidates(stainRows,listOf(stain,culture))
        assertEquals(listOf(stain.url),stash.map { it.url })
        assertEquals("GIEMSA SMEAR",MicrobiologyPdfIdentity.displayTitle(
            "GRAMS STAINING",stainRows))
        val matchedStain=MicrobiologyPdfIdentity.unique(stainRows,stash,
            cr,LocalDate.parse("2026-11-02"))!!
        assertEquals("E19079",matchedStain.header.labNumber)
        assertEquals("BONE MARROW",matchedStain.specimen)
        assertEquals("Lab E19079 · Col 02/11 18:52 · Rep 02/11 23:22",
            ClinicalResultsLayout.oneLineMicrobiologyMetadata(
                stainRows,emptyList(),matchedStain.header))
        val matchedCulture=MicrobiologyPdfIdentity.unique(cultureRows,
            SourceMetadataReview.pdfCandidates(cultureRows,listOf(stain,culture)),
            cr,LocalDate.parse("2026-11-04"))!!
        assertEquals(culture.url,matchedCulture.report.url)
        assertEquals("Lab E19079 · Col 02/11 18:52 · Rep 04/11 16:32",
            ClinicalResultsLayout.oneLineMicrobiologyMetadata(
                cultureRows,emptyList(),matchedCulture.header))
    }

    @Test fun urineCultureDoesNotOfferOlderAccessionOnCurrentResultCard() {
        val currentBody="""
            GRAMS SMEAR SHOWS <5 PUS CELLS/HPF
            CULTURE SHOWS NO SIGNIFICANT BACTERIURIA. COLONY COUNT LESS THAN100 CFU/ml.
            PLEASE REPEAT IF CLINICAL CONDITION WARRANTS.
            CULTURE SHOWS GROWTH OF ESCHERICHIA COLI.
            EXTENDED SPECTRUM BETA LACTAMASE PRODUCING STRAIN.
        """.trimIndent()
        val a=report("U31379","Urine Aerobic Culture",
            "30-Oct-2026 18:32","01-Nov-2026 15:07",
            currentBody.replace("BONE MARROW","CLEAN CATCH"),
            "https://nimsts.edu.in/source/urine-current.pdf")
        val b=report("U30927","Urine Aerobic Culture",
            "27-Oct-2026 12:10","28-Oct-2026 09:00",
            "CULTURE SHOWS GROWTH OF ESCHERICHIA COLI.",
            "https://nimsts.edu.in/source/urine-old.pdf")
        val rows=listOf(row("Urine Aerobic Culture","2026-11-01",
            "CULTURE SHOWS NO SIGNIFICANT BACTERIURIA. COLONY COUNT LESS THAN100 CFU/ml."))
        val candidates=SourceMetadataReview.pdfCandidates(rows,listOf(a,b))
        assertEquals(listOf("U31379"),candidates.map { it.labNumber })
        assertTrue(MicrobiologyPdfIdentity.contradictoryCulture(currentBody))
        val finding=MicrobiologyPdfIdentity.unique(rows,candidates,cr,
            LocalDate.parse("2026-11-01"))
        assertNotNull(finding)
        assertTrue(finding!!.discordant)
        assertEquals("U31379",finding.header.labNumber)
        val shown=ClinicalReadableSummaries.urineCulture(currentBody)
        assertTrue(shown.any { it.heading.contains("Conflicting culture statements") })
        assertTrue(shown.none { it.heading=="Laboratory note" })
    }

    @Test fun ambiguousUninspectedUrinePdfsNeverBecomeDirectLinks() {
        val rows=listOf(row("Urine Aerobic Culture","2026-11-01",
            "CULTURE SHOWS NO SIGNIFICANT BACTERIURIA."))
        val options=listOf(
            ReportRef(id="a",title="Urine Aerobic Culture",
                url="https://nimsts.edu.in/a.pdf",date="2026-10-30"),
            ReportRef(id="b",title="Urine Aerobic Culture",
                url="https://nimsts.edu.in/b.pdf",date="2026-10-27"))
        assertNull(MicrobiologyPdfIdentity.unique(rows,options,cr,
            LocalDate.parse("2026-11-01")))
    }

    @Test fun metadataKeepsSentOnlyWhenExplicitlyProvided() {
        val rows=listOf(row("GRAMS STAINING","2026-11-02",
            "GIEMSA SMEAR SHOWS NO PARASITES",lab="E19079").copy(
            collectionDateTime="2026-11-02 17:50",
            sentDate="2026-11-02 18:15",
            reportedDate="2026-11-02 23:22"))
        assertEquals("Lab E19079 · Col 02/11 17:50 · Sent 02/11 18:15 · Rep 02/11 23:22",
            ClinicalResultsLayout.oneLineMicrobiologyMetadata(rows,emptyList()))
        assertEquals("",
            ClinicalResultsLayout.oneLineMicrobiologyMetadata(
                listOf(row("GRAMS STAINING","2026-11-02","no bacteria")),emptyList()))
    }
}
