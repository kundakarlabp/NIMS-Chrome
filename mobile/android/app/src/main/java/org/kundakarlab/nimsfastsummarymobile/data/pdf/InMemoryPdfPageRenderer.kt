package org.kundakarlab.nimsfastsummarymobile.data.pdf

import android.content.Context
import android.graphics.Bitmap
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class RenderedPdfPage(
    val bitmap: Bitmap,
    val pageIndex: Int,
    val pageCount: Int
)

/** Renders one source-PDF page at a time without writing report bytes to disk. */
class InMemoryPdfPageRenderer(context: Context) {
    private val appContext = context.applicationContext

    suspend fun render(pdfBytes: ByteArray, pageIndex: Int): RenderedPdfPage = withContext(Dispatchers.IO) {
        require(pdfBytes.size <= PdfExtractionLimits.MAX_LOCAL_PDF_BYTES) { "Source PDF exceeded the on-device size limit." }
        require(pdfBytes.size >= 4 && pdfBytes.copyOfRange(0, 4).contentEquals("%PDF".toByteArray())) {
            "Source response is not a PDF."
        }
        init(appContext)
        PDDocument.load(pdfBytes).use { document ->
            val pageCount = document.numberOfPages
            require(pageCount in 1..PdfExtractionLimits.MAX_PDF_PAGES) { "Source PDF page count is unsupported." }
            require(pageIndex in 0 until pageCount) { "Source PDF page is unavailable." }
            // 112 DPI remains easily readable on a phone while creating about
            // 28% fewer pixels than 132 DPI. This materially reduces first-page
            // latency and memory pressure without persisting source bytes.
            val bitmap = PDFRenderer(document).renderImageWithDPI(pageIndex, 112f, ImageType.RGB)
            RenderedPdfPage(bitmap, pageIndex, pageCount)
        }
    }

    companion object {
        @Volatile private var initialized = false

        private fun init(context: Context) {
            if (!initialized) synchronized(this) {
                if (!initialized) {
                    PDFBoxResourceLoader.init(context)
                    initialized = true
                }
            }
        }
    }
}
