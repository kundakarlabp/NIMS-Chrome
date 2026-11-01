package org.kundakarlab.nimsresults

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.widget.Button
import android.widget.ImageView
import android.widget.FrameLayout
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
    private var restoredPage = 0
    private var pinchDetector: ScaleGestureDetector? = null
    private var swipeDetector: GestureDetector? = null

    // Receive swipe/pinch even if a nested ScrollView intercepts MOVE events.
    // Preserve normal taps, scrolling, and all accessible PDF controls.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val pinch=pinchDetector
        if (pinch != null) {
            pinch.onTouchEvent(event)
            val flung = if (!pinch.isInProgress)
                swipeDetector?.onTouchEvent(event) == true else false
            if (pinch.isInProgress || flung) return true
        }
        return super.dispatchTouchEvent(event)
    }
    private var sourceUrl = ""
    private var expectedCr = ""
    private lateinit var readTextButton: Button
    private lateinit var tableButton: Button
    private var cachedExtractedText = ""
    private var cachedGeometryText = ""
    private var renderingGeneration = 0
    @Volatile private var closing = false

    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var tempFile: File? = null
    private var bitmap: Bitmap? = null
    private var pageIndex = 0
    private val pdfOpenStartMs = android.os.SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restoredPage = savedInstanceState?.getInt("current_pdf_page", 0) ?: 0
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
            scaleType = ImageView.ScaleType.FIT_CENTER
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
        // Keep large documents in the existing PdfRenderer. Touch changes
        // the visible page, never loads all pages into bitmap memory at once.
        val pinch = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    zoom = (zoom * detector.scaleFactor).coerceIn(1f, 2.7f)
                    return true
                }
                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    render(pageIndex)
                }
            })
        val swipe = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent,
                    velocityX: Float, velocityY: Float
                ): Boolean {
                    val distance = e2.x - (e1?.x ?: e2.x)
                    if (zoom > 1.05f || kotlin.math.abs(distance) < dp(65) ||
                        kotlin.math.abs(distance) < kotlin.math.abs(e2.y-(e1?.y?:e2.y)) ||
                        kotlin.math.abs(velocityX) < 250f) return false
                    val total = renderer?.pageCount ?: return false
                    val nextPage = pageIndex + if (distance < 0) 1 else -1
                    if (nextPage !in 0 until total) return false
                    render(nextPage)
                    return true
                }
            })
        pinchDetector = pinch
        swipeDetector = swipe

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
        readTextButton = Button(this).apply {
            text = "Text"
            isEnabled = false
            contentDescription = "Read selectable text extracted from source PDF"
            setOnClickListener { extractText(false) }
        }
        tableButton = Button(this).apply {
            text = "Table"
            isEnabled = false
            contentDescription = "Review table-like entries from original PDF"
            setOnClickListener { extractText(true) }
        }
        saveButton = Button(this).apply {
            text = "Save"
            isEnabled = false
            setOnClickListener { savePdf() }
        }
        listOf(previous, next).forEach { control ->
            control.minimumWidth = 0
            control.minWidth = 0
            control.isAllCaps = false
            control.textSize = 12f
            control.setPadding(0, 0, 0, 0)
            nav.addView(control, LinearLayout.LayoutParams(0, dp(44), 1f))
        }
        top.addView(saveButton)
        val helper = TextView(this).apply {
            text = "‹ / › pages   ·   Swipe between pages   ·   Pinch to zoom"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(70, 92, 112))
            setPadding(0, dp(3), 0, dp(3))
        }
        // Preserve text/table extraction without occupying permanent screen space.
        val more = Button(this).apply {
            text = "⋮"
            contentDescription = "PDF text and table options"
            setOnClickListener {
                AlertDialog.Builder(this@ReportActivity)
                    .setItems(arrayOf("Selectable text", "Table text")) { _, choice ->
                        extractText(choice == 1)
                    }.show()
            }
        }
        top.addView(more)
        nav.addView(helper, LinearLayout.LayoutParams(0, dp(38), 3f))
        root.addView(nav)

        setContentView(root)
        download(url)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("current_pdf_page", pageIndex)
        super.onSaveInstanceState(outState)
    }

    private fun download(url: String) {
        executor.execute {
            val downloadStart = android.os.SystemClock.elapsedRealtime()
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
                    cached.setLastModified(System.currentTimeMillis())
                    PdfPerformanceTrace.record("download_ms", 0)
                    if (!closing) openOnWorker(cached)
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
                        PdfPerformanceTrace.record("download_ms",
                            android.os.SystemClock.elapsedRealtime() - downloadStart)
                        if (!closing) openOnWorker(saved)
                    } catch (error: Throwable) {
                        file.delete()
                        throw error
                    }
                }
            } catch (error: Throwable) {
                DiagnosticEvents.record(
                    if (error.message?.contains("PDF", true) == true) "PDF_NOT_VALID"
                    else "PDF_HTTP_FAILED"
                )
                runOnUiThread {
                    progress.visibility = View.GONE
                    status.text = error.message ?: "Could not open NIMS report."
                }
            }
        }
    }

    private fun pdfCacheFile(url: String): File {
        // Partition private PDF cache by requested patient, never by URL alone.
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest((expectedCr + "|" + url).toByteArray(Charsets.UTF_8))
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

    // PdfRenderer is confined to the one worker. UI thread only receives the
    // already-rendered Bitmap; zoom/navigation no longer blocks gestures.
    private fun openOnWorker(file: File) {
        val openStart = android.os.SystemClock.elapsedRealtime()
        try {
            // Parse the report's own CR and headings before displaying it.
            // Failure to extract text does not prevent a user visually
            // inspecting an image-based source PDF.
            if (file.length() <= 16L * 1024 * 1024) {
                // Read every PDF page; the first two pages are NOT a reliable
                // inventory for combined reports with multiple lab accessions.
                val (pages, aligned) = runCatching {
                    PDFBoxResourceLoader.init(applicationContext)
                    PDDocument.load(file, MemoryUsageSetting.setupMixed(4L * 1024 * 1024)).use { doc ->
                        val parsed = (1..doc.numberOfPages).map { page ->
                            PDFTextStripper().apply {
                                startPage = page
                                endPage = page
                                sortByPosition = true
                            }.getText(doc).take(40000)
                        }
                        val table = if (parsed.any {
                            it.contains("Nature Of",true) ||
                            it.contains("Coombs",true) ||
                            it.contains("URINE EXAMINATION",true)
                        }) PdfGeometryTable.read(doc) else ""
                        parsed to table
                    }
                }.getOrNull() ?: (emptyList<String>() to "")
                if (pages.all { it.isBlank() }) DiagnosticEvents.record("PDF_TEXT_MISSING")
                val crs = PdfSourceEvidence.patientCrs(pages)
                if (expectedCr.isNotBlank() && crs.any { it != expectedCr }) {
                    DiagnosticEvents.record("PDF_IDENTITY_MISMATCH")
                    runOnUiThread {
                        status.text = "Wrong patient CR in PDF — blocked"
                        progress.visibility = View.GONE
                        AlertDialog.Builder(this)
                            .setTitle("Report identity mismatch")
                            .setMessage("This PDF does not belong to the selected CR. Do not use this report.")
                            .setPositiveButton("Close") { _, _ -> finish() }
                            .show()
                    }
                    return
                }
                if (pages.any { it.isNotBlank() }) {
                    // One bounded PDF source cache is enough for navigation.
                    // Avoid retaining a second 160k-char copy in this Activity.
                    ReportTitleCache.learnPages(sourceUrl, pages, expectedCr,
                        digestFile(file), aligned)
                }
            }
            PdfPerformanceTrace.record("identity_and_extract_ms",
                android.os.SystemClock.elapsedRealtime() - openStart)
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(descriptor!!)
            if ((renderer?.pageCount ?: 0) == 0) {
                runOnUiThread { if (!closing) status.text = "NIMS report PDF is empty." }
                return
            }
            runOnUiThread {
                if (closing) return@runOnUiThread
                saveButton.isEnabled = true
                readTextButton.isEnabled = true
                tableButton.isEnabled = true
                render(restoredPage.coerceIn(0, (renderer?.pageCount ?: 1)-1))
            }
        } catch (_: Exception) {
            DiagnosticEvents.record("PDF_RENDER_FAILED")
            runOnUiThread { if (!closing) {
                progress.visibility = View.GONE
                status.text = "PDF cannot be displayed. Try another source report."
            } }
        } catch (_: OutOfMemoryError) {
            DiagnosticEvents.record("PDF_RENDER_FAILED")
            runOnUiThread { if (!closing) {
                progress.visibility = View.GONE
                status.text = "Not enough memory to display this PDF."
            } }
        }
    }

    private fun render(index: Int) {
        // A stale swipe or lifecycle callback must not leave controls disabled
        // and the spinner visible when there is no valid page to render.
        val availablePages = renderer?.pageCount ?: return
        if (closing || index !in 0 until availablePages) return
        val currentZoom = zoom
        val generation = ++renderingGeneration
        previous.isEnabled = false
        next.isEnabled = false
        progress.visibility = View.VISIBLE
        executor.execute {
            val renderStart = android.os.SystemClock.elapsedRealtime()
            val current = renderer ?: return@execute
            if (closing || generation != renderingGeneration ||
                index !in 0 until current.pageCount) return@execute
            var page: PdfRenderer.Page? = null
            var result: Bitmap? = null
            try {
                page = current.openPage(index)
                val viewport = horizontalScroll.width.takeIf { it > 0 }
                    ?: resources.displayMetrics.widthPixels
                val desiredWidth = (viewport * currentZoom)
                    .coerceIn(240f, 1500f).toDouble()
                val scale = minOf(desiredWidth / page.width, 2600.0 / page.height)
                val width = (page.width * scale).toInt().coerceAtLeast(1)
                val height = (page.height * scale).toInt().coerceAtLeast(1)
                result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                result.eraseColor(Color.WHITE)
                page.render(result, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                page = null
                if (closing || generation != renderingGeneration) {
                    result.recycle()
                    result = null
                    return@execute
                }
                val newBitmap = result
                result = null
                runOnUiThread {
                    if (closing || generation != renderingGeneration) {
                        newBitmap.recycle()
                        return@runOnUiThread
                    }
                    val switchingPage = index != pageIndex
                    image.setImageDrawable(null)
                    val old = bitmap
                    bitmap = newBitmap
                    PdfPerformanceTrace.bitmapAllocated(newBitmap.allocationByteCount)
                    pageIndex = index
                    // HorizontalScrollView extends FrameLayout and measures its child
                    // as FrameLayout.LayoutParams. Generic ViewGroup params crash
                    // in framework onMeasure with ClassCastException.
                    image.layoutParams = FrameLayout.LayoutParams(width, height)
                    image.setImageBitmap(newBitmap)
                    old?.recycle()
                    PdfPerformanceTrace.record("render_page_ms",
                        android.os.SystemClock.elapsedRealtime() - renderStart)
                    if (index == 0) PdfPerformanceTrace.record("full_pdf_open_ms",
                        android.os.SystemClock.elapsedRealtime() - pdfOpenStartMs)
                    progress.visibility = View.GONE
                    status.text = "PDF · page " + (index + 1) + "/" + current.pageCount
                    previous.isEnabled = index > 0
                    next.isEnabled = index + 1 < current.pageCount
                    if (switchingPage) {
                        verticalScroll.post { verticalScroll.scrollTo(0, 0) }
                        horizontalScroll.post { horizontalScroll.scrollTo(0, 0) }
                    }
                }
            } catch (_: OutOfMemoryError) {
                runOnUiThread { if (!closing) {
                    progress.visibility = View.GONE
                    status.text = "PDF is too large for available memory."
                } }
            } catch (_: Exception) {
                runOnUiThread { if (!closing) {
                    progress.visibility = View.GONE
                    status.text = "Unable to render this PDF page."
                } }
            } finally {
                runCatching { page?.close() }
                result?.recycle()
            }
        }
    }

    private fun extractText(tableView: Boolean) {
        val file = tempFile?.takeIf { it.isFile } ?: return
        if (file.length() > 6L * 1024 * 1024) {
            status.text = "PDF too large for on-device extraction. Use zoom."
            return
        }
        if (tableView && cachedGeometryText.isNotBlank()) {
            showSourceText(cachedGeometryText, true)
            return
        }
        if (!tableView && cachedExtractedText.isNotBlank()) {
            showSourceText(cachedExtractedText, false)
            return
        }
        readTextButton.isEnabled = false
        tableButton.isEnabled = false
        executor.execute {
            val extraction = if (cachedExtractedText.isNotBlank())
                Result.success(cachedExtractedText)
            else runCatching {
                PDFBoxResourceLoader.init(applicationContext)
                PDDocument.load(file, MemoryUsageSetting.setupMixed(
                    4L * 1024 * 1024)).use { doc ->
                    val pages = minOf(doc.numberOfPages, 8)
                    PDFTextStripper().apply {
                        startPage = 1
                        endPage = pages
                        sortByPosition = true
                    }.getText(doc).take(16000).trim()
                }
            }
            val geometry = if (tableView) runCatching {
                PDFBoxResourceLoader.init(applicationContext)
                PdfGeometryTable.read(file)
            }.getOrNull().orEmpty() else ""
            runOnUiThread {
                if (closing) return@runOnUiThread
                readTextButton.isEnabled = true
                tableButton.isEnabled = true
                val text = extraction.getOrNull().orEmpty()
                if (text.isBlank()) {
                    status.text = "No selectable PDF text. Inspect original report image."
                    return@runOnUiThread
                }
                val header = PdfDocumentHeaders.parse(text)
                if (expectedCr.isNotBlank() && header.crNo.isNotBlank() &&
                    header.crNo != expectedCr) {
                    status.text = "WARNING: PDF CR does not match the requested patient"
                    AlertDialog.Builder(this)
                        .setTitle("Wrong patient report — do not use")
                        .setMessage("The source PDF identifies another CR. No text was linked to this patient.")
                        .setPositiveButton("Close", null).show()
                    return@runOnUiThread
                }
                cachedExtractedText = text
                if (geometry.isNotBlank()) cachedGeometryText = geometry
                ReportTitleCache.learn(sourceUrl, text, expectedCr)
                showSourceText(if (tableView && geometry.isNotBlank()) geometry else text, tableView)
            }
        }
    }

    private fun showSourceText(text: String, tableView: Boolean) {
        val studyType = when {
            Regex("(?i)biofire|filmarray").containsMatchIn(text) -> "BioFire"
            Regex("(?i)blood\\s+group|antibody\\s+screen|abo\\s*/\\s*rh")
                .containsMatchIn(text) -> "BGT"
            else -> ""
        }
        val labelledCells = if (tableView) when(studyType) {
            "BioFire" -> SourceReportTables.biofire(text)
            "BGT" -> SourceReportTables.bloodGrouping(text)
            else -> emptyList()
        } else emptyList()
        val parsedTable = if (labelledCells.isEmpty()) "" else
            "Explicitly labelled " + studyType + " source rows (not independently verified)\n" +
                labelledCells.joinToString("\n") { it.label + "  |  " + it.value } +
                "\n\nOriginal extracted source text\n\n"
        val content = parsedTable + when {
            tableView && cachedGeometryText.isNotBlank() ->
                "PDF coordinate-aligned layout. Confirm against the original page.\n\n" + text
            tableView -> ReportTableReader.present(text)
            else -> text
        }
        val editable = TextView(this).apply {
            setTextIsSelectable(true)
            setTextColor(Color.rgb(20, 40, 58))
            textSize = 12f
            setPadding(dp(14), dp(8), dp(14), dp(8))
            if (tableView) typeface = android.graphics.Typeface.MONOSPACE
            setText(content)
        }
        val scroll = ScrollView(this).apply { addView(editable) }
        AlertDialog.Builder(this)
            .setTitle(if (tableView) "Table review · unverified layout" else "Source PDF · selectable text")
            .setMessage("Extracted reading order may differ from the report. Confirm values and units in the original PDF.")
            .setView(scroll)
            .setPositiveButton("Close", null).show()
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

    // Streaming digest prevents a second multi-megabyte copy of the
    // PDF in memory while PdfRenderer is opening a page.
    private fun digestFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val chunk = ByteArray(8192)
            while (true) {
                val read = input.read(chunk)
                if (read < 0) break
                md.update(chunk, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isPdf(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == '%'.code.toByte() &&
            bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() &&
            bytes[3] == 'F'.code.toByte()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        closing = true
        renderingGeneration++
        // PdfRenderer must close after its queued worker tasks, never while
        // a page is rendering. Bitmap ownership remains on the UI thread.
        runCatching {
            executor.execute {
                runCatching { renderer?.close() }
                runCatching { descriptor?.close() }
                tempFile?.takeIf {
                    it.name.startsWith("nims-report-") || it.name.startsWith("nims-work-")
                }?.delete()
            }
        }
        executor.shutdown()
        image.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = null
        PdfPerformanceTrace.bitmapAllocated(0)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "report_url"
        const val EXTRA_EXPECTED_CR = "expected_patient_cr"
        private const val REQUEST_SAVE_PDF = 231
        private const val MAX_PDF_BYTES = 20L * 1024 * 1024
        private const val MAX_CACHE_FILES = 16
        private const val CACHE_TTL_MS = 30L * 60L * 1000L
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
