package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.junit.Assert.*
import org.junit.Test
import org.kundakarlab.nimsfastsummarymobile.data.pdf.PdfExtractionResult
import org.kundakarlab.nimsfastsummarymobile.data.pdf.PdfTextExtractor
import org.kundakarlab.nimsfastsummarymobile.domain.model.ReportInput
import org.kundakarlab.nimsfastsummarymobile.domain.processing.ProcessingResult
import kotlin.coroutines.*

class OnDeviceReportProcessorTest {
    @Test fun pdfSuccessParsesExtractedText() {
        val processor = OnDeviceReportProcessor(LocalTextReportProcessor(), FakePdf(PdfExtractionResult.Success("Hemoglobin 10.2 g/dL", 1)))
        val result = runSuspend { processor.parseReport(pdfInput()) }
        assertTrue(result is ProcessingResult.Success)
        val report = (result as ProcessingResult.Success).value
        assertEquals("On-device PDF", report.processorName)
        assertTrue(report.labs.any { it.displayName == "Hemoglobin" })
    }

    @Test fun imageOnlyPdfIsUnsupported() {
        val result = runSuspend { OnDeviceReportProcessor(LocalTextReportProcessor(), FakePdf(PdfExtractionResult.ImageOnly(1))).parseReport(pdfInput()) }
        assertTrue(result is ProcessingResult.Unsupported)
        assertTrue((result as ProcessingResult.Unsupported).reason.contains("OCR is not enabled"))
    }

    @Test fun encryptedPdfIsUnsupported() {
        val result = runSuspend { OnDeviceReportProcessor(LocalTextReportProcessor(), FakePdf(PdfExtractionResult.Encrypted)).parseReport(pdfInput()) }
        assertTrue(result is ProcessingResult.Unsupported)
        assertTrue((result as ProcessingResult.Unsupported).reason.contains("password-protected"))
    }

    @Test fun corruptPdfIsFailure() {
        val result = runSuspend { OnDeviceReportProcessor(LocalTextReportProcessor(), FakePdf(PdfExtractionResult.Corrupt("This PDF report could not be read on-device."))).parseReport(pdfInput()) }
        assertTrue(result is ProcessingResult.Failure)
        assertEquals("LOCAL_PDF_CORRUPT", (result as ProcessingResult.Failure).technicalCode)
    }

    private class FakePdf(private val result: PdfExtractionResult) : PdfTextExtractor {
        override suspend fun extract(pdfBytes: ByteArray, onProgress: ((Int, Int) -> Unit)?): PdfExtractionResult = result
    }
    private fun pdfInput() = ReportInput("r", "PDF", "2026-01-01", "lab", "application/pdf", "%PDF-1.4".toByteArray())
}

private fun <T> runSuspend(block: suspend () -> T): T { var value: Result<T>? = null; block.startCoroutine(object : Continuation<T> { override val context = EmptyCoroutineContext; override fun resumeWith(result: Result<T>) { value = result } }); return value!!.getOrThrow() }
