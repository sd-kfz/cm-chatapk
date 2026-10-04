package org.cmchat.app.diag

import android.content.Context
import org.cmchat.app.BuildConfig
import java.io.File

/**
 * Global uncaught-exception handler with two jobs:
 *
 *  1) ANTI-FORENSICS (all builds): before the process dies, zero the decrypted
 *     messages and in-memory key material so a crash can't leave plaintext
 *     behind in a heap dump. The stack trace is SCRUBBED ([Redact]) so no full
 *     onion address or key blob is ever written anywhere.
 *
 *  2) DEBUG-PHASE aid (debug builds only): write the scrubbed trace to one
 *     app-internal file so the crash can be shown on the Diagnostics screen on
 *     next launch, then deleted. This is the ONLY thing besides the vault that
 *     touches disk, and it is compiled out of release by the [BuildConfig.DEBUG]
 *     gate — a release build never writes a crash file.
 */
object CrashCatcher {

    /** Debug-only crash file is written only when this AND BuildConfig.DEBUG. */
    const val ENABLED = true
    private const val FILE = "last_crash.txt"
    private val writeToDisk get() = ENABLED && BuildConfig.DEBUG

    fun install(context: Context) {
        val app = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            // 1) Persist a SCRUBBED trace for debugging (debug builds only).
            if (writeToDisk) runCatching {
                val sw = java.io.StringWriter()
                ex.printStackTrace(java.io.PrintWriter(sw))
                crashFile(app).writeText(Redact.scrub("thread=${thread.name}\n$sw"))
            }
            // 2) Anti-forensics: zero decrypted messages + key material before we
            //    hand off to the system handler that ends the process. Each is
            //    isolated so one failure can't stop the others.
            runCatching { org.cmchat.app.chat.ChatStore.clearAll() }
            runCatching { org.cmchat.app.transport.MessageService.zeroKeys() }
            runCatching { org.cmchat.app.tor.ServerController.stop() }
            prev?.uncaughtException(thread, ex)
        }
    }

    /** Read (and delete) a crash from a previous run, if any (debug only). */
    fun consume(context: Context): String? {
        if (!writeToDisk) return null
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
