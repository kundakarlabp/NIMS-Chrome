package org.kundakarlab.nimsresults

/** Presentation-only column layout for selectable PDF text, not interpretation. */
object ReportTableReader {
    private val columns = Regex("""[ \\t]{2,}""")
    fun splitColumns(line: String): List<String> {
        val cells = line.trim().split(columns).map { it.trim() }.filter { it.isNotBlank() }
        return if (cells.size in 2..5) cells else emptyList()
    }
    fun present(text: String): String {
        val rows = mutableListOf<String>()
        val notes = mutableListOf<String>()
        text.replace("\r\n", "\n").lineSequence().forEach { line ->
            val items = splitColumns(line)
            if (items.size >= 2 && items.any { col -> col.any(Char::isLetter) }) {
                rows.add(items.joinToString("  |  "))
            } else if (line.isNotBlank()) {
                notes.add(line)
            }
        }
        if (rows.size < 2) return "No reliable table columns found. Original extracted text:\n\n" + text
        return "PDF text columns (not verified)\n\n" +
            rows.joinToString("\n") + "\n\nOther text and notes\n\n" + notes.joinToString("\n")
    }
}
