package org.cmchat.app.buzz

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * How often a person's buzzes are accepted by the receiver. "Once only" means:
 * after one buzz from someone, no more buzzes from them are accepted until the
 * user sends that person a message.
 */
enum class BuzzFrequency(val label: String, val everyMs: Long?) {
    H1("Every 1h", 60 * 60_000L),
    H12("Every 12h", 12 * 60 * 60_000L),
    H24("Every 24h", 24 * 60 * 60_000L),
    ONCE("Once only", null);

    companion object {
        fun fromLabel(l: String): BuzzFrequency = entries.firstOrNull { it.label == l } ?: H1
    }
}

/** A screen-shake request for the chat currently on screen. */
data class BuzzShake(val chatCmId: String, val at: Long = System.currentTimeMillis())

/**
 * Buzz rate-limiting + gating. All RAM-only; nothing here is persisted, and it
 * is cleared on every wipe path. A BUZZ carries no content and can never act as
 * a presence detector: the send side is cooldown-limited, and the receive side
 * throttles by the user's chosen frequency, so a probe can't be used to poll.
 */
object BuzzPolicy {

    /** Minimum gap between buzzes YOU send to one contact. */
    const val SEND_COOLDOWN_MS = 5 * 60_000L

    /** Receiver frequency setting (how often to accept a person's buzzes). */
    val frequency = MutableStateFlow(BuzzFrequency.H1)

    // cmId -> last time we SENT that contact a buzz
    private val lastSent = mutableMapOf<String, Long>()
    // cmId -> last time we ACCEPTED a buzz from that contact
    private val lastAccepted = mutableMapOf<String, Long>()
    // cmIds we've accepted a buzz from under ONCE mode, until we message them
    private val onceConsumed = mutableSetOf<String>()

    private val _shakes = MutableSharedFlow<BuzzShake>(extraBufferCapacity = 8)
    val shakes: SharedFlow<BuzzShake> = _shakes.asSharedFlow()

    /** True if we may send a buzz now (past the per-contact send cooldown). */
    @Synchronized
    fun canSend(cmId: String, now: Long = System.currentTimeMillis()): Boolean {
        val last = lastSent[cmId] ?: return true
        return now - last >= SEND_COOLDOWN_MS
    }

    @Synchronized
    fun markSent(cmId: String, now: Long = System.currentTimeMillis()) { lastSent[cmId] = now }

    /** Seconds left on the send cooldown, or 0 if ready. */
    @Synchronized
    fun sendCooldownRemaining(cmId: String, now: Long = System.currentTimeMillis()): Long {
        val last = lastSent[cmId] ?: return 0
        return ((SEND_COOLDOWN_MS - (now - last)) / 1000L).coerceAtLeast(0)
    }

    /** Decide whether an incoming buzz from [cmId] is accepted right now. */
    @Synchronized
    fun accept(cmId: String, now: Long = System.currentTimeMillis()): Boolean {
        val freq = frequency.value
        if (freq == BuzzFrequency.ONCE) {
            if (onceConsumed.contains(cmId)) return false
            onceConsumed.add(cmId)
            lastAccepted[cmId] = now
            return true
        }
        val every = freq.everyMs ?: return true
        val last = lastAccepted[cmId]
        if (last != null && now - last < every) return false
        lastAccepted[cmId] = now
        return true
    }

    /** Sending a person a message re-opens ONCE-mode buzzes from them. */
    @Synchronized
    fun onMessagedContact(cmId: String) { onceConsumed.remove(cmId) }

    fun requestShake(chatCmId: String) { _shakes.tryEmit(BuzzShake(chatCmId)) }

    @Synchronized
    fun clear() {
        lastSent.clear(); lastAccepted.clear(); onceConsumed.clear()
    }
}
