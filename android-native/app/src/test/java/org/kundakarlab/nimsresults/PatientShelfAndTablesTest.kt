package org.kundakarlab.nimsresults

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PatientShelfAndTablesTest {
    @Test fun historyValidatesCrAndPreservesSavedListsAcrossRecencyTrimming() {
        assertTrue(PatientShelfRules.validCr("123456789012345"))
        assertFalse(PatientShelfRules.validCr("12345"))
        assertFalse(PatientShelfRules.validCr("12345678901234x"))
        val recent = (0..32).map { index ->
            PatientBookmark((100000000000000L + index).toString(), lastUsed = index.toLong())
        }
        val saved = PatientBookmark("123456789012345", "Synthetic Patient",
            setOf("Favorites", "Follow-up"), lastUsed = 1)
        val bounded = PatientShelfRules.bounded(recent + saved)
        assertEquals(26, bounded.size)
        assertTrue(bounded.any { it.cr == saved.cr && "Favorites" in it.lists })
        assertEquals(25, bounded.count { it.lists.isEmpty() })
    }

    @Test fun freeTextAndAnnotationsAreBoundedOnDevice() {
        assertEquals(85, PatientShelfRules.sanitizeName("A".repeat(150)).length)
        assertEquals(180, PatientShelfRules.sanitizeNote("x".repeat(500)).length)
        assertEquals(null, PatientShelfRules.normalizedList("Unapproved"))
        assertEquals("Research", PatientShelfRules.normalizedList("Research"))
    }

    @Test fun structuredHtmlAndJsonTableCellsPreserveOriginalLabels() {
        val html = "<table><tr><th>Target</th><th>Result</th></tr>" +
            "<tr><td>Test antigen A</td><td>Not detected</td></tr></table>"
        val formatted = ResultTableFormats.format(html)
        assertTrue(formatted.contains("Target | Result"))
        assertTrue(formatted.contains("Test antigen A | Not detected"))
        val json = """[{"analyte":"Test analyte","result":"<1.0"},{"analyte":"Other target","result":"Not detected"}]"""
        val result = ResultTableFormats.format(json)
        assertTrue(result.contains("<1.0"))
        assertTrue(result.contains("Not detected"))
    }

    @Test fun coordinateRowsGroupWithoutConvertingNumericCodes() {
        val tokens = listOf(
            PositionedText(20f, 100f, 50f, "Analyte"),
            PositionedText(150f, 100f, 40f, "Result"),
            PositionedText(20f, 118f, 80f, "Marker X"),
            PositionedText(150f, 118f, 42f, "<1.00"),
            PositionedText(20f, 138f, 65f, "Combo10"),
            PositionedText(150f, 138f, 12f, "3")
        )
        val lines = PdfGeometryTable.lines(tokens)
        assertEquals(3, lines.size)
        assertTrue(lines[1].contains("<1.00"))
        assertTrue(lines[2].contains("Combo10"))
        assertTrue(lines[2].contains("3"))
    }

    @Test fun whitespaceOnlyAndUnalignedTextRemainsVisible() {
        assertTrue(ReportTableReader.present("Single line notes").contains("Single line notes"))
        assertTrue(ReportTableReader.splitColumns("Marker     <1.0").size == 2)
    }
}
