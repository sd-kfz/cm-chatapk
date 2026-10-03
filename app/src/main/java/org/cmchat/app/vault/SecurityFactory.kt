package org.cmchat.app.vault

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import org.cmchat.app.crypto.CryptoManager
import java.io.File

/** Builds a VaultManager backed by the on-device libsodium native library. */
object SecurityFactory {
    fun create(filesDir: File): VaultManager {
        val ls = LazySodiumAndroid(SodiumAndroid())
        return VaultManager(CryptoManager(ls), File(filesDir, "vault"))
    }
}
