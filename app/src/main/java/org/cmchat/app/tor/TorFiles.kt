package org.cmchat.app.tor

import android.content.Context
import java.io.File
import java.security.SecureRandom


object TorFiles {


    fun dir(context: Context): File = context.getDir("TorService", Context.MODE_PRIVATE)


    fun wipe(context: Context) {
        val d = runCatching { dir(context) }.getOrNull() ?: return
        runCatching {
            d.walkBottomUp().forEach { f ->
                if (f.isFile) {
                    runCatching {
                        val len = f.length().toInt().coerceIn(1, 1 shl 20)
                        val junk = ByteArray(len)
                        SecureRandom().nextBytes(junk)
                        f.outputStream().use { it.write(junk); it.flush() }
                    }
                    runCatching { f.delete() }
                } else {
                    runCatching { f.delete() }
                }
            }
        }
    }


    fun wipeAsync(context: Context) {
        val app = context.applicationContext
        Thread { wipe(app) }.apply { isDaemon = true }.start()
    }
}
