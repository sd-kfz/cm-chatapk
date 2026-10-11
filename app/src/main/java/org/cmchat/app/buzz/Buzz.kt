package org.cmchat.app.buzz

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow


enum class BuzzFrequency(val label: String, val everyMs: Long?) {
    H1("Every 1h", 60 * 60_000L),
    H12("Every 12h", 12 * 60 * 60_000L),
    H24("Every 24h", 24 * 60 * 60_000L),
    ONCE("Once only", null);

    companion object {
        fun fromLabel(l: String): BuzzFrequency = entries.firstOrNull { it.label == l } ?: H1
    }
}


data class BuzzShake(val chatCmId: String, val at: Long = System.currentTimeMillis())


object BuzzPolicy {


    const val SEND_COOLDOWN_MS = 5 * 60_000L


    val frequency = MutableStateFlow(BuzzFrequency.H1)


    private val lastSent = mutableMapOf<String, Long>()

    private val lastAccepted = mutableMapOf<String, Long>()

    private val onceConsumed = mutableSetOf<String>()

    private val _shakes = MutableSharedFlow<BuzzShake>(extraBufferCapacity = 8)
    val shakes: SharedFlow<BuzzShake> = _shakes.asSharedFlow()


    @Synchronized
    fun canSend(cmId: String, now: Long = System.currentTimeMillis()): Boolean {
        val last = lastSent[cmId] ?: return true
        return now - last >= SEND_COOLDOWN_MS
    }

    @Synchronized
    fun markSent(cmId: String, now: Long = System.currentTimeMillis()) { lastSent[cmId] = now }


    @Synchronized
    fun sendCooldownRemainingMs(cmId: String, now: Long = System.currentTimeMillis()): Long {
        val last = lastSent[cmId] ?: return 0
        return (SEND_COOLDOWN_MS - (now - last)).coerceAtLeast(0)
    }


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


    @Synchronized
    fun onMessagedContact(cmId: String) { onceConsumed.remove(cmId) }


    @Synchronized
    fun onOpenedConversation(cmId: String) { onceConsumed.remove(cmId) }

    fun requestShake(chatCmId: String) { _shakes.tryEmit(BuzzShake(chatCmId)) }

    @Synchronized
    fun clear() {
        lastSent.clear(); lastAccepted.clear(); onceConsumed.clear()
    }
}
