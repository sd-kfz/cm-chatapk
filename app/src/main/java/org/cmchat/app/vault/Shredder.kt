package org.cmchat.app.vault

import android.content.Context
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom

/**
 * The Shredder PIN (reverse-PIN) wipes EVERYTHING the app can delete on disk —
 * vault, keys, salt, friends list, settings, caches, databases, shared prefs,
 * code cache, no-backup files, and any external cache — not just the vault key.
 * The only thing it cannot remove is the installed app binary itself (that
 * needs the user to confirm an uninstall). The wipe overwrites file contents
 * first (best-effort against casual recovery; flash wear-levelling means it is
 * not a forensic guarantee) and then deletes.
 */
object Shredder {

    /**
     * Set the moment the Shredder (duress) PIN is entered. While true the
     * lock screen shows ONLY "Error. Please restart the app." and accepts NO
     * input — no new-PIN prompt, no uninstall prompt, nothing that hints at a
     * wipe. RAM-only, so it lasts exactly until the process restarts.
     */
    val tripped = kotlinx.coroutines.flow.MutableStateFlow(false)

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /**
     * Duress: show the fake error immediately, then silently stop everything
     * and erase. Order: flag (UI switches at once) → RAM + keys → engine off →
     * disk shred (off the main thread).
     */
    fun trip(context: Context) {
        val ctx = context.applicationContext
        tripped.value = true
        // Every step on its own: one failing can never skip the rest or the shred.
        runCatching { org.cmchat.app.guard.GuardController.wipeRamOnly() }   // chats, tools, buzz, diag, notifications, server
        runCatching { org.cmchat.app.transport.MessageService.zeroKeys() }
        runCatching { org.cmchat.app.transport.CoverTraffic.stop() }
        runCatching { org.cmchat.app.tor.BuzzListenerService.stop(ctx) }
        runCatching { org.cmchat.app.tor.TorService.stop(ctx) }
        // Process-level scope: the shred must finish even if the screen goes away.
        scope.launch {
            kotlinx.coroutines.delay(800)   // let Tor finish shutting down before shredding its dir
            runCatching { shredAll(ctx) }
        }
    }

    /** Irreversibly erase all recoverable on-disk app data. */
    fun shredAll(context: Context) {
        val ctx = context.applicationContext
        val targets = buildList {
            add(ctx.filesDir)
            add(ctx.cacheDir)
            runCatching { ctx.codeCacheDir }.getOrNull()?.let { add(it) }
            runCatching { ctx.noBackupFilesDir }.getOrNull()?.let { add(it) }
            ctx.externalCacheDir?.let { add(it) }
            ctx.getExternalFilesDir(null)?.let { add(it) }
            // databases/ and shared_prefs/ live next to filesDir under the data dir.
            ctx.filesDir.parentFile?.let { dataDir ->
                add(File(dataDir, "databases"))
                add(File(dataDir, "shared_prefs"))
            }
            // tor-android's working dir (cached consensus/descriptors/state/cookie
            // + the bridge torrc). getDir("TorService") = <dataDir>/app_TorService.
            runCatching { ctx.getDir("TorService", Context.MODE_PRIVATE) }.getOrNull()?.let { add(it) }
        }
        targets.forEach { shredContents(it) }
    }

    /** Overwrite then delete every file under [dir], then the directory itself. */
    private fun shredContents(dir: File?) {
        if (dir == null || !dir.exists()) return
        runCatching {
            dir.walkBottomUp().forEach { f ->
                if (f.isFile) overwriteAndDelete(f) else runCatching { f.delete() }
            }
        }
    }

    private fun overwriteAndDelete(f: File) {
        runCatching {
            val len = f.length().toInt().coerceIn(1, 1 shl 20) // cap overwrite buffer at 1 MiB
            val junk = ByteArray(len)
            SecureRandom().nextBytes(junk)
            f.outputStream().use { it.write(junk); it.flush() }
        }
        runCatching { f.delete() }
    }
}
