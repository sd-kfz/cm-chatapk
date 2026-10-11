package org.cmchat.app.tools

import android.content.Context
import java.io.File


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
