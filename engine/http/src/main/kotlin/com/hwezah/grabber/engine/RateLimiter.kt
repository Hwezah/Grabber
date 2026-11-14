package com.hwezah.grabber.engine

import kotlinx.coroutines.delay

/** A shared speed cap across every connection. [bytesPerSecond] 0 means no cap. */
class RateLimiter(@Volatile var bytesPerSecond: Long = 0) {
    private var windowStart = System.nanoTime()
    private var used = 0L

    suspend fun take(bytes: Int) {
        while (true) {
            val limit = bytesPerSecond
            if (limit <= 0) return
            val wait: Long
            synchronized(this) {
                val now = System.nanoTime()
                if (now - windowStart >= 1_000_000_000L) {
                    windowStart = now
                    used = 0
                }
                if (used + bytes <= limit || used == 0L) {
                    used += bytes
                    return
                }
                wait = (1_000_000_000L - (now - windowStart)) / 1_000_000L
            }
            delay(wait.coerceAtLeast(5))
        }
    }
}
