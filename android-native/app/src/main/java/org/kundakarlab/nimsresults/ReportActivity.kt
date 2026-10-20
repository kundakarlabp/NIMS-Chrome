package org.kundakarlab.nimsresults

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.Button
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReportActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var image: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var previous: Button
    private lateinit var next: Button
    private lateinit var saveButton: Button
    private lateinit var verticalScroll: ScrollView
    private lateinit var horizontalScroll: HorizontalScrollView
    private var zoom = 1f
    private var sourceUrl = ""
    private var expectedCr = ""
    private lateinit var readTextButton: Button

    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var tempFile: File? = null
    private var bitmap: Bitmap? = null
    private var pageIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        sourceUrl = url
        expectedCr = intent.getStringExtra(EXTRA_EXPECTED_CR).orEmpty()
        if (ReportPayloadParser.safeReportUrl(url).isBlank()) {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        // Android 15+ draws edge-to-edge. Do not allow Back and Save below
        // the device status bar or navigation gesture strip.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.rgb(12, 45, 76))
        }
        val back = Button(this).apply {
            text = "‹"
            contentDescription = "Back to NIMS results"
            setOnClickListener { finish() }
        }
        status = TextView(this).apply {
            text = "Opening NIMS source report…"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(8), 0, 0, 0)
        }
        top.addView(back)
        top.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(top)

        progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)))

        image = ImageView(this).apply {
            setBackgroundColor(Color.WHITE)
            adjustViewBounds = false
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = "NIMS PDF page"
        }
        // PDF pages are rendered at readable, page-width scale and scroll vertically.
        // The inner horizontal scroll supports zoomed pages wider than the device.
        horizontalScroll = HorizontalScrollView(this).apply {
            isFillViewport = false
            setBackgroundColor(Color.rgb(245, 247, 250))
            addView(image)
        }
        verticalScroll = ScrollView(this).apply {
            isFillViewport = false
            setBackgroundColor(Color.rgb(245, 247, 250))
            addView(horizontalScroll, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(verticalScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        previous = Button(this).apply {
            text = "‹"
            contentDescription = "Previous PDF page"
            isEnabled = false
            setOnClickListener { render(pageIndex - 1) }
        }
        next = Button(this).apply {
            text = "›"
            contentDescription = "Next PDF page"
            isEnabled = false
            setOnClickListener { render(pageIndex + 1) }
        }
        val zoomOut = Button(this).apply {
            text = "−"
            contentDescription = "Zoom out"
            setOnClickListener { zoom = (zoom / 1.5f).coerceAtLeast(1f); render(pageIndex) }
        }
        val zoomIn = Button(this).apply {
            text = "+"
            contentDescription = "Zoom in"
            setOnClickListener { zoom = (zoom * 1.5f).coerceAtMost(2.5f); render(pageIndex) }
        }
        val fit = Button(this).apply {
            text = "Fit"
            setOnClickListener { zoom = 1f; render(pageIndex) }
        }
        readTextButton = Button(this).apply {
            text = "Text"
            isEnabled = false
            contentDescription = "Read selectable text extracted from source PDF"
            setOnClickListener { extractText() }
        }
        saveButton = Button(this).apply {
            text = "Save"
            isEnabled = false
            setOnClickListener { savePdf() }
        }
        listOf(previous, next, zoomOut, zoomIn, fit, readTextButton).forEach { control ->
            control.minimumWidth = 0
            control.minWidth = 0
            control.isAllCaps = false
            control.textSize = 12f
            control.setPadding(0, 0, 0, 0)
            nav.addView(control, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        top.addView(saveButton)
        root.addView(nav)

        setContentView(root)
        download(url)
    }

    private fun download(url: String) {
        executor.execute {
            try {
                val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
                val cached = pdfCacheFile(url)
                // Cache is app-private, short-lived and usable only while a
                // NIMS cookie is present; never reuse after explicit logout.
                if (cookie.isNotBlank() && cached.isFile &&
                    cached.length() in 4..MAX_PDF_BYTES &&
                    System.currentTimeMillis() - cached.lastModified() in 0..CACHE_TTL_MS &&
                    cached.inputStream().use { stream ->
                        val header = ByteArray(4)
                        stream.read(header) == 4 && isPdf(header)
                    }
                ) {
                    tempFile = cached
                    runOnUiThread { if (!isFinishing && !isDestroyed) open(cached) }
                    return@execute
                }
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", NimsUrls.DESKTOP_UA)
                    .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
                    .build()

                SHARED_CLIENT.newCall(request).execute().use { response ->
                    response.headers("Set-Cookie").forEach {
                        runCatching { CookieManager.getInstance().setCookie(response.request.url.toString(), it) }
                    }
                    CookieManager.getInstance().flush()

                    if (!response.isSuccessful) error("NIMS report returned HTTP " + response.code)
                    val finalUrl = response.request.url
                    if (finalUrl.scheme != "https" || finalUrl.host !in NimsUrls.HOSTS) {
                        error("NIMS redirected this report outside its verified host.")
                    }
                    val body = response.body ?: error("NIMS report response is empty.")
                    if (body.contentLength() > MAX_PDF_BYTES) {
                        error("Report exceeds mobile PDF viewer size limit.")
                    }
                    val file = File.createTempFile("nims-report-", ".pdf", cacheDir)
                    try {
                        var total = 0L
                        body.byteStream().use { input ->
                            FileOutputStream(file).use { output ->
                                val block = ByteArray(16 * 1024)
                                while (true) {
                                    val count = input.read(block)
                                    if (count < 0) break
                                    total += count
                                    if (total > MAX_PDF_BYTES) {
                                        throw IOException("Report exceeds mobile PDF viewer size limit.")
                                    }
                                    output.write(block, 0, count)
                                }
                            }
                        }
                        val signature = ByteArray(4)
                        val firstBytes = ByteArray(2048)
                        val previewSize = file.inputStream().use { input ->
                            val read = input.read(firstBytes)
                            if (read >= 4) System.arraycopy(firstBytes, 0, signature, 0, 4)
                            read
                        }
                        if (!isPdf(signature)) {
                            val preview = if (previewSize > 0) String(firstBytes, 0, previewSize) else ""
                            if (Regex("(?i)login|captcha|session expired|please login again").containsMatchIn(preview)) {
                                error("NIMS session expired. Log in to open this report.")
                            }
                            error("NIMS did not return PDF data.")
                        }
                        // Store a bounded number of verified PDFs for rapid repeat
                        // viewing without keeping raw report URLs in filenames.
                        val destination = pdfCacheFile(url)
                        if (destination.exists()) destination.delete()
                        val saved = if (file.renameTo(destination)) destination else file
                        tempFile = saved
                        prunePdfCache()
                        runOnUiThread { if (!isFinishing && !isDestroyed) open(saved) }
                    } catch (error: Throwable) {
                        file.delete()
                        throw error
                    }
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    status.text = error.message ?: "Could not open NIMS report."
                }
            }
        }
    }

    private fun pdfCacheFile(url: String): File {
        val bytes = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        val hash = bytes.joinToString("") { "%02x".format(it) }
        return File(cacheDir, "nims-verified-" + hash + ".pdf")
    }

    private fun prunePdfCache() {
        val files = cacheDir.listFiles { file ->
            file.name.startsWith("nims-verified-") && file.name.endsWith(".pdf")
        }?.sortedByDescending { it.lastModified() }.orEmpty()
        val now = System.currentTimeMillis()
        files.forEachIndexed { index, file ->
            if (index >= MAX_CACHE_FILES || now - file.lastModified() > CACHE_TTL_MS) {
                if (file != tempFile) file.delete()
            }
        }
    }

    private fun open(file: File) {
        progress.visibility = View.GONE
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor!!)
            if ((renderer?.pageCount ?: 0) == 0) {
                status.text = "NIMS report PDF is empty."
                return
            }
            saveButton.isEnabled = true
            readTextButton.isEnabled = true
            render(0)
        } catch (_: Exception) {
            status.text = "PDF cannot be displayed. Try another source report."
        } catch (_: OutOfMemoryError) {
            status.text = "Not enough memory to display this PDF."
        }
    }

    private fun render(index: Int) {
        val current = renderer ?: return
        if (index !in 0 until current.pageCount) return
        var page: PdfRenderer.Page? = null
        var nextBitmap: Bitmap? = null
        try {
            val switchingPage = index != pageIndex
            page = current.openPage(index)
            // Limit image memory on low-RAM phones; never allocate a full-size
            // high-resolution PDF bitmap on the UI thread.
            val desiredWidth = (resources.displayMetrics.widthPixels * zoom)
                .coerceIn(320f, 1200f).toDouble()
            val scale = minOf(desiredWidth / page.width, 2400.0 / page.height)
            val width = (page.width * scale).toInt().coerceAtLeast(1)
            val height = (page.height * scale).toInt().coerceAtLeast(1)
            nextBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            nextBitmap.eraseColor(Color.WHITE)
            page.render(nextBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            page = null
            image.setImageDrawable(null)
            bitmap?.recycle()
            bitmap = nextBitmap
            nextBitmap = null
            pageIndex = index
            image.layoutParams = android.widget.FrameLayout.LayoutParams(width, height)
            image.setImageBitmap(bitmap)
            status.text = "PDF · page " + (index + 1) + "/" + current.pageCount
            previous.isEnabled = index > 0
            next.isEnabled = index + 1 < current.pageCount
            if (switchingPage) {
                verticalScroll.post { verticalScroll.scrollTo(0, 0) }
                horizontalScroll.post { horizontalScroll.scrollTo(0, 0) }
            }
        } catch (_: OutOfMemoryError) {
            status.text = "PDF is too large for available memory."
        } catch (_: Exception) {
            status.text = "Unable to render this PDF page."
        } finally {
            runCatching { page?.close() }
            nextBitmap?.recycle()
        }
    }

    private fun extractText() {
        val file = tempFile?.takeIf { it.isFile } ?: return
        if (file.length() > 6L * 1024 * 1024) {
            status.text = "PDF too large for on-device text extraction. Use zoom."
            return
        }
        readTextButton.isEnabled = false
        executor.execute {
            val extraction = runCatching {
                PDFBoxResourceLoader.init(applicationContext)
                PDDocument.load(file).use { doc ->
                    val pages = minOf(doc.numberOfPages, 8)
                    val stripper = PDFTextStripper().apply {
                        startPage = 1
                        endPage = pages
                        sortByPosition = true
                    }
                    stripper.getText(doc).take(16000).trim()
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                readTextButton.isEnabled = true
                val text = extraction.getOrNull().orEmpty()
                if (text.isBlank()) {
                    status.text = "No selectable PDF text. Use the original report image."
                } else {
                    val header = PdfDocumentHeaders.parse(text)
                    if (expectedCr.isNotBlank() && header.crNo.isNotBlank() &&
                        header.crNo != expectedCr) {
                        status.text = "WARNING: PDF patient CR does not match selected patient"
                        AlertDialog.Builder(this)
                            .setTitle("Wrong patient report — do not use")
                            .setMessage("The PDF text contains a different CR number. This report was not linked to the selected patient.")
                            .setPositiveButton("Close", null)
                            .show()
                        return@runOnUiThread
                    }
                    ReportTitleCache.learn(sourceUrl, text, expectedCr)
                    AlertDialog.Builder(this)
                        .setTitle("NIMS source report · extracted text")
                        .setMessage("Extraction may reorder PDF tables or omit symbols. Verify against the original page.\n\n" + text)
                        .setPositiveButton("Close", null)
                        .show()
                }
            }
        }
    }

    private fun savePdf() {
        if (tempFile?.isFile != true) return
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/pdf"
            putExtra(Intent.EXTRA_TITLE, "NIMS-report.pdf")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_SAVE_PDF)
    }

    @Deprecated("Android Activity result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_SAVE_PDF || resultCode != RESULT_OK) return
        val target = data?.data ?: return
        val source = tempFile?.takeIf { it.isFile } ?: return
        executor.execute {
            val saved = runCatching {
                contentResolver.openOutputStream(target)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Destination is not writable")
            }.isSuccess
            runOnUiThread {
                Toast.makeText(this, if (saved) "PDF saved" else "Could not save PDF", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isPdf(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == '%'.code.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        executor.shutdownNow()
        bitmap?.recycle()
        renderer?.close()
        descriptor?.close()
        if (tempFile?.name?.startsWith("nims-report-") == true ||
            tempFile?.name?.startsWith("nims-work-") == true) tempFile?.delete()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "report_url"
        const val EXTRA_EXPECTED_CR = "expected_patient_cr"
        private const val REQUEST_SAVE_PDF = 231
        private const val MAX_PDF_BYTES = 20L * 1024 * 1024
        private const val MAX_CACHE_FILES = 8
        private const val CACHE_TTL_MS = 10L * 60L * 1000L
        private val SHARED_CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}
