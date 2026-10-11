package org.cmchat.app.selftest

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow


object SelfTestLog {

    enum class Severity { INFO, OK, LOW, MEDIUM, HIGH }


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
