package org.kundakarlab.nimsdashboard

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReportViewerActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var image: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var previous: Button
    private lateinit var next: Button
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null
    private var pageIndex = 0
    private var bitmap: Bitmap? = null
    private var tempFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (ReportInventoryParser.safeReportUrl(url).isBlank()) {
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        val close = Button(this).apply {
            text = "Back"
            setOnClickListener { finish() }
        }
        status = TextView(this).apply {
            text = "Opening NIMS report…"
            textSize = 14f
            setPadding(dp(8), 0, dp(8), 0)
        }
        bar.addView(close)
        bar.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)))

        image = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(245, 247, 250))
        }
        root.addView(image, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        previous = Button(this).apply {
            text = "Previous"
            isEnabled = false
            setOnClickListener { renderPage(pageIndex - 1) }
        }
        next = Button(this).apply {
            text = "Next"
            isEnabled = false
            setOnClickListener { renderPage(pageIndex + 1) }
        }
        nav.addView(previous)
        nav.addView(next)
        root.addView(nav)

        setContentView(root)
        load(url, root)
    }

    private fun load(url: String, root: LinearLayout) {
        executor.execute {
            try {
                val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
                val client = OkHttpClient.Builder()
                    .connectTimeout(12, TimeUnit.SECONDS)
                    .readTimeout(40, TimeUnit.SECONDS)
                    .callTimeout(50, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", ReportInventory.USER_AGENT)
                    .apply { if (cookies.isNotBlank()) header("Cookie", cookies) }
                    .build()
                client.newCall(request).execute().use { response ->
                    response.headers("Set-Cookie").forEach {
                        runCatching { CookieManager.getInstance().setCookie(response.request.url.toString(), it) }
                    }
                    val bytes: ByteArray = response.body?.bytes() ?: ByteArray(0)
                    val contentType = response.header("Content-Type").orEmpty().lowercase()
                    if (response.code == 401 || response.code == 403 || bytes.isEmpty()) {
                        throw IllegalStateException("NIMS report could not be opened. Login again and retry.")
                    }
                    if (contentType.contains("pdf") || bytes.startsWithPdf()) {
                        val file = File.createTempFile("nims-report-", ".pdf", cacheDir)
                        FileOutputStream(file).use { it.write(bytes) }
                        tempFile = file
                        runOnUiThread { openPdf(file) }
                    } else {
                        val html = bytes.toString(Charsets.UTF_8)
                        if (Regex("(?i)login|captcha|session expired").containsMatchIn(html.take(10000))) {
                            throw IllegalStateException("NIMS session expired. Login again and retry.")
                        }
                        runOnUiThread { showHtml(url, root) }
                    }
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    status.text = error.message ?: "Could not open the NIMS report."
                }
            }
        }
    }

    private fun openPdf(file: File) {
        progress.visibility = View.GONE
        descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        renderer = PdfRenderer(descriptor!!)
        if ((renderer?.pageCount ?: 0) <= 0) {
            status.text = "The NIMS report PDF is empty."
            return
        }
        renderPage(0)
    }

    private fun renderPage(index: Int) {
        val currentRenderer = renderer ?: return
        if (index !in 0 until currentRenderer.pageCount) return
        bitmap?.recycle()
        val page = currentRenderer.openPage(index)
        val width = resources.displayMetrics.widthPixels.coerceAtLeast(720)
        val scale = width.toFloat() / page.width.toFloat()
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val nextBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        nextBitmap.eraseColor(Color.WHITE)
        page.render(nextBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        bitmap = nextBitmap
        image.setImageBitmap(nextBitmap)
        pageIndex = index
        status.text = "NIMS source report · page ${index + 1} of ${currentRenderer.pageCount}"
        previous.isEnabled = index > 0
        next.isEnabled = index + 1 < currentRenderer.pageCount
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun showHtml(url: String, root: LinearLayout) {
        progress.visibility = View.GONE
        image.visibility = View.GONE
        previous.visibility = View.GONE
        next.visibility = View.GONE
        status.text = "NIMS source report"
        val web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = ReportInventory.USER_AGENT
            webViewClient = WebViewClient()
            loadUrl(url)
        }
        root.addView(web, 2, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun ByteArray.startsWithPdf(): Boolean =
        size >= 4 && this[0] == '%'.code.toByte() && this[1] == 'P'.code.toByte() &&
            this[2] == 'D'.code.toByte() && this[3] == 'F'.code.toByte()

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
        const val EXTRA_URL = "nims_report_url"
    }
}
