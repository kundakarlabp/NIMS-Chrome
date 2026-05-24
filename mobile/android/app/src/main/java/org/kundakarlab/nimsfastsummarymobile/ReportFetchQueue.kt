package org.kundakarlab.nimsfastsummarymobile

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ReportFetchQueue(private val concurrency: Int = 3) {
    fun <T, R> run(items: List<T>, worker: (T) -> R): List<Result<R>> {
        val limit = concurrency.coerceIn(1, 5)
        val next = AtomicInteger(0)
        val results = MutableList<Result<R>?>(items.size) { null }
        val pool = Executors.newFixedThreadPool(limit)
        val futures = (0 until limit).map {
            pool.submit {
                while (true) {
                    val index = next.getAndIncrement()
                    if (index >= items.size) break
                    results[index] = runCatching { worker(items[index]) }
                }
            }
        }
        futures.forEach { it.get() }
        pool.shutdown()
        return results.map { it ?: Result.failure(IllegalStateException("not processed")) }
    }
}
