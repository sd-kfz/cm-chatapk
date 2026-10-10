package org.cmchat.app.tor

/**
 * A network change (WiFi ⇄ mobile, a "No service" gap) while Tor is ONLINE no
 * longer restarts Tor. A restart tears the onion down and republishes it — for
 * a minute or more nobody can reach me, and a change every few minutes (a
 * phone on the move) can keep me unreachable almost all the time.
 *
 * Instead Tor is told the network changed (DisableNetwork 1 → 0: drop the dead
 * connections and rebuild circuits at once) while the onion service stays
 * published, and we wait for a circuit. Only if none comes back within
 * [WAIT_MS] do we fall back to the old full restart — so the worst case is
 * the old behaviour, 90 s later.
 *
 * Pure logic (the control-port calls are passed in), so it is unit-tested.
 */
object SoftReconnect {
    const val WAIT_MS = 90_000L
    /** Tor needs a moment after the kick before "circuit established" means anything. */
    const val SETTLE_MS = 3_000L
    const val POLL_MS = 1_000L

    /** @return true = circuits are back with the onion still up; false = do a full restart. */
    suspend fun run(
        kick: () -> Unit,
        circuitUp: () -> Boolean,
        log: (String) -> Unit,
        sleep: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
        clock: () -> Long = System::currentTimeMillis,
        waitMs: Long = WAIT_MS,
    ): Boolean {
        log("network changed — soft reconnect (my onion stays published, no Tor restart)")
        try {
            kick()
        } catch (e: Exception) {
            log("soft reconnect refused by Tor (${e.javaClass.simpleName}) — full Tor restart")
            return false
        }
        sleep(SETTLE_MS)
        val end = clock() + waitMs
        while (clock() < end) {
            if (runCatching(circuitUp).getOrDefault(false)) {
                log("soft reconnect: circuits are back — reachable at the same address")
                return true
            }
            sleep(POLL_MS)
        }
        log("soft reconnect: no circuit after ${waitMs / 1000}s — full Tor restart")
        return false
    }
}
