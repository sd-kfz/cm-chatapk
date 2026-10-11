package org.pocketcalc.app

import android.content.Context
import android.content.Intent
import java.io.File
import java.security.MessageDigest


class Secret(private var secret: String?) {
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


object SecretStore {
    private const val FILE = "k"

    fun load(ctx: Context): String? = runCatching {
        File(ctx.filesDir, FILE).takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
    }.getOrNull()

    fun save(ctx: Context, key: String?) {
        val f = File(ctx.filesDir, FILE)
        runCatching { if (key == null) f.delete() else f.writeText(key) }
    }
}


object Target {
    const val TARGET = "89723f11b398d645c900cb3e5d4cd3ca7ec7d5b4ed59bd5f4c18c6abd8824e6d"

    fun matches(pkg: String): Boolean = sha256(pkg) == TARGET

    fun open(ctx: Context): Boolean = runCatching {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val hit = ctx.packageManager.queryIntentActivities(launcher, 0)
            .firstOrNull { matches(it.activityInfo.packageName) } ?: return false
        ctx.startActivity(Intent(launcher)
            .setClassName(hit.activityInfo.packageName, hit.activityInfo.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
