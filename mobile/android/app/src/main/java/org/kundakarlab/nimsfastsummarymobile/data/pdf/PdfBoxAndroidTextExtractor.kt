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

    override suspend fun extract(pdfBytes: ByteArray, onProgress: ((Int, Int) -> Unit)?): PdfExtractionResult = mutex.withPermit {
        if (pdfBytes.size > PdfExtractionLimits.MAX_LOCAL_PDF_BYTES) {
            return@withPermit PdfExtractionResult.TooLarge(pdfBytes.size, PdfExtractionLimits.MAX_LOCAL_PDF_BYTES)
        }
        if (!pdfBytes.startsWithPdfMagic()) return@withPermit PdfExtractionResult.Corrupt("This PDF report could not be read on-device.")
        withContext(Dispatchers.IO) {
            init(appContext)
            try {
                PDDocument.load(pdfBytes).use { document ->
                    if (document.isEncrypted) return@withContext PdfExtractionResult.Encrypted
                    val pages = document.numberOfPages
                    if (pages <= 0) return@withContext PdfExtractionResult.ImageOnly(0)
                    if (pages > PdfExtractionLimits.MAX_PDF_PAGES) return@withContext PdfExtractionResult.TooManyPages(pages, PdfExtractionLimits.MAX_PDF_PAGES)
                    val out = StringBuilder()
                    val warnings = mutableListOf<String>()
                    for (page in 1..pages) {
                        coroutineContext.ensureActive()
                        val stripper = PDFTextStripper().apply {
                            sortByPosition = true
                            startPage = page
                            endPage = page
                        }
                        val pageText = stripper.getText(document).orEmpty()
                        if (out.isNotEmpty()) out.append("\n\n--- Page ").append(page).append(" of ").append(pages).append(" ---\n")
                        val remaining = PdfExtractionLimits.MAX_EXTRACTED_TEXT_CHARS - out.length
                        if (remaining <= 0) {
                            warnings += "Extracted PDF text was truncated at the local safety limit."
                            break
                        }
                        out.append(pageText.take(remaining))
                        onProgress?.invoke(page, pages)
                    }
                    val normalized = PdfExtractedTextNormalizer.normalize(out.toString())
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
        private val mutex = Semaphore(1)
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
