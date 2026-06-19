package org.kundakarlab.nimsfastsummarymobile.data.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.kundakarlab.nimsfastsummarymobile.data.processing.LocalTextReportProcessor
import org.kundakarlab.nimsfastsummarymobile.data.processing.OnDeviceReportProcessor
import org.kundakarlab.nimsfastsummarymobile.domain.model.ReportInput
import org.kundakarlab.nimsfastsummarymobile.domain.processing.ProcessingResult
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PdfBoxAndroidTextExtractorInstrumentedTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun extractsSyntheticTextPdfParsesCbcRftAndCreatesNoCacheFiles() = runBlocking {
        val before = cacheNames()
        val bytes = textPdf(listOf("Hemoglobin 10.2 g/dL\nCreatinine : 1.3 mg/dL"))
        assertTrue(PdfBoxAndroidTextExtractor(context).extract(bytes) is PdfExtractionResult.Success)
        assertEquals(before, cacheNames())
        val parsed = OnDeviceReportProcessor(LocalTextReportProcessor(), PdfBoxAndroidTextExtractor(context)).parseReport(input("application/pdf", bytes))
        assertTrue(parsed is ProcessingResult.Success)
        val labs = (parsed as ProcessingResult.Success).value.labs.map { it.displayName }
        assertTrue(labs.contains("Hemoglobin"))
        assertTrue(labs.contains("Creatinine"))
    }

    @Test fun genericContentTypeWithPdfMagicIsProcessed() = runBlocking {
        assertTrue(OnDeviceReportProcessor(LocalTextReportProcessor(), PdfBoxAndroidTextExtractor(context)).parseReport(input("application/octet-stream", textPdf(listOf("Hemoglobin 11.1 g/dL")))) is ProcessingResult.Success)
    }

    @Test fun imageOnlyEncryptedCorruptOversizedAndTooManyPagesAreClassified() = runBlocking {
        assertTrue(PdfBoxAndroidTextExtractor(context).extract(blankPdf(1)) is PdfExtractionResult.ImageOnly)
        assertTrue(PdfBoxAndroidTextExtractor(context).extract(encryptedPdf()) is PdfExtractionResult.Encrypted)
        assertTrue(PdfBoxAndroidTextExtractor(context).extract("%PDF corrupt".toByteArray()) is PdfExtractionResult.Corrupt)
        assertTrue(PdfBoxAndroidTextExtractor(context).extract("%PDF".toByteArray() + ByteArray(PdfExtractionLimits.MAX_LOCAL_PDF_BYTES + 1)) is PdfExtractionResult.TooLarge)
        assertTrue(PdfBoxAndroidTextExtractor(context).extract(blankPdf(101)) is PdfExtractionResult.TooManyPages)
    }

    private fun input(contentType: String, bytes: ByteArray) = ReportInput("r", "Synthetic", "2026-01-01", "lab", contentType, bytes)
    private fun cacheNames(): Set<String> = context.cacheDir.list()?.toSet().orEmpty()
    private fun textPdf(pages: List<String>): ByteArray = PDDocument().use { doc ->
        pages.forEach { text ->
            val page = PDPage(PDRectangle.LETTER); doc.addPage(page)
            PDPageContentStream(doc, page).use { stream ->
                stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 10f); stream.newLineAtOffset(36f, 720f)
                text.lines().forEach { line -> stream.showText(line.take(10_000)); stream.newLineAtOffset(0f, -14f) }
                stream.endText()
            }
        }
        ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
    }
    private fun blankPdf(pages: Int): ByteArray = PDDocument().use { doc -> repeat(pages) { doc.addPage(PDPage(PDRectangle.LETTER)) }; ByteArrayOutputStream().also { doc.save(it) }.toByteArray() }
    private fun encryptedPdf(): ByteArray = PDDocument().use { doc -> doc.addPage(PDPage(PDRectangle.LETTER)); doc.protect(StandardProtectionPolicy("owner", "user", AccessPermission())); ByteArrayOutputStream().also { doc.save(it) }.toByteArray() }
}
