package org.cmchat.app.tor


object SoftReconnect {
    const val WAIT_MS = 90_000L

    const val SETTLE_MS = 3_000L
    const val POLL_MS = 1_000L


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
