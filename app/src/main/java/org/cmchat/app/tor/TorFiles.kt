package org.cmchat.app.tor

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * tor-android keeps its DataDirectory on disk at
 * `context.getDir("TorService")` → /data/data/<pkg>/app_TorService/, holding
 * `torrc`, `torrc-defaults` and `data/` (cached consensus + descriptors, the
 * state file, the control-auth cookie). None of our messages, identity keys or
 * onion private key live here (the onion service is an in-memory ADD_ONION, and
 * the onion key is stored only in the encrypted vault) — but the cached network
 * data would otherwise reveal "this device used Tor recently" after the user
 * Exits. So we wipe this directory on teardown/exit and on the Shredder path,
 * keeping it only for the duration of a running session (reconnects reuse it).
 */
object TorFiles {

    /** The whole tor-android working dir (torrc + torrc-defaults + data/). */
    fun dir(context: Context): File = context.getDir("TorService", Context.MODE_PRIVATE)

    /** Overwrite + delete everything under the Tor working dir. Safe to call
     * while Tor is stopping (unlinking open files is fine on Linux). */
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

    /** Fire-and-forget wipe on a daemon thread, so teardown never blocks. */
    fun wipeAsync(context: Context) {
        val app = context.applicationContext
        Thread { wipe(app) }.apply { isDaemon = true }.start()
    }
}
