package org.kundakarlab.nimsresults

import android.content.Context
import android.webkit.CookieManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Progressive, local-only metadata inspection of a patient's signed PDF
 * inventory. This never transmits PDF contents to an external service and
 * never fills an unproven structured-result accession.
 */
class PdfSourceEnricher(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(28, TimeUnit.SECONDS)
        .followRedirects(true).build()

    /** Returns true only for a readable source PDF explicitly proving the CR. */
    fun inspect(report: ReportRef, expectedCr: String, stillSelected: () -> Boolean): Boolean {
        val url = report.url
        if (!stillSelected() || ReportPayloadParser.safeReportUrl(url).isBlank()) return false
        if (ReportTitleCache.sections(url).isNotEmpty()) return true
        val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
        if (cookie.isBlank()) return false
        val request = Request.Builder().url(url)
            .header("Cookie", cookie)
            .header("User-Agent", NimsUrls.DESKTOP_UA)
            .build()
        val tmp = File.createTempFile("nims-source-index-", ".pdf", context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful || !stillSelected()) return false
                if (response.request.url.scheme != "https" ||
                    response.request.url.host !in NimsUrls.HOSTS) return false
                val body = response.body ?: return false
                if (body.contentLength() > MAX_PDF_BYTES) return false
                var count = 0L
                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            if (!stillSelected() || Thread.currentThread().isInterrupted)
                                return false
                            val n = input.read(buffer)
                            if (n < 0) break
                            count += n
                            if (count > MAX_PDF_BYTES) return false
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                        }
                    }
                }
            }
            if (tmp.length() < 4L || tmp.inputStream().use {
                val bytes = ByteArray(4)
                it.read(bytes) != 4 || !bytes.contentEquals(
                    byteArrayOf(37, 80, 68, 70) /* %PDF */)
            }) return false
            PDFBoxResourceLoader.init(context.applicationContext)
            val (pages, positioned) =
                PDDocument.load(tmp, MemoryUsageSetting.setupMixed(4L * 1024 * 1024)).use { pdf ->
                    val extracted = (1..pdf.numberOfPages).map { p ->
                        if (!stillSelected() || Thread.currentThread().isInterrupted)
                            return false
                        PDFTextStripper().apply {
                            startPage = p
                            endPage = p
                            sortByPosition = true
                        }.getText(pdf).take(40000)
                    }
                    // Only selected small NIMS PDFs are inspected. Coordinate
                    // alignment is essential for fluid chemistry and DAT/IAT
                    // without guessing what adjacent text refers to.
                    val table = if (extracted.any {
                        it.contains("Nature Of",true) ||
                        it.contains("Coombs",true) ||
                        it.contains("URINE EXAMINATION",true)
                    }) PdfGeometryTable.read(pdf) else ""
                    extracted to table
                }
            if (!stillSelected() || pages.all { it.isBlank() }) return false
            return ReportTitleCache.learnPages(url, pages, expectedCr,
                digest.digest().joinToString("") { "%02x".format(it) },positioned)
        } catch (_: Exception) {
            // An inaccessible/bitmap-only PDF stays a source card that the
            // clinician can open; never synthesize missing source metadata.
            return false
        } finally {
            tmp.delete()
        }
    }

    companion object {
        private const val MAX_PDF_BYTES = 16L * 1024 * 1024
    }
}
