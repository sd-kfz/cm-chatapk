package org.cmchat.app.vault

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


object VaultIO {


    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))


    fun save(manager: VaultManager, data: VaultData): Boolean {
        if (!manager.queueSave(data)) return false
        flushSoon(manager)
        return true
    }


    fun flushSoon(manager: VaultManager) {
        scope.launch {
            runCatching { manager.flush() }
                .onFailure { org.cmchat.app.diag.Diag.e("vault", "save failed", it) }
        }
    }


    fun holdForLateSave(manager: VaultManager, timeoutMs: Long = 90_000L): VaultManager.LateSave? {
        val late = manager.holdForLateSave() ?: return null
        scope.launch { delay(timeoutMs); late.release() }
        return late
    }
}
