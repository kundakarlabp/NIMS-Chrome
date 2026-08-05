package org.kundakarlab.nimsfastsummarymobile.data.cache

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InMemoryReportByteCacheTest {
    @Test
    fun evictsLeastRecentlyUsedEntryWithinByteLimit() {
        val cache = InMemoryReportByteCache(maxBytes = 6)
        cache.put("a", byteArrayOf(1, 2, 3))
        cache.put("b", byteArrayOf(4, 5, 6))
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.get("a"))

        cache.put("c", byteArrayOf(7, 8, 9))

        assertNull(cache.get("b"))
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.get("a"))
        assertArrayEquals(byteArrayOf(7, 8, 9), cache.get("c"))
        assertEquals(6, cache.byteCount())
    }

    @Test
    fun clearRemovesAllPatientReportBytes() {
        val cache = InMemoryReportByteCache(maxBytes = 100)
        cache.put("report", ByteArray(20))
        cache.clear()
        assertEquals(0, cache.size())
        assertEquals(0, cache.byteCount())
    }
}
