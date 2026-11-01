package org.kundakarlab.nimsresults

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-level regression guard: Android's HorizontalScrollView is a
 * FrameLayout and casts child LayoutParams during measurement. A generic
 * ViewGroup.LayoutParams compiles, but crashes in framework onMeasure.
 * JVM unit tests cannot instantiate this Android widget without Robolectric,
 * so this enforces the dangerous assignment at source level.
 */
class PdfViewerLayoutRegressionTest {
    private fun source(file: String): String {
        val suffix = "src/main/java/org/kundakarlab/nimsresults/$file"
        return listOf(File(suffix), File("app/$suffix"))
            .first { it.isFile }.readText()
    }

    @Test fun pdfPageGetsFrameLayoutCompatibleParams() {
        val code = source("ReportActivity.kt")
        assertTrue(code.contains("image.layoutParams = FrameLayout.LayoutParams(width, height)"))
        assertFalse(code.contains("image.layoutParams = ViewGroup.LayoutParams("))
    }

    @Test fun singlePdfOpensDirectlyWithoutTreatingCandidateAsVerifiedResult() {
        val code = source("MainActivity.kt")
        assertTrue(code.contains("if(only!=null) openReport(only.url) else pickPdf=true"))
        assertTrue(code.contains("header?.crNo==current.patient.crNo"))
    }
}
