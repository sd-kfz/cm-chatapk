package org.cmchat.app.transport

/**
 * A tiny token-bucket rate limiter keyed by a string (e.g. a contact's cmId).
 * Used post-authentication to drop a peer that floods us with frames. RAM-only;
 * bounded in size so the key map itself can't grow without limit.
 */
class RateLimiter(
    private val burst: Int,
    private val refillPerSec: Double,
    private val maxKeys: Int = 256,
) {
    private data class Bucket(var tokens: Double, var at: Long)
    private val buckets = HashMap<String, Bucket>()

    @Synchronized
    fun allow(key: String, now: Long = System.currentTimeMillis()): Boolean {
        // Bound the map: if somehow flooded with distinct keys, reset it.
        if (buckets.size > maxKeys && !buckets.containsKey(key)) buckets.clear()
        val b = buckets.getOrPut(key) { Bucket(burst.toDouble(), now) }
        b.tokens = (b.tokens + (now - b.at) / 1000.0 * refillPerSec).coerceAtMost(burst.toDouble())
        b.at = now
        if (b.tokens < 1.0) return false
        b.tokens -= 1.0
        return true
    }

    @Synchronized
    fun clear() = buckets.clear()
}
