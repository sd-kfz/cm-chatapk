package org.cmchat.app.diag

import android.content.Context
import java.io.File

/**
 * DEBUG-PHASE aid: the ONE thing allowed to touch disk besides the vault.
 * Installs a default uncaught-exception handler that writes a single crash
 * file to app-internal storage; on next launch the crash is shown on the
 * Diagnostics screen and deleted immediately. Wiped by every wipe path and by
 * uninstall.
 *
 * IMPORTANT: set [ENABLED] = false (or delete this class) before any
 * real-safety release — it is the only on-disk exception trace. See
 * PROGRESS.md.
 */
object CrashCatcher {

    const val ENABLED = true
    private const val FILE = "last_crash.txt"

    fun install(context: Context) {
        if (!ENABLED) return
        val app = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            runCatching {
                val sw = java.io.StringWriter()
                ex.printStackTrace(java.io.PrintWriter(sw))
                crashFile(app).writeText("thread=${thread.name}\n$sw")
            }
            prev?.uncaughtException(thread, ex)
        }
    }

    /** Read (and delete) a crash from a previous run, if any. */
    fun consume(context: Context): String? {
        if (!ENABLED) return null
        val f = crashFile(context)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        f.delete()
        return text
    }

    fun delete(context: Context) {
        runCatching { crashFile(context).delete() }
    }

    private fun crashFile(context: Context): File = File(context.filesDir, FILE)
}
