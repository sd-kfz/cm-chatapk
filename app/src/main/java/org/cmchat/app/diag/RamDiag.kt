package org.cmchat.app.diag

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

/**
 * RAM diagnostics — its OWN separate log, kept apart from the Tor/[Diag] log so
 * neither list gets crowded. It samples the process's used JVM heap over time
 * and labels each sample with the currently-enabled features, so you can see
 * which combinations cost the most memory. (Android can't attribute heap to a
 * single feature exactly, so this shows total used RAM under the active feature
 * set, sampled on a timer — an indicator, not a precise per-feature breakdown.)
 */
object RamDiag {

    data class Sample(val atMs: Long, val usedMb: Long, val maxMb: Long, val features: String)

    private const val CAP = 200
    private val _samples = MutableStateFlow<List<Sample>>(emptyList())
    val samples: StateFlow<List<Sample>> = _samples.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /** Peak used MB seen while sampling, for a quick "worst so far" readout. */
    val peakMb = MutableStateFlow(0L)

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                sampleNow()
                delay(2000)
            }
        }
    }

    fun stop() { job?.cancel(); job = null }

    fun sampleNow() {
        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        val maxMb = rt.maxMemory() / (1024 * 1024)
        if (usedMb > peakMb.value) peakMb.value = usedMb
        val s = Sample(System.currentTimeMillis(), usedMb, maxMb, activeFeatures())
        _samples.value = (_samples.value + s).takeLast(CAP)
    }

    private fun activeFeatures(): String {
        val f = mutableListOf<String>()
        if (org.cmchat.app.tor.TorService.status.value is org.cmchat.app.tor.TorStatus.Online) f += "tor"
        if (org.cmchat.app.tor.ServerController.status.value is org.cmchat.app.tor.ServerStatus.Online) f += "onion"
        if (org.cmchat.app.tools.ToolsState.calcEnabled.value) f += "calc"
        if (org.cmchat.app.tools.ToolsState.notesEnabled.value) f += "notes"
        if (org.cmchat.app.tools.ToolsState.flashlightEnabled.value) f += "torch"
        if (org.cmchat.app.tools.Flashlight.on.value) f += "torch-on"
        return if (f.isEmpty()) "idle" else f.joinToString("+")
    }

    /** "Enable all functionalities" so their RAM cost shows up in the samples. */
    fun enableAllFeatures() {
        org.cmchat.app.tools.ToolsState.calcEnabled.value = true
        org.cmchat.app.tools.ToolsState.notesEnabled.value = true
        org.cmchat.app.tools.ToolsState.flashlightEnabled.value = true
        sampleNow()
    }

    fun clear() { _samples.value = emptyList(); peakMb.value = 0 }
}
