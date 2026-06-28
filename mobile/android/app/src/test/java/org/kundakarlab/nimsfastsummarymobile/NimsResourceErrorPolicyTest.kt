package org.kundakarlab.nimsfastsummarymobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NimsResourceErrorPolicyTest {
    @Test
    fun mainFrameErrorsAreAlwaysSurfaced() {
        assertTrue(NimsResourceErrorPolicy.shouldSurface("https://www.nimsts.edu.in/anything.css", true))
    }

    @Test
    fun legacyStaticAssetsAreSuppressed() {
        val assets = listOf(
            "https://www.nimsts.edu.in/HIS/hisglobal/js/perfect-scrollbar.js",
            "https://www.nimsts.edu.in/HIS/hisglobal/css/perfect-scrollbar.css",
            "https://www.nimsts.edu.in/AHIMSG5/hislogin/transactions/css/sidebar.css",
            "https://www.nimsts.edu.in/HIS/fonts/fontawesome.woff2"
        )
        assets.forEach { assertFalse(it, NimsResourceErrorPolicy.shouldSurface(it, false)) }
    }

    @Test
    fun requiredJqueryScriptsAreSurfaced() {
        val assets = listOf(
            "https://www.nimsts.edu.in/HISInvestigationG5/js/jquery.min.js",
            "https://www.nimsts.edu.in/HISInvestigationG5/js/jquery.validate.email.js",
            "https://www.nimsts.edu.in/HISInvestigationG5/js/additional-methods.min.js"
        )
        assets.forEach { assertTrue(it, NimsResourceErrorPolicy.shouldSurface(it, false)) }
    }

    @Test
    fun documentEndpointsAreSurfacedForChildFrames() {
        val pages = listOf(
            "https://www.nimsts.edu.in/AHIMSG5/hissso/loginLogin.action",
            "https://www.nimsts.edu.in/HISInvestigationG5/new_investigation/viewcrnowisereportprocess.cnt?x=1",
            "https://www.nimsts.edu.in/AHIMSG5/menu.jsp",
            "https://www.nimsts.edu.in/HIS/run.do"
        )
        pages.forEach { assertTrue(it, NimsResourceErrorPolicy.shouldSurface(it, false)) }
    }

    @Test
    fun arbitraryExtensionlessSubresourcesAreNotMislabelledAsFrames() {
        assertFalse(
            NimsResourceErrorPolicy.shouldSurface(
                "https://www.nimsts.edu.in/AHIMSG5/assets/generated/resource",
                false
            )
        )
    }
}
