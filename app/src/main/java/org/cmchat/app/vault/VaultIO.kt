package org.cmchat.app.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Off-main-thread vault writes. A vault save runs Argon2id (64 MiB, hundreds of
 * ms) + a disk write, which would ANR if done on the UI thread. All saves go
 * through a SINGLE-threaded dispatcher so they also stay serialized — two
 * concurrent writers could otherwise race on the one vault file. The in-RAM
 * [VaultData] in the UI is updated optimistically by the caller; this just
 * persists it in the background.
 */
object VaultIO {

    // limitedParallelism(1): a private single-thread lane, so writes never
    // overlap and never touch the main thread.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    fun save(manager: VaultManager, pin: String, data: VaultData) {
        scope.launch {
            runCatching { manager.save(pin, data) }
                .onFailure { org.cmchat.app.diag.Diag.e("vault", "save failed", it) }
        }
    }
}
