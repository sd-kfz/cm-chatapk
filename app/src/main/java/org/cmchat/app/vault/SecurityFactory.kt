package org.cmchat.app.vault

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import org.cmchat.app.crypto.CryptoManager
import java.io.File

/**
 * The ONE VaultManager of this process, backed by the on-device libsodium. One
 * instance because it holds the session key: a recreated screen must reuse it
 * (and lock it), never strand a key in an orphaned copy.
 */
object SecurityFactory {
    @Volatile private var instance: VaultManager? = null

    fun create(filesDir: File): VaultManager = instance ?: synchronized(this) {
        instance ?: VaultManager(CryptoManager(LazySodiumAndroid(SodiumAndroid())), File(filesDir, "vault"))
            .also { instance = it }
    }

    /** Exit / close / screen gone: the vault key leaves RAM (after any queued save). */
    fun lockIfCreated() { instance?.lock() }
}
