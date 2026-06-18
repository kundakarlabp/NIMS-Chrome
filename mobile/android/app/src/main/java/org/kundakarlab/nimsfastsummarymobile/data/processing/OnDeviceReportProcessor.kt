package org.kundakarlab.nimsfastsummarymobile.data.processing

import org.kundakarlab.nimsfastsummarymobile.data.pdf.PdfExtractionResult
import org.kundakarlab.nimsfastsummarymobile.data.pdf.PdfTextExtractor
import org.kundakarlab.nimsfastsummarymobile.domain.model.*
import org.kundakarlab.nimsfastsummarymobile.domain.processing.ProcessingResult
import org.kundakarlab.nimsfastsummarymobile.domain.processing.ReportProcessor
import java.util.concurrent.CancellationException

class OnDeviceReportProcessor(
    private val textProcessor: LocalTextReportProcessor,
    private val pdfExtractor: PdfTextExtractor,
    private val onPdfProgress: ((completedPages: Int, totalPages: Int) -> Unit)? = null
) : ReportProcessor {
    override val name = "On-device"
    override val capabilities = setOf(ProcessingCapability.HTML, ProcessingCapability.PLAIN_TEXT, ProcessingCapability.PDF, ProcessingCapability.LABS, ProcessingCapability.CULTURES, ProcessingCapability.SUMMARY)

    override suspend fun parseReport(input: ReportInput): ProcessingResult<ParsedReport> {
        if (!input.isPdf()) return textProcessor.parseReport(input)
        return try {
            when (val extracted = pdfExtractor.extract(input.bytes, onPdfProgress)) {
                is PdfExtractionResult.Success -> {
                    val textInput = input.copy(contentType = "text/plain; charset=utf-8", bytes = extracted.text.toByteArray(Charsets.UTF_8))
                    when (val parsed = textProcessor.parseReport(textInput)) {
                        is ProcessingResult.Success -> {
                            val warnings = parsed.warnings + extracted.warnings + "Processed from PDF on-device."
                            ProcessingResult.Success(parsed.value.copy(warnings = parsed.value.warnings + warnings, processorName = "On-device PDF"), "On-device PDF", warnings)
                        }
                        is ProcessingResult.Unsupported -> ProcessingResult.Unsupported(parsed.reason)
                        is ProcessingResult.Failure -> parsed
                    }
                }
                is PdfExtractionResult.ImageOnly -> ProcessingResult.Unsupported("This PDF appears to contain images without extractable text. OCR is not enabled. Open the source report in NIMS.")
                PdfExtractionResult.Encrypted -> ProcessingResult.Unsupported("This PDF is password-protected and cannot be processed on-device.")
                is PdfExtractionResult.TooLarge -> ProcessingResult.Failure("This PDF is too large for on-device processing. Open the source report in NIMS.", "LOCAL_PDF_TOO_LARGE", false)
                is PdfExtractionResult.TooManyPages -> ProcessingResult.Failure("This PDF has too many pages for on-device processing. Open the source report in NIMS.", "LOCAL_PDF_TOO_MANY_PAGES", false)
                is PdfExtractionResult.Corrupt -> ProcessingResult.Failure(extracted.userMessage, "LOCAL_PDF_CORRUPT", false)
                is PdfExtractionResult.Failure -> ProcessingResult.Failure(extracted.userMessage, extracted.technicalCode, false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        }
    }

    override suspend fun summarize(reports: List<ParsedReport>, mode: SummaryMode): ProcessingResult<ProcessingSummary> = textProcessor.summarize(reports, mode)
}

private fun ReportInput.isPdf(): Boolean = contentType.contains("pdf", true) || (bytes.size >= 4 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte())
