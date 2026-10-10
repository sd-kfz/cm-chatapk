package org.cmchat.app.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Off-main-thread vault writes. A save re-encrypts with the session's cached
 * key ([VaultManager] — no Argon2id) and replaces the file atomically. All
 * writes go through ONE single-threaded lane, so they never overlap, and a
 * burst of changes collapses into one write of the newest state. The in-RAM
 * [VaultData] in the UI is updated by the caller; this just persists it.
 */
object VaultIO {

    // limitedParallelism(1): a private single-thread lane, never the main thread.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Persist [data] in the background. False when the vault is locked (nothing queued). */
    fun save(manager: VaultManager, data: VaultData): Boolean {
        if (!manager.queueSave(data)) return false
        flushSoon(manager)
        return true
    }

    /** Write whatever is queued, on the vault lane. */
    fun flushSoon(manager: VaultManager) {
        scope.launch {
            runCatching { manager.flush() }
                .onFailure { org.cmchat.app.diag.Diag.e("vault", "save failed", it) }
        }
    }

    /**
     * Keep the vault key for ONE late save (the decoy's new address, which only
     * exists a moment after the app has locked). Released automatically after
     * [timeoutMs] if that save never comes, so the key can't linger.
     */
    fun holdForLateSave(manager: VaultManager, timeoutMs: Long = 90_000L): VaultManager.LateSave? {
        val late = manager.holdForLateSave() ?: return null
        scope.launch { delay(timeoutMs); late.release() }
        return late
    }
}
