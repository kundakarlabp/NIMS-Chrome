package org.kundakarlab.nimsfastsummarymobile.data.pdf

object PdfExtractedTextNormalizer {
    fun normalize(raw: String): String {
        val cleaned = raw.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u00A0', ' ')
            .filter { it == '\n' || it == '\t' || it.code >= 0x20 }
        return cleaned.lines()
            .joinToString("\n") { line -> line.replace(Regex("[ \\t]{2,}"), " ").trimEnd() }
            .replace(Regex("\n{4,}"), "\n\n\n")
            .trim()
    }
}
