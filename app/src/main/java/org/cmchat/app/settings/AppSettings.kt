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

/**
 * App-wide settings the whole app reads live. The ones a user chooses are
 * SAVED in the encrypted vault and restored at unlock ([restoreFrom] /
 * [savedChoices]) — a setting that silently forgets after a restart (a decoy
 * that turns itself off, a Buzz listener that turns itself back on) is a
 * safety failure, not a cosmetic one.
 */
object AppSettings {
    /** Strip EXIF/GPS from every photo before sending. Default ON. */
    val metadataScrub = MutableStateFlow(true)

    /** App-wide text size step, -6..+6 (0 = default), loaded from the vault. */
    val textSize = MutableStateFlow(0)

    /** Font-scale multiplier for a text size step (about 0.79x .. 1.21x). */
    fun textScale(step: Int): Float = 1f + step.coerceIn(-6, 6) * 0.035f

    /**
     * General self-timer applied to ALL messages (Settings-only). Default OFF.
     * A per-message timer, when set, overrides this for that one message. Saved.
     */
    val generalTimer = MutableStateFlow(SelfTimer.OFF)

    /**
     * Invisible: messages still arrive but are held as "Missed" (no notification,
     * nothing tells the sender). INVISIBLE AT EVERY STARTUP — the default here
     * (a fresh process), and again after Exit or closing the app
     * ([startupPresence]). Never switched on automatically otherwise: minimising
     * and re-unlocking keep whatever you chose. Only your tap changes it.
     */
    val invisibleMode = MutableStateFlow(true)

    /** The app was exited or closed: the next start is a fresh one — Invisible. */
    fun startupPresence() { invisibleMode.value = true }

    /**
     * Keep a minimal "buzz-listener" alive after the app is swiped away, so a
     * Buzz can still reach me while the full app is closed. Default ON. Saved.
     */
    val buzzListenerWhenClosed = MutableStateFlow(true)

    /**
     * Keep the FULL server running after the app is closed, until the user taps
     * Exit. Default OFF, per run. When ON it also forces Cerberus OFF and the
     * Kill Timer OFF (so they can't wipe while you're deliberately staying reachable).
     */
    val stayReachable = MutableStateFlow(false)

    /**
     * Decoy chat (default OFF): a fake, renamable contact shown on the Friends
     * screen. Tapping it = silent instant Exit + RAM wipe, no confirmation. Its
     * name and whether it sits at the top (vs bottom) are configurable. Saved.
     */
    val decoyEnabled = MutableStateFlow(false)
    val decoyName = MutableStateFlow("Notes to self")
    val decoyAtTop = MutableStateFlow(true)

    /**
     * 6-hour session window (item 7). When ON, a successful unlock stays valid
     * for 6h so returning to the app doesn't re-ask (in-process only — a cold
     * start always re-asks, since keys are never persisted to disk). Default OFF.
     */
    val sessionWindowEnabled = MutableStateFlow(false)
    const val SESSION_WINDOW_MS = 6 * 60 * 60_000L

    /** Monotonic time of the last successful unlock (RAM only). */
    @Volatile
    var lastUnlockMs: Long = 0L

    fun sessionStillValid(now: Long = System.currentTimeMillis()): Boolean =
        sessionWindowEnabled.value && lastUnlockMs > 0 && now - lastUnlockMs < SESSION_WINDOW_MS

    /**
     * App context for posting notifications from background (buzz listener).
     * Application context only — never an Activity — so it cannot leak a window.
     */
    @Volatile
    var appContext: Context? = null

    // ---- saved choices: vault <-> the live flows ---------------------------------

    /** Put the saved choices back into the live settings (at unlock). */
    fun restoreFrom(s: VaultSettings) {
        generalTimer.value = SelfTimer.fromLabel(s.defaultSelfTimer).takeIf { it != SelfTimer.VIEW_ONCE } ?: SelfTimer.OFF
        BuzzPolicy.frequency.value = BuzzFrequency.entries.firstOrNull { it.name == s.buzzFrequency } ?: BuzzFrequency.H1
        buzzListenerWhenClosed.value = s.buzzWhenClosed
        decoyEnabled.value = s.decoyEnabled
        decoyName.value = s.decoyName
        decoyAtTop.value = s.decoyAtTop
        ToolsState.calcEnabled.value = s.toolCalc
        ToolsState.notesEnabled.value = s.toolNotes
        ToolsState.flashlightEnabled.value = s.toolFlash
    }

    /** [s] with the live choices written into it (what gets saved). */
    fun applyTo(s: VaultSettings): VaultSettings = s.copy(
        defaultSelfTimer = generalTimer.value.label,
        buzzFrequency = BuzzPolicy.frequency.value.name,
        buzzWhenClosed = buzzListenerWhenClosed.value,
        decoyEnabled = decoyEnabled.value,
        decoyName = decoyName.value,
        decoyAtTop = decoyAtTop.value,
        toolCalc = ToolsState.calcEnabled.value,
        toolNotes = ToolsState.notesEnabled.value,
        toolFlash = ToolsState.flashlightEnabled.value,
    )

    /** Emits whenever any saved choice changes (and once with the current values). */
    val savedChoices: Flow<Unit> = combine(
        listOf<Flow<Any>>(generalTimer, BuzzPolicy.frequency, buzzListenerWhenClosed, decoyEnabled, decoyName,
            decoyAtTop, ToolsState.calcEnabled, ToolsState.notesEnabled, ToolsState.flashlightEnabled),
    ) { }
}
