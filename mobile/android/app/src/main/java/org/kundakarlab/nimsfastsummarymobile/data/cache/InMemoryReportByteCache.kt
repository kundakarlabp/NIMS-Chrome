package org.kundakarlab.nimsfastsummarymobile.data.cache

/**
 * Process-local LRU cache for authenticated report bytes.
 *
 * Nothing is persisted. Call [clear] whenever the patient or NIMS session
 * changes. Byte arrays are treated as immutable after insertion.
 */
class InMemoryReportByteCache(
    private val maxBytes: Int = 48 * 1024 * 1024
) {
    private val entries = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var currentBytes: Int = 0

    @Synchronized
    fun put(key: String, bytes: ByteArray) {
        if (key.isBlank() || bytes.isEmpty() || bytes.size > maxBytes) return
        entries.remove(key)?.let { currentBytes -= it.size }
        entries[key] = bytes
        currentBytes += bytes.size
        trimToLimit()
    }

    @Synchronized
    fun get(key: String): ByteArray? = entries[key]

    @Synchronized
    fun remove(key: String) {
        entries.remove(key)?.let { currentBytes -= it.size }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        currentBytes = 0
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun byteCount(): Int = currentBytes

    private fun trimToLimit() {
        val iterator = entries.entries.iterator()
        while (currentBytes > maxBytes && iterator.hasNext()) {
            currentBytes -= iterator.next().value.size
            iterator.remove()
        }
    }
}
