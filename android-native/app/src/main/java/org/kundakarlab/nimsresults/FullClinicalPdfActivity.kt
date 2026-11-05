package org.kundakarlab.nimsresults

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * Private preview of the locally generated PDF. Does not expose a file path,
 * web URL or external ContentProvider. Save As uses Android's document picker.
 */
class FullClinicalPdfActivity : Activity() {
    private val worker=Executors.newSingleThreadExecutor()
    private var renderer:PdfRenderer?=null
    private var descriptor:ParcelFileDescriptor?=null
    private var rendered:Bitmap?=null
    private var pageIndex=0
    private var pageCount=0
    private var zoom=1.15f
    private val scaleDetector by lazy {
        ScaleGestureDetector(this,object:ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector:ScaleGestureDetector):Boolean {
                zoom=(zoom*detector.scaleFactor).coerceIn(1f,3.2f)
                applyZoom()
                return true
            }
        })
    }

    override fun dispatchTouchEvent(event:MotionEvent):Boolean {
        scaleDetector.onTouchEvent(event)
        if(scaleDetector.isInProgress)return true
        return super.dispatchTouchEvent(event)
    }
    private var closing=false
    private lateinit var image:ImageView
    private lateinit var counter:TextView
    private lateinit var previous:Button
    private lateinit var next:Button
    private val pdfFile:File by lazy { File(cacheDir,PDF_FILE) }

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        pageIndex=state?.getInt("pdf_page",0)?:0
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root){view,insets->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0,bars.top,0,bars.bottom)
            insets
        }
        fun button(label:String,action:()->Unit):Button=Button(this).apply {
            text=label;textSize=12f;minWidth=0;minimumWidth=0
            setPadding(8,0,8,0);setOnClickListener { action() }
        }
        val titleRow=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(6,2,6,2)
            setBackgroundColor(Color.rgb(12,45,76))
        }
        titleRow.addView(button("Back"){finish()})
        counter=TextView(this).apply {
            text="Clinical PDF";textSize=13f
            setTextColor(Color.WHITE);gravity=Gravity.CENTER
        }
        titleRow.addView(counter,LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        titleRow.addView(button("Save PDF"){saveCopy()})
        root.addView(titleRow)
        val navRow=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER
            setPadding(4,0,4,0)
            setBackgroundColor(Color.rgb(230,238,244))
        }
        previous=button("Previous"){showPage(pageIndex-1)}
        next=button("Next"){showPage(pageIndex+1)}
        navRow.addView(previous,LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        navRow.addView(next,LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT,1f))
        navRow.addView(button("Fit") {zoom=1f;applyZoom()},
            LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,0.7f))
        navRow.addView(button("Zoom +") {
            zoom=(zoom*1.35f).coerceAtMost(3.2f);applyZoom()
        },LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,0.9f))
        root.addView(navRow)
        image=ImageView(this).apply {
            scaleType=ImageView.ScaleType.FIT_CENTER
            adjustViewBounds=true
            setBackgroundColor(Color.WHITE)
            contentDescription="Generated clinical PDF page"
        }
        root.addView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(243,246,248))
            addView(HorizontalScrollView(this@FullClinicalPdfActivity).apply {
                isFillViewport=true
                addView(image,android.widget.FrameLayout.LayoutParams(
                    (resources.displayMetrics.widthPixels*zoom).toInt(),
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
            },android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
        },LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        applyZoom()
        previous.isEnabled=false;next.isEnabled=false
        if(!pdfFile.isFile || pdfFile.length()<4L) {
            Toast.makeText(this,"PDF preview is not available",Toast.LENGTH_LONG).show()
            finish();return
        }
        worker.execute {
            try {
                val input=ParcelFileDescriptor.open(pdfFile,
                    ParcelFileDescriptor.MODE_READ_ONLY)
                val render=PdfRenderer(input)
                descriptor=input;renderer=render;pageCount=render.pageCount
                if(pageCount<1)error("PDF has no pages")
                runOnUiThread { if(!closing)showPage(pageIndex.coerceIn(0,pageCount-1)) }
            } catch (_:Exception) {
                runOnUiThread {
                    if(!closing)Toast.makeText(this,"Unable to preview PDF",
                        Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    private fun applyZoom() {
        if(!::image.isInitialized)return
        val width=(resources.displayMetrics.widthPixels*zoom).toInt()
        image.layoutParams=android.widget.FrameLayout.LayoutParams(
            width.coerceAtLeast(1),
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun showPage(index:Int) {
        if(index !in 0 until pageCount || closing)return
        pageIndex=index
        previous.isEnabled=index>0;next.isEnabled=index<pageCount-1
        counter.text=(index+1).toString()+"/"+pageCount
        worker.execute {
            val render=renderer ?:return@execute
            val bitmap=try {
                render.openPage(index).use { page ->
                    val scale=2.0f
                    val b=Bitmap.createBitmap(
                        (page.width*scale).toInt().coerceAtLeast(1),
                        (page.height*scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888)
                    b.eraseColor(Color.WHITE)
                    page.render(b,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    b
                }
            } catch (_:Exception) { null }
            runOnUiThread {
                if(closing || index!=pageIndex) { bitmap?.recycle();return@runOnUiThread }
                val old=rendered
                rendered=bitmap
                image.setImageBitmap(bitmap)
                old?.recycle()
            }
        }
    }

    private fun saveCopy() {
        if(!pdfFile.isFile)return
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type="application/pdf"
            putExtra(Intent.EXTRA_TITLE,"NIMS-Results-Clinical-Review.pdf")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent,REQUEST_SAVE)
    }

    @Deprecated("Activity result callback")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=REQUEST_SAVE || resultCode!=RESULT_OK)return
        val uri=data?.data ?:return
        worker.execute {
            val saved=runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    pdfFile.inputStream().use { it.copyTo(out) }
                } ?: error("Destination not writable")
            }.isSuccess
            runOnUiThread {
                if(!closing)Toast.makeText(this,
                    if(saved)"Clinical review PDF saved" else "Could not save PDF",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onSaveInstanceState(out:Bundle) {
        out.putInt("pdf_page",pageIndex)
        super.onSaveInstanceState(out)
    }
    override fun onDestroy() {
        closing=true
        image.setImageDrawable(null)
        rendered?.recycle();rendered=null
        worker.execute {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
        }
        worker.shutdown()
        super.onDestroy()
    }
    companion object {
        const val PDF_FILE="nims-full-clinical-preview.pdf"
        private const val REQUEST_SAVE=1812
    }
}
