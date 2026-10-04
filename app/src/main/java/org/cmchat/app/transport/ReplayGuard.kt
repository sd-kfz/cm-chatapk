package org.cmchat.app.transport

/**
 * Anti-replay sliding window (IPsec-style) keyed by a string (e.g.
 * "<contactCmId>:<senderSessionId>"). Each authenticated frame carries a
 * per-sender monotonic sequence number; this accepts a sequence at most once and
 * rejects duplicates and frames older than [window] behind the highest seen.
 *
 * The sender's session id changes each app run (sequence counters reset to 0 on
 * reboot), so a fresh session opens a new window rather than being rejected — a
 * replayed frame WITHIN a session is still caught. RAM-only; bounded in size.
 */
class ReplayGuard(private val window: Int = 64, private val maxKeys: Int = 512) {

    private class W {
        var highest = -1L
        var mask = 0L   // bit i set => (highest - i) has been seen
    }

    private val map = HashMap<String, W>()

    /** @return true to ACCEPT (first time seen), false to REJECT (replay / too old / bad). */
    @Synchronized
    fun check(key: String, seq: Long): Boolean {
        if (seq < 0) return false
        if (map.size > maxKeys && !map.containsKey(key)) map.clear()
        val w = map.getOrPut(key) { W() }
        return when {
            w.highest < 0L -> { w.highest = seq; w.mask = 1L; true }     // first in this window
            seq > w.highest -> {                                         // newer than anything seen
                val shift = seq - w.highest
                w.mask = if (shift >= 64L) 1L else ((w.mask shl shift.toInt()) or 1L)
                w.highest = seq
                true
            }
            else -> {                                                   // seq <= highest
                val diff = w.highest - seq
                if (diff >= window) false                               // too old (outside window)
                else {
                    val bit = 1L shl diff.toInt()
                    if (w.mask and bit != 0L) false                     // duplicate
                    else { w.mask = w.mask or bit; true }
                }
            }
        }
    }

    @Synchronized
    fun clear() = map.clear()
}
