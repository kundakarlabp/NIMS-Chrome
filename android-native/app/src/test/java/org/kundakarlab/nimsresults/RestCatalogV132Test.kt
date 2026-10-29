package org.kundakarlab.nimsresults

import org.junit.Assert.*
import org.junit.Test

class RestCatalogV132Test {
    private fun rest(title:String,date:String) = ReportRef(
        id="rest",title=title,date=date,reportedDate=date,url="",
        sourceTag="nims_rest_reportList_context"
    )
    private fun pdf(title:String,date:String,url:String,lab:String="") = ReportRef(
        id=url,title=title,date=date,url=url,labNumber=lab,
        sourceTag="nims_html_source"
    )
    private val cr="123456789012345"

    @Test fun originalPdfIsDiscoverableFromMetadataOnlyRestWithoutFabricatedAccession() {
        val catalog=rest("CBC+DIFF (HB, PCV,RBC INDICES, PLT,TLC,DLC, PS)",
            "2026-09-29 11:17:29.0")
        val original=pdf("Complete Blood Picture","29-Sep-2026",
            "https://www.nimsts.edu.in/HISInvestigationG5/report.pdf",
            "26CP438696")
        val matches=SourceMetadataReview.catalogCandidates(catalog,
            listOf(catalog,original))
        assertEquals(listOf(original),matches)
        assertTrue(catalog.url.isEmpty())
        assertTrue(catalog.labNumber.isEmpty())
        assertFalse(catalog == original)
    }

    @Test fun multiplePossibleSameDaySpecimensStaySeparateAndUnverified() {
        val catalog=rest("CBC+DIFF","2026-09-29")
        val a=pdf("CBP","2026-09-29",
            "https://www.nimsts.edu.in/HISInvestigationG5/a.pdf","LABA")
        val b=pdf("HEMOGRAM","2026-09-29",
            "https://www.nimsts.edu.in/HISInvestigationG5/b.pdf","LABB")
        val choices=SourceMetadataReview.catalogCandidates(catalog,listOf(a,b))
        assertEquals(2,choices.size)
        val rows=listOf(LabResult(
            id="obs",test="CBC+DIFF (HB, PCV,RBC INDICES)",
            parameter="Hemoglobin",date="2026-09-29",
            valueText="14.7",numericValue=14.7
        ))
        val candidates=ReportLinkResolver.candidates(rows,listOf(a,b),cr)
        assertNull(candidates.verified)
        assertEquals(2,candidates.candidates.size)
    }

    @Test fun metadataDoesNotMatchUnrelatedDateOrTest() {
        val catalog=rest("ELECTROLYTES (Na,K&Cl)","2026-09-29")
        val sameDateWrong=pdf("Liver Function Tests","2026-09-29",
            "https://www.nimsts.edu.in/HISInvestigationG5/lft.pdf")
        val farAway=pdf("Electrolytes","2026-07-01",
            "https://www.nimsts.edu.in/HISInvestigationG5/old.pdf")
        assertTrue(SourceMetadataReview.catalogCandidates(catalog,
            listOf(sameDateWrong,farAway)).isEmpty())
    }

    @Test fun sourceFileUrlAbsentCannotBeOfferedAsPdf() {
        val catalog=rest("CREATININE","2026-09-29")
        val noPdf=pdf("SERUM CREATININE","2026-09-29","")
        assertTrue(SourceMetadataReview.catalogCandidates(catalog,listOf(noPdf)).isEmpty())
    }
}
