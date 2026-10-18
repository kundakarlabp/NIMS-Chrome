package org.kundakarlab.nimsresults

import android.app.Activity
import android.content.Intent
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

    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var tempFile: File? = null
    private var bitmap: Bitmap? = null
    private var pageIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
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
        saveButton = Button(this).apply {
            text = "Save"
            isEnabled = false
            setOnClickListener { savePdf() }
        }
        nav.addView(previous)
        nav.addView(next)
        nav.addView(zoomOut)
        nav.addView(zoomIn)
        nav.addView(fit)
        top.addView(saveButton)
        root.addView(nav)

        setContentView(root)
        download(url)
    }

    private fun download(url: String) {
        executor.execute {
            try {
                val cookie = CookieManager.getInstance().getCookie(url).orEmpty()
                val client = OkHttpClient.Builder()
                    .connectTimeout(12, TimeUnit.SECONDS)
                    .readTimeout(45, TimeUnit.SECONDS)
                    .callTimeout(60, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", NimsUrls.DESKTOP_UA)
                    .apply { if (cookie.isNotBlank()) header("Cookie", cookie) }
                    .build()

                client.newCall(request).execute().use { response ->
                    response.headers("Set-Cookie").forEach {
                        runCatching { CookieManager.getInstance().setCookie(response.request.url.toString(), it) }
                    }
                    CookieManager.getInstance().flush()

                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    if (!response.isSuccessful || bytes.isEmpty()) {
                        error("NIMS report could not be downloaded.")
                    }
                    if (!isPdf(bytes)) {
                        val sample = bytes.toString(Charsets.UTF_8).take(12000)
                        if (Regex("(?i)login|captcha|session expired|please login again").containsMatchIn(sample)) {
                            error("NIMS session expired. Return to the app and log in again.")
                        }
                        error("NIMS did not return a PDF for this report.")
                    }

                    val file = File.createTempFile("nims-report-", ".pdf", cacheDir)
                    FileOutputStream(file).use { it.write(bytes) }
                    tempFile = file
                    runOnUiThread { open(file) }
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    status.text = error.message ?: "Could not open NIMS report."
                }
            }
        }
    }

    private fun open(file: File) {
        progress.visibility = View.GONE
        descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(descriptor!!)
        if ((renderer?.pageCount ?: 0) == 0) {
            status.text = "NIMS report PDF is empty."
            return
        }
        saveButton.isEnabled = true
        render(0)
    }

    private fun render(index: Int) {
        val current = renderer ?: return
        if (index !in 0 until current.pageCount) return
        val switchingPage = index != pageIndex
        val page = current.openPage(index)
        // Capped width prevents very high resolution PDFs from exhausting memory.
        val width = (resources.displayMetrics.widthPixels * zoom).toInt().coerceIn(320, 2048)
        val scale = width.toFloat() / page.width.toFloat()
        val height = (page.height * scale).toInt().coerceIn(1, 4096)
        val nextBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        nextBitmap.eraseColor(Color.WHITE)
        try {
            page.render(nextBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        } finally {
            page.close()
        }

        image.setImageDrawable(null)
        bitmap?.recycle()
        bitmap = nextBitmap
        pageIndex = index
        image.layoutParams = android.widget.FrameLayout.LayoutParams(width, height)
        image.setImageBitmap(nextBitmap)
        status.text = "PDF · page " + (index + 1) + "/" + current.pageCount
        previous.isEnabled = index > 0
        next.isEnabled = index + 1 < current.pageCount
        if (switchingPage) {
            verticalScroll.post { verticalScroll.scrollTo(0, 0) }
            horizontalScroll.post { horizontalScroll.scrollTo(0, 0) }
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
        tempFile?.delete()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "report_url"
        private const val REQUEST_SAVE_PDF = 231
    }
}
