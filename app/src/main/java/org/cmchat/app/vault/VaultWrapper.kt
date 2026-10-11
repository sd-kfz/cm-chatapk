package org.cmchat.app.vault


interface VaultWrapper {

    fun wrap(plain: ByteArray): ByteArray


    fun unwrap(blob: ByteArray): ByteArray?


    fun isWrapped(blob: ByteArray): Boolean =
        blob.size >= MAGIC.size + IV_LEN + GCM_TAG &&
            MAGIC.indices.all { blob[it] == MAGIC[it] }


    val active: Boolean

    companion object {

        val MAGIC = byteArrayOf(0x43, 0x4D, 0x4B, 0x57, 0x52, 0x41, 0x50, 0x31)
        const val IV_LEN = 12
        const val GCM_TAG = 16


        val NONE: VaultWrapper = object : VaultWrapper {
            override fun wrap(plain: ByteArray) = plain
            override fun unwrap(blob: ByteArray) = blob
            override fun isWrapped(blob: ByteArray) = false
            override val active = false
        }
    }
}
