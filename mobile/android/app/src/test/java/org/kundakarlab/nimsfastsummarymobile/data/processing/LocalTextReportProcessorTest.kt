package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.junit.Assert.*
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.domain.model.*
import org.kundakarlab.nimsfastsummarymobile.domain.processing.ProcessingResult
import kotlin.coroutines.*

class LocalTextReportProcessorTest {
    @Test fun parsesCbcText() {
        val result = runSuspend { LocalTextReportProcessor().parseReport(input("Hemoglobin 10.2 g/dL\nPlatelet Count 150000 /cumm")) }
        assertTrue(result is ProcessingResult.Success)
        val report = (result as ProcessingResult.Success).value
        assertTrue(report.labs.any { it.canonicalCode == "HB" && it.numericValue == 10.2 })
        assertTrue(report.labs.any { it.canonicalCode == "PLT" })
    }

    @Test fun rejectsPdfForLocalOnlyParser() {
        val result = runSuspend { LocalTextReportProcessor().parseReport(input("%PDF", "application/pdf")) }
        assertTrue(result is ProcessingResult.Unsupported)
    }

    @Test fun detectsLoginHtml() {
        val result = runSuspend { LocalTextReportProcessor().parseReport(input("<html>login password captcha</html>", "text/html")) }
        assertTrue(result is ProcessingResult.Failure)
    }

    @Test fun parsesPositiveCultureWithSusceptibility() {
        val text = "Blood culture\nSpecimen: Blood\nOrganism: Escherichia coli\nMeropenem Resistant\nESBL"
        val result = runSuspend { LocalTextReportProcessor().parseReport(input(text)) }
        assertTrue(result is ProcessingResult.Success)
        val culture = (result as ProcessingResult.Success).value.cultures.first()
        assertEquals(GrowthStatus.GROWTH_DETECTED, culture.growthStatus)
        assertTrue("ESBL" in culture.explicitResistanceMarkers)
        assertTrue(culture.susceptibility.any { it.interpretation.equals("Resistant", true) })
    }

    private fun input(text: String, type: String = "text/plain") = ReportInput("r1", "Test", "2026-01-01", "lab", type, text.toByteArray())
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var value: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context: CoroutineContext = EmptyCoroutineContext
        override fun resumeWith(result: Result<T>) { value = result }
    })
    return value!!.getOrThrow()
}
