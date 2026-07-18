package org.kundakarlab.nimsfastsummarymobile.data.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext

class PdfBoxAndroidTextExtractor(context: Context) : PdfTextExtractor {
    private val appContext = context.applicationContext

    override suspend fun extract(pdfBytes: ByteArray, onProgress: ((Int, Int) -> Unit)?): PdfExtractionResult = extractionSlots.withPermit {
        if (pdfBytes.size > PdfExtractionLimits.MAX_LOCAL_PDF_BYTES) return@withPermit PdfExtractionResult.TooLarge(pdfBytes.size, PdfExtractionLimits.MAX_LOCAL_PDF_BYTES)
        if (!pdfBytes.startsWithPdfMagic()) return@withPermit PdfExtractionResult.Corrupt("This PDF report could not be read on-device.")
        withContext(Dispatchers.IO) {
            init(appContext)
            try {
                PDDocument.load(pdfBytes).use { document ->
                    if (document.isEncrypted) return@withContext PdfExtractionResult.Encrypted
                    val pages = document.numberOfPages
                    if (pages <= 0) return@withContext PdfExtractionResult.ImageOnly(0)
                    if (pages > PdfExtractionLimits.MAX_PDF_PAGES) return@withContext PdfExtractionResult.TooManyPages(pages, PdfExtractionLimits.MAX_PDF_PAGES)
                    coroutineContext.ensureActive()
                    val text = PDFTextStripper().apply {
                        sortByPosition = true
                        startPage = 1
                        endPage = pages
                    }.getText(document).orEmpty()
                    onProgress?.invoke(pages, pages)
                    val truncated = text.length > PdfExtractionLimits.MAX_EXTRACTED_TEXT_CHARS
                    val normalized = PdfExtractedTextNormalizer.normalize(text.take(PdfExtractionLimits.MAX_EXTRACTED_TEXT_CHARS))
                    val warnings = if (truncated) listOf("Extracted PDF text was truncated at the local safety limit.") else emptyList()
                    if (normalized.length < PdfExtractionLimits.MIN_USEFUL_TEXT_CHARS) PdfExtractionResult.ImageOnly(pages)
                    else PdfExtractionResult.Success(normalized, pages, warnings)
                }
            } catch (_: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                PdfExtractionResult.Encrypted
            } catch (_: IOException) {
                PdfExtractionResult.Corrupt("This PDF report could not be read on-device.")
            } catch (_: IllegalArgumentException) {
                PdfExtractionResult.Corrupt("This PDF report could not be read on-device.")
            }
        }
    }

    companion object {
        // The bulk pipeline already limits concurrent report work. Two extraction
        // slots remove the previous global serialization while keeping peak memory
        // bounded on mid-range Android devices.
        private val extractionSlots = Semaphore(2)
        @Volatile private var initialized = false
        private fun init(context: Context) {
            if (!initialized) synchronized(this) {
                if (!initialized) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    initialized = true
                }
            }
        }
    }
}

private fun ByteArray.startsWithPdfMagic(): Boolean = size >= 4 && this[0] == '%'.code.toByte() && this[1] == 'P'.code.toByte() && this[2] == 'D'.code.toByte() && this[3] == 'F'.code.toByte()
