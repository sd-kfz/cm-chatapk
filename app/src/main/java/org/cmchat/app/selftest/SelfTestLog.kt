package org.cmchat.app.selftest

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The self-attack harness' OWN log — deliberately kept separate from the Tor
 * [org.cmchat.app.diag.Diag] log so adversarial findings never crowd (or get
 * lost in) the normal diagnostics. RAM-only, bounded. Debug-only: the harness
 * that writes here is gated by BuildConfig.DEBUG.
 */
object SelfTestLog {

    enum class Severity { INFO, OK, LOW, MEDIUM, HIGH }

    /** One finding: what was attacked, what (if anything) broke, how bad. */
    data class Finding(
        val atMs: Long,
        val attack: String,
        val result: String,
        val severity: Severity,
    )

    private const val CAP = 200
    private val lock = Any()
    private val _findings = MutableStateFlow<List<Finding>>(emptyList())
    val findings: StateFlow<List<Finding>> = _findings.asStateFlow()

    val running = MutableStateFlow(false)

    fun record(attack: String, result: String, severity: Severity) {
        synchronized(lock) {
            _findings.value = (_findings.value + Finding(
                System.currentTimeMillis(), attack, result, severity,
            )).takeLast(CAP)
        }
    }

    fun clear() { synchronized(lock) { _findings.value = emptyList() } }
}
