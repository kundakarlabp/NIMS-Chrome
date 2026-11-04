package org.kundakarlab.nimsresults

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Full, paginated, device-local and selectable-text PDF.
 * Patient report dates/identifiers appear only when independently supplied
 * or confirmed by a matching CR-verified source PDF.
 */
object FullClinicalResultsPdf {
    private const val PAGE_W=595
    private const val PAGE_H=842
    private const val LEFT=35f
    private const val RIGHT=560f
    private const val BOTTOM=797f
    private const val LEADING=13f

    fun write(output: OutputStream, patient: PatientInfo,
              retrievedAt: Long, studies: List<FullClinicalStudy>) {
        require(studies.isNotEmpty()) { "No investigations to export" }
        val pdf=PdfDocument()
        val normal=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.rgb(34,50,64);textSize=9.5f
        }
        val muted=Paint(normal).apply { textSize=8.5f;color=Color.rgb(96,108,119) }
        val bold=Paint(normal).apply { isFakeBoldText=true;textSize=10.5f }
        val accent=Paint(bold).apply { color=Color.rgb(17,65,105) }
        val warning=Paint(bold).apply { color=Color.rgb(168,42,39) }
        val line=Paint().apply {color=Color.rgb(222,228,233);strokeWidth=0.7f}
        val created=SimpleDateFormat("dd-MMM-yyyy HH:mm",Locale.ENGLISH)
            .format(Date(System.currentTimeMillis()))
        val snapshot=SimpleDateFormat("dd-MMM-yyyy HH:mm",Locale.ENGLISH)
            .format(Date(retrievedAt))
        var page:PdfDocument.Page?=null
        var canvas:Canvas?=null
        var y=0f
        var number=0

        fun endPage() {
            val current=page ?: return
            current.canvas.drawLine(LEFT,812f,RIGHT,812f,line)
            current.canvas.drawText("Generated clinical review - NOT an original signed NIMS report",
                LEFT,823f,muted)
            current.canvas.drawText("Page "+number,RIGHT-55f,823f,muted)
            pdf.finishPage(current)
            page=null;canvas=null
        }
        fun newPage() {
            endPage()
            number++
            page=pdf.startPage(PdfDocument.PageInfo.Builder(PAGE_W,PAGE_H,number).create())
            canvas=page!!.canvas
            y=35f
            canvas!!.drawText("NIMS Results | Complete investigation review",LEFT,y,accent)
            y+=16f
            val details=patient.name.ifBlank { "Patient name not provided" }+
                " | CR: "+patient.crNo+" | "+patient.age+" | "+patient.sex
            canvas!!.drawText(details.take(125),LEFT,y,normal)
            y+=15f
            canvas!!.drawText("Snapshot: "+snapshot+"    Generated: "+created,LEFT,y,muted)
            y+=12f
            canvas!!.drawLine(LEFT,y,RIGHT,y,line)
            y+=18f
        }
        fun ensure(space:Float) { if(page==null || y+space>=BOTTOM)newPage() }
        fun wrapped(raw:String,paint:Paint=normal,indent:Float=0f) {
            val maxWidth=RIGHT-LEFT-indent
            val text=raw.replace('\t',' ').replace("\r\n","\n")
            for(paragraph in text.split('\n')) {
                if(paragraph.isBlank()) {ensure(LEADING);y+=LEADING;continue}
                var builder=""
                for(word in paragraph.split(Regex("""\s+"""))) {
                    if(word.isEmpty())continue
                    var remaining=word
                    while(remaining.isNotBlank()) {
                        val trial=if(builder.isBlank()) remaining else builder+" "+remaining
                        if(paint.measureText(trial)<=maxWidth) {
                            builder=trial;remaining="";continue
                        }
                        if(builder.isNotBlank()) {
                            ensure(LEADING)
                            canvas!!.drawText(builder,LEFT+indent,y,paint)
                            y+=LEADING;builder=""
                            continue
                        }
                        var n=remaining.length
                        while(n>1 && paint.measureText(remaining.take(n))>maxWidth)n--
                        ensure(LEADING)
                        canvas!!.drawText(remaining.take(n),LEFT+indent,y,paint)
                        y+=LEADING;remaining=remaining.drop(n)
                    }
                }
                if(builder.isNotBlank()) {
                    ensure(LEADING);canvas!!.drawText(builder,LEFT+indent,y,paint);y+=LEADING
                }
            }
        }
        fun detail(label:String,value:String) {
            if(value.isNotBlank())wrapped(label+": "+value,muted,8f)
        }
        try {
            studies.forEachIndexed { index,study ->
                ensure(93f)
                y+=if(index==0)0f else 9f
                canvas!!.drawLine(LEFT,y,RIGHT,y,line)
                y+=17f
                wrapped((index+1).toString()+". "+study.title,accent)
                detail("Structured date ("+study.dateKind+")",study.sourceDate)
                detail("Lab/Study ID",study.accession)
                detail("Collected",study.collectedAt)
                detail("Sent",study.sentAt)
                detail("Reported",study.reportedAt)
                detail("Specimen",study.specimen)
                wrapped("Provenance: "+study.evidence,muted,8f)
                if(study.conflictingCulture)
                    wrapped("WARNING: Conflicting culture statements in source. Inspect original NIMS PDF.",warning,8f)
                if(study.findings.isEmpty())
                    wrapped("No readable structured result. See original source report.",muted,8f)
                else {
                    study.findings.forEach { finding ->
                        y+=4f
                        val tag=if(finding.unresolved)" [Unmapped source field]" else ""
                        wrapped(finding.parameter+tag,bold,8f)
                        val unit=if(finding.unit.isNotBlank() &&
                            !finding.value.endsWith(finding.unit,true))" "+finding.unit else ""
                        wrapped(finding.value+unit,normal,16f)
                        if(finding.reference.isNotBlank())
                            wrapped("Reference: "+finding.reference,muted,16f)
                    }
                }
                y+=6f
            }
            endPage()
            pdf.writeTo(output)
        } finally { pdf.close() }
    }
}
