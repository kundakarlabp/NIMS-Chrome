package org.kundakarlab.nimsresults

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Patient-requested PDF export; source data remains unchanged.
 * Pages are created one at a time without bitmap snapshots or external APIs.
 * Date columns refer to NIMS structured result dates, not inferred specimen dates.
 */
object ClinicalLabPdfExport {
    private const val WIDTH = 595
    private const val HEIGHT = 842
    private const val LEFT = 28f
    private const val RIGHT = 567f
    private const val FIRST_DATE_X = 214f
    private const val DATE_WIDTH = 87f

    fun write(output: OutputStream, patient: PatientInfo,
              retrievedAt: Long, tables: List<LabPanelMatrix.Table>) {
        val pdf = PdfDocument()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(28, 52, 72)
            textSize = 10f
        }
        val muted = Paint(text).apply { color = Color.rgb(95, 113, 128); textSize=8f }
        val strong = Paint(text).apply { isFakeBoldText = true }
        val line = Paint().apply { color = Color.rgb(221, 228, 234); strokeWidth = .7f }
        var pageNumber = 0
        val generated = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.ENGLISH)
            .format(Date(System.currentTimeMillis()))
        val snapshot = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.ENGLISH)
            .format(Date(retrievedAt))
        fun shorten(value: String, maxWidth: Float, paint: Paint): String {
            if (paint.measureText(value) <= maxWidth) return value
            var limit = value.length
            while (limit > 0 &&
                paint.measureText(value.take(limit) + "…") > maxWidth) limit--
            return value.take(limit) + "…"
        }
        try {
            for (table in tables) {
                if (table.parameters.isEmpty()) continue
                val datesChunks = table.dates.chunked(4).ifEmpty { listOf(emptyList()) }
                for (dates in datesChunks) {
                    val showRows = table.parameters.filter { param ->
                        dates.any { !param.byDate[it].isNullOrEmpty() }
                    }
                    if (showRows.isEmpty()) continue
                    for (rows in showRows.chunked(27)) {
                        pageNumber++
                        val page = pdf.startPage(PdfDocument.PageInfo.Builder(
                            WIDTH, HEIGHT, pageNumber).create())
                        val canvas=page.canvas
                        strong.textSize=16f
                        canvas.drawText("NIMS Results | Laboratory trends", LEFT, 38f, strong)
                        strong.textSize=10f
                        canvas.drawText(shorten(patient.name.ifBlank { "Patient name not available" },
                            420f, strong),LEFT,62f,strong)
                        canvas.drawText("CR: " + patient.crNo,LEFT,78f,text)
                        canvas.drawText("Age: " + patient.age + "    Sex: " + patient.sex,
                            LEFT,93f,text)
                        canvas.drawText("Exported: " + generated + "    Snapshot: " +
                            snapshot,LEFT,108f,muted)
                        canvas.drawText("Result-date columns; collection/report times and PDF " +
                            "identities are not inferred.",LEFT,122f,muted)
                        canvas.drawLine(LEFT,132f,RIGHT,132f,line)
                        strong.textSize=12f
                        canvas.drawText(shorten(table.panel.label,500f,strong),
                            LEFT,152f,strong)
                        text.textSize=9f
                        strong.textSize=9f
                        canvas.drawText("Parameter",LEFT,176f,strong)
                        dates.forEachIndexed { idx, date ->
                            canvas.drawText(LabPanelMatrix.displayDate(date),
                                FIRST_DATE_X + idx*DATE_WIDTH,176f,strong)
                        }
                        canvas.drawLine(LEFT,184f,RIGHT,184f,line)
                        rows.forEachIndexed { idx, param ->
                            val baseline=203f+idx*22f
                            text.color = Color.rgb(35, 55, 72)
                            canvas.drawText(shorten(LabPanelMatrix.shortLabel(param.title),
                                FIRST_DATE_X-LEFT-10f,text),LEFT,baseline,text)
                            dates.forEachIndexed { col, date ->
                                val values=param.byDate[date].orEmpty()
                                val label=when {
                                    values.isEmpty() -> "—"
                                    values.size>1 -> "${values.size} values*"
                                    else -> ClinicalPresentation.displayValue(values[0])
                                }
                                canvas.drawText(shorten(label, DATE_WIDTH-8f,text),
                                    FIRST_DATE_X + col*DATE_WIDTH,baseline,text)
                            }
                            canvas.drawLine(LEFT,baseline+7f,RIGHT,baseline+7f,line)
                        }
                        canvas.drawText("* Same-day repetitions are not averaged; " +
                            "inspect the original results in the app.",LEFT,802f,muted)
                        canvas.drawText("Page " + pageNumber,RIGHT-46f,822f,muted)
                        pdf.finishPage(page)
                    }
                }
            }
            if (pageNumber == 0) error("No dated clinical parameters available to export")
            pdf.writeTo(output)
        } finally {
            pdf.close()
        }
    }
}
