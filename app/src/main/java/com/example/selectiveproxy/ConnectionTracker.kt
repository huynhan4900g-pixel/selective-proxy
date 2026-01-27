package com.example.selectiveproxy

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class ConnectionTracker {
    data class AppStats(
        val packageName: String,
        var bytesUp: AtomicLong = AtomicLong(0),
        var bytesDown: AtomicLong = AtomicLong(0),
        var connections: AtomicLong = AtomicLong(0)
    )

    private val stats = ConcurrentHashMap<String, AppStats>()
    private val totalBytesUp = AtomicLong(0)
    private val totalBytesDown = AtomicLong(0)

    fun update(packageName: String, upBytes: Long, downBytes: Long) {
        stats.computeIfAbsent(packageName) { AppStats(packageName) }.apply {
            bytesUp.addAndGet(upBytes)
            bytesDown.addAndGet(downBytes)
        }
        totalBytesUp.addAndGet(upBytes)
        totalBytesDown.addAndGet(downBytes)
    }

    fun getStats(): Map<String, AppStats> = stats.toMap()
    fun getTotalUp() = totalBytesUp.get()
    fun getTotalDown() = totalBytesDown.get()
    fun reset() {
        stats.clear()
        totalBytesUp.set(0)
        totalBytesDown.set(0)
    }
}
