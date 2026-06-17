package org.kundakarlab.nimsfastsummarymobile.security

import org.junit.Assert.*
import org.junit.Test

class LogSanitizerTest {
    @Test fun stripsQueryAndCrIdentifiers() {
        val safe = LogSanitizer.urlHostPath("https://host/path?file=CR123456&token=secret")
        assertEquals("https://host/path", safe)
    }
    @Test fun redactsSensitiveFieldsAndBoundsLength() {
        val safe = LogSanitizer.message("Cookie: abc apiKey=secret Authorization: bearer CR123456", 40)
        assertFalse(safe.contains("secret"))
        assertFalse(safe.contains("CR123456"))
        assertTrue(safe.length <= 40)
    }
}
