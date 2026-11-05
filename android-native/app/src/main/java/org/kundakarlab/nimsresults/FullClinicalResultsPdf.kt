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
    private const val LEADING=16f

    fun write(output: OutputStream, patient: PatientInfo,
              retrievedAt: Long, studies: List<FullClinicalStudy>,
              sources:List<FullClinicalSource> = emptyList()) {
        require(studies.isNotEmpty()) { "No investigations to export" }
        val pdf=PdfDocument()
        val normal=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.rgb(34,50,64);textSize=11.2f
        }
        val muted=Paint(normal).apply { textSize=9.3f;color=Color.rgb(96,108,119) }
        val bold=Paint(normal).apply { isFakeBoldText=true;textSize=11.5f }
        val accent=Paint(bold).apply { textSize=12.3f; color=Color.rgb(17,65,105) }
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
            current.canvas.drawText("Clinical summary - confirm against signed NIMS report",
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
            canvas!!.drawText("NIMS Results | All investigations",LEFT,y,accent)
            y+=16f
            val details=patient.name.ifBlank { "Patient name not provided" }+
                " | CR: "+patient.crNo+" | "+patient.age+" | "+patient.sex
            val detailLine=details.take(95)
            canvas!!.drawText(detailLine,LEFT,y,normal)
            y+=15f
            canvas!!.drawText("Results snapshot: "+snapshot,LEFT,y,muted)
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
            var precedingDate=""
            for(study in studies) {
                val date=study.sourceDate.ifBlank { "Date unavailable" }
                if(date!=precedingDate) {
                    ensure(68f)
                    if(precedingDate.isNotBlank())y+=12f
                    canvas!!.drawLine(LEFT,y,RIGHT,y,line)
                    y+=21f
                    wrapped(date,accent)
                    precedingDate=date
                }
                ensure(51f)
                y+=7f
                wrapped(study.title,bold)
                val verifiedDetails=listOfNotNull(
                    study.accession.takeIf(String::isNotBlank)?.let { "Lab "+it },
                    study.specimen.takeIf(String::isNotBlank),
                    study.collectedAt.takeIf(String::isNotBlank)?.let { "Col "+it },
                    study.sentAt.takeIf(String::isNotBlank)?.let { "Sent "+it },
                    study.reportedAt.takeIf(String::isNotBlank)?.let { "Rep "+it }
                ).joinToString("  |  ")
                if(verifiedDetails.isNotBlank())wrapped(verifiedDetails,muted,8f)
                if(study.conflictingCulture)
                    wrapped("CONFLICTING CULTURE STATEMENTS - VERIFY ORIGINAL PDF",warning,8f)
                val readable=study.findings.filterNot { it.unresolved }
                if(readable.isEmpty())wrapped("Result unavailable in structured data",muted,8f)
                else readable.forEach { finding ->
                    val raw=finding.value.trim()
                    if(raw.isBlank())return@forEach
                    val unit=if(finding.unit.isNotBlank() &&
                        !raw.endsWith(finding.unit,true)) " "+finding.unit else ""
                    val label=finding.parameter.trim()
                    val generic=label.equals("Result",true) ||
                        label.equals(study.title,true)
                    val textValue=if(generic)raw+unit else label+": "+raw+unit
                    // Keep short numeric rows and reference values on one line.
                    val reference=finding.reference.trim()
                    val row=if(reference.isNotBlank() &&
                        normal.measureText(textValue+"    ["+reference+"]")<RIGHT-LEFT-14f)
                        textValue+"    ["+reference+"]" else textValue
                    wrapped(row,normal,10f)
                    if(reference.isNotBlank() && !row.contains("["+reference+"]"))
                        wrapped("Ref: "+reference,muted,20f)
                }
            }
            // An unlinked PDF-index appendix is intentionally NOT included.
            // It expanded one patient's review into dozens of unrelated
            // metadata rows and obscured the actual clinical observations.
            endPage()
            pdf.writeTo(output)
        } finally { pdf.close() }
    }
}
