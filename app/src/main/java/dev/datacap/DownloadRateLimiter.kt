package dev.datacap

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal class DownloadRateLimiter(initialLimitBps: Long) {
    private var bytesPerSecond = initialLimitBps / 8.0
    private var capacity = bucketCapacity(bytesPerSecond)
    private var tokens = 0.0
    private var lastRefillNanos = System.nanoTime()

    @Synchronized
    fun setLimitBps(limitBps: Long) {
        require(limitBps > 0L)
        refill()
        bytesPerSecond = limitBps / 8.0
        capacity = bucketCapacity(bytesPerSecond)
        tokens = min(tokens, capacity)
        (this as java.lang.Object).notifyAll()
    }

    @Synchronized
    @Throws(InterruptedException::class)
    fun acquire(byteCount: Int) {
        var remaining = byteCount.toLong()
        while (remaining > 0L) {
            refill()
            val available = floor(tokens).toLong()
            if (available > 0L) {
                val consumed = min(remaining, available)
                tokens -= consumed
                remaining -= consumed
            } else {
                val waitMillis = ceil((1.0 - tokens) / bytesPerSecond * 1_000.0)
                    .toLong()
                    .coerceAtLeast(1L)
                (this as java.lang.Object).wait(waitMillis)
            }
        }
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsedSeconds = (now - lastRefillNanos) / 1_000_000_000.0
        tokens = min(capacity, tokens + elapsedSeconds * bytesPerSecond)
        lastRefillNanos = now
    }

    private fun bucketCapacity(rateBytesPerSecond: Double): Double =
        max(MIN_BURST_BYTES, min(MAX_BURST_BYTES.toDouble(), rateBytesPerSecond / 4.0))

    private companion object {
        const val MIN_BURST_BYTES = 1.0
        const val MAX_BURST_BYTES = 65_536
    }
}
