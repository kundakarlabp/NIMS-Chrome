package org.kundakarlab.nimsfastsummarymobile.security

import org.junit.Assert.*
import org.junit.Test

class SafeLogBufferTest {
    @Test
    fun retainsLongEntriesWithoutChoppingThemToTheOldTwoFortyCharLimit() {
        val buffer = SafeLogBuffer()
        val longError = "Uncaught TypeError: Cannot read properties of undefined (reading 'someReallyLongPropertyNameThatWouldHaveBeenChoppedUnderTheOldTwoHundredFortyCharacterPerEntryLimit') at https://www.nimsts.edu.in/some/deeply/nested/path/to/a/script/file/that/is/long.js:1234:56"
        buffer.add(longError)
        assertTrue("the full error text must survive, not be cut at 240 chars", buffer.fullText().contains("someReallyLongPropertyNameThatWouldHaveBeenChoppedUnderTheOldTwoHundredFortyCharacterPerEntryLimit"))
        assertTrue(buffer.fullText().contains(":1234:56"))
    }

    @Test
    fun fullTextReturnsEverythingNotJustTheDisplayTruncatedView() {
        val buffer = SafeLogBuffer()
        repeat(50) { i -> buffer.add("entry $i") }
        val full = buffer.fullText()
        assertTrue(full.contains("entry 0"))
        assertTrue(full.contains("entry 49"))
    }

    @Test
    fun stillRedactsSensitiveFieldsRegardlessOfTheHigherLengthLimit() {
        val buffer = SafeLogBuffer()
        buffer.add("Cookie: secretcookievalue Authorization: bearer abc123")
        assertFalse(buffer.fullText().contains("secretcookievalue"))
    }

    @Test
    fun dropsOldestEntriesPastMaxEntriesButKeepsRecentOnesIntact() {
        val buffer = SafeLogBuffer(maxEntries = 5, maxEntryLength = 100)
        repeat(7) { i -> buffer.add("entry $i") }
        val full = buffer.fullText()
        assertFalse(full.contains("entry 0"))
        assertFalse(full.contains("entry 1"))
        assertTrue(full.contains("entry 6"))
    }
}
