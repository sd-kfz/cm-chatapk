package org.cmchat.app.settings

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.cmchat.app.buzz.BuzzFrequency
import org.cmchat.app.buzz.BuzzPolicy
import org.cmchat.app.chat.SelfTimer
import org.cmchat.app.tools.ToolsState
import org.cmchat.app.vault.VaultSettings


object AppSettings {

    val textSize = MutableStateFlow(0)


    fun textScale(step: Int): Float = 1f + step.coerceIn(-6, 6) * 0.035f


    val generalTimer = MutableStateFlow(SelfTimer.OFF)


    val invisibleMode = MutableStateFlow(true)


    fun startupPresence() { invisibleMode.value = true }


    val stayReachable = MutableStateFlow(false)


    val decoyEnabled = MutableStateFlow(false)
    val decoyName = MutableStateFlow("Notes to self")
    val decoyAtTop = MutableStateFlow(true)


    val sessionWindowEnabled = MutableStateFlow(false)
    const val SESSION_WINDOW_MS = 6 * 60 * 60_000L


    @Volatile
    var lastUnlockMs: Long = 0L

    fun sessionStillValid(now: Long = System.currentTimeMillis()): Boolean =
        sessionWindowEnabled.value && lastUnlockMs > 0 && now - lastUnlockMs < SESSION_WINDOW_MS


    @Volatile
    var appContext: Context? = null




    fun restoreFrom(s: VaultSettings) {
        generalTimer.value = SelfTimer.fromLabel(s.defaultSelfTimer).takeIf { it != SelfTimer.VIEW_ONCE } ?: SelfTimer.OFF
        BuzzPolicy.frequency.value = BuzzFrequency.entries.firstOrNull { it.name == s.buzzFrequency } ?: BuzzFrequency.H1
        decoyEnabled.value = s.decoyEnabled
        decoyName.value = s.decoyName
        decoyAtTop.value = s.decoyAtTop
        ToolsState.calcEnabled.value = s.toolCalc
        ToolsState.notesEnabled.value = s.toolNotes
        ToolsState.flashlightEnabled.value = s.toolFlash
    }


    fun applyTo(s: VaultSettings): VaultSettings = s.copy(
        defaultSelfTimer = generalTimer.value.label,
        buzzFrequency = BuzzPolicy.frequency.value.name,
        decoyEnabled = decoyEnabled.value,
        decoyName = decoyName.value,
        decoyAtTop = decoyAtTop.value,
        toolCalc = ToolsState.calcEnabled.value,
        toolNotes = ToolsState.notesEnabled.value,
        toolFlash = ToolsState.flashlightEnabled.value,
    )


    val savedChoices: Flow<Unit> = combine(
        listOf<Flow<Any>>(generalTimer, BuzzPolicy.frequency, decoyEnabled, decoyName,
            decoyAtTop, ToolsState.calcEnabled, ToolsState.notesEnabled, ToolsState.flashlightEnabled),
    ) { }
}
