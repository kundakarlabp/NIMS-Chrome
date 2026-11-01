package org.kundakarlab.nimsresults

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** Position-based text rows for native NIMS PDF reports with embedded fonts. */
data class PositionedText(val x: Float, val y: Float, val width: Float, val text: String)

object PdfGeometryTable {
    fun lines(source: List<PositionedText>): List<String> {
        val all = source.filter { it.text.isNotBlank() && it.x.isFinite() && it.y.isFinite() }
            .sortedWith(compareBy<PositionedText> { it.y }.thenBy { it.x })
        val rows = mutableListOf<MutableList<PositionedText>>()
        val bands = mutableListOf<Float>()
        all.forEach { entry ->
            val index = bands.indexOfFirst { abs(it - entry.y) <= 2.5f }
            if (index < 0) {
                bands += entry.y
                rows += mutableListOf(entry)
            } else {
                rows[index] += entry
            }
        }
        return rows.map { row ->
            val ordered = row.sortedBy { it.x }
            val out = StringBuilder()
            var cursor = Float.NaN
            ordered.forEach { token ->
                val gap = if (cursor.isNaN()) 0f else token.x - cursor
                if (out.isNotEmpty()) {
                    if (gap >= 11f) out.append("   |   ")
                    else if (gap >= 3f && !out.last().isWhitespace()) out.append(' ')
                }
                out.append(token.text)
                cursor = max(cursor.takeUnless(Float::isNaN) ?: 0f, token.x + token.width)
            }
            out.toString().trim()
        }.filter(String::isNotBlank)
    }

    fun read(file: File): String =
        PDDocument.load(file).use { document -> read(document) }

    /** Use the document that is already open in the source-index worker;
     * avoid a second PDF load and keep only bounded text rows in memory. */
    fun read(document: PDDocument): String {
            val output = mutableListOf<String>()
            val count = minOf(document.numberOfPages, 8)
            for (page in 1..count) {
                val positions = mutableListOf<PositionedText>()
                val stripper = object : PDFTextStripper() {
                    override fun processTextPosition(text: TextPosition) {
                        val chars = text.unicode.orEmpty()
                        if (chars.isNotBlank() && positions.size < 25000) {
                            positions += PositionedText(
                                text.xDirAdj, text.yDirAdj,
                                text.widthDirAdj, chars
                            )
                        }
                        super.processTextPosition(text)
                    }
                }
                stripper.startPage = page
                stripper.endPage = page
                stripper.getText(document)
                val lines = lines(positions)
                if (lines.isNotEmpty()) {
                    output += "PAGE " + page
                    output.addAll(lines)
                }
            }
            return output.joinToString("\n").take(22000)
    }
}
