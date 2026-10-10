package org.cmchat.app.tools

import android.content.Context
import java.io.File

/**
 * Cover mode: the app opens to a WORKING calculator instead of the lock
 * screen. Tapping the SAME key 10 times in a row gets you in (the first time,
 * whichever key you use becomes THE key); tapping "reset" 20 times in a row
 * forgets that key, so the next 10-in-a-row picks a new one.
 *
 * The switch has to be readable BEFORE the vault is unlocked, so it lives in a
 * tiny plain file next to the vault (wiped with everything else). It only
 * hides the chats from someone glancing at the phone — the PIN still guards
 * everything.
 */
object CoverMode {
    private const val FILE = "cover"

    fun isOn(ctx: Context): Boolean = runCatching { File(ctx.filesDir, FILE).exists() }.getOrDefault(false)

    fun secretKey(ctx: Context): String? = runCatching {
        File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
    }.getOrNull()

    fun setOn(ctx: Context, on: Boolean) {
        val f = File(ctx.filesDir, FILE)
        runCatching { if (on) { if (!f.exists()) f.writeText("") } else f.delete() }
    }

    fun setSecretKey(ctx: Context, key: String?) {
        val f = File(ctx.filesDir, FILE)
        if (f.exists()) runCatching { f.writeText(key ?: "") }
    }
}

/**
 * The secret-sequence logic (pure, unit-tested): feed it every key pressed.
 * [RESET] is the plain "reset" text.
 */
class CoverSecret(private var secret: String?) {
    enum class Action { NONE, OPEN, FORGET }

    private var last: String? = null
    private var run = 0

    val key: String? get() = secret

    fun press(k: String): Action {
        if (k == last) run++ else { last = k; run = 1 }
        if (k == RESET) {
            if (run >= RESET_TIMES) { run = 0; last = null; secret = null; return Action.FORGET }
            return Action.NONE
        }
        if (run >= SAME_TIMES) {
            run = 0; last = null
            if (secret == null) secret = k
            return if (secret == k) Action.OPEN else Action.NONE
        }
        return Action.NONE
    }

    companion object {
        const val RESET = "reset"
        const val SAME_TIMES = 10
        const val RESET_TIMES = 20
    }
}
