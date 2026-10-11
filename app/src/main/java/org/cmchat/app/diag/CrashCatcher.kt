package org.cmchat.app.diag

import android.content.Context
import org.cmchat.app.BuildConfig
import java.io.File


object CrashCatcher {


    const val ENABLED = true
    private const val FILE = "last_crash.txt"
    private val writeToDisk get() = ENABLED && BuildConfig.DEBUG

    fun install(context: Context) {
        val app = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->

            if (writeToDisk) runCatching {
                val sw = java.io.StringWriter()
                ex.printStackTrace(java.io.PrintWriter(sw))
                crashFile(app).writeText(Redact.scrub("thread=${thread.name}\n$sw"))
            }



            runCatching { org.cmchat.app.chat.ChatStore.clearAll() }
            runCatching { org.cmchat.app.transport.MessageService.zeroKeys() }
            runCatching { org.cmchat.app.tor.ServerController.stop() }
            prev?.uncaughtException(thread, ex)
        }
    }


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
