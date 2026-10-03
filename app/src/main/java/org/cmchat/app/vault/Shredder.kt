package org.cmchat.app.vault

import android.content.Context
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
