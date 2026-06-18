package org.kundakarlab.nimsfastsummarymobile.data.pdf

interface PdfTextExtractor {
    suspend fun extract(
        pdfBytes: ByteArray,
        onProgress: ((completedPages: Int, totalPages: Int) -> Unit)? = null
    ): PdfExtractionResult
}

sealed interface PdfExtractionResult {
    data class Success(val text: String, val pageCount: Int, val warnings: List<String> = emptyList()) : PdfExtractionResult
    data class ImageOnly(val pageCount: Int) : PdfExtractionResult
    data object Encrypted : PdfExtractionResult
    data class TooLarge(val actualBytes: Int, val maximumBytes: Int) : PdfExtractionResult
    data class TooManyPages(val pageCount: Int, val maximumPages: Int) : PdfExtractionResult
    data class Corrupt(val userMessage: String) : PdfExtractionResult
    data class Failure(val userMessage: String, val technicalCode: String) : PdfExtractionResult
}

object PdfExtractionLimits {
    const val MAX_LOCAL_PDF_BYTES = 25 * 1024 * 1024
    const val MAX_PDF_PAGES = 100
    const val MAX_EXTRACTED_TEXT_CHARS = 2_000_000
    const val MIN_USEFUL_TEXT_CHARS = 20
}
