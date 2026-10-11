package org.cmchat.app.vault

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import org.cmchat.app.crypto.CryptoManager
import java.io.File


object SecurityFactory {
    @Volatile private var instance: VaultManager? = null

    fun create(filesDir: File): VaultManager = instance ?: synchronized(this) {


        instance ?: VaultManager(
            CryptoManager(LazySodiumAndroid(SodiumAndroid())),
            File(filesDir, "vault"),
            KeystoreWrap(),
        ).also { instance = it }
    }


    fun lockIfCreated() { instance?.lock() }
}
