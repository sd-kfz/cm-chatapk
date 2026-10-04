package org.cmchat.app.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.cmchat.app.tor.TorService
import org.cmchat.app.tor.TorStatus

/**
 * Optional cover traffic (OFF by default). When on, it sends decoy frames to a
 * random contact at random intervals during an active session, so an observer
 * can't tell WHEN you're really messaging. Decoy frames are padded + sealed by
 * the exact same path as real frames and silently discarded by the receiver, so
 * they're indistinguishable on the wire. Costs extra battery + data — the UI
 * says so honestly.
 */
object CoverTraffic {

    private const val MIN_GAP_MS = 20_000L
    private const val MAX_GAP_MS = 90_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var job: Job? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(on: Boolean) {
        _enabled.value = on
        if (on) start() else stop()
    }

    private fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive && _enabled.value) {
                delay((MIN_GAP_MS..MAX_GAP_MS).random())
                if (!_enabled.value) break
                if (TorService.status.value is TorStatus.Online) {
                    MessageService.contactIds().randomOrNull()?.let { MessageService.sendCover(it) }
                }
            }
        }
    }

    fun stop() { job?.cancel(); job = null }
}
