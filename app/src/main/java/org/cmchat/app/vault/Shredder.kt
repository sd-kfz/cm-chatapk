package org.cmchat.app.vault

import android.content.Context
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom


object Shredder {


    val tripped = kotlinx.coroutines.flow.MutableStateFlow(false)


    val epoch = kotlinx.coroutines.flow.MutableStateFlow(0)


    fun reset() {
        if (!tripped.value) return
        tripped.value = false
        epoch.value = epoch.value + 1
    }

    private val scope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)


    fun trip(context: Context) {
        val ctx = context.applicationContext
        tripped.value = true

        runCatching { org.cmchat.app.guard.GuardController.wipeRamOnly() }
        runCatching { org.cmchat.app.transport.MessageService.zeroKeys() }
        runCatching { org.cmchat.app.transport.CoverTraffic.stop() }
        runCatching { org.cmchat.app.tor.TorService.stop(ctx) }

        scope.launch {
            kotlinx.coroutines.delay(800)
            runCatching { shredAll(ctx) }
        }
    }


    fun shredAll(context: Context) {
        val ctx = context.applicationContext
        val targets = buildList {
            add(ctx.filesDir)
            add(ctx.cacheDir)
            runCatching { ctx.codeCacheDir }.getOrNull()?.let { add(it) }
            runCatching { ctx.noBackupFilesDir }.getOrNull()?.let { add(it) }
            ctx.externalCacheDir?.let { add(it) }
            ctx.getExternalFilesDir(null)?.let { add(it) }

            ctx.filesDir.parentFile?.let { dataDir ->
                add(File(dataDir, "databases"))
                add(File(dataDir, "shared_prefs"))
            }


            runCatching { ctx.getDir("TorService", Context.MODE_PRIVATE) }.getOrNull()?.let { add(it) }
        }
        targets.forEach { shredContents(it) }
    }


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
            val len = f.length().toInt().coerceIn(1, 1 shl 20)
            val junk = ByteArray(len)
            SecureRandom().nextBytes(junk)
            f.outputStream().use { it.write(junk); it.flush() }
        }
        runCatching { f.delete() }
    }
}
