package org.cmchat.app.vault

import kotlinx.serialization.json.Json
import org.cmchat.app.crypto.CryptoManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

sealed interface UnlockResult {
    data class Success(val data: VaultData) : UnlockResult
    object WrongPin : UnlockResult

    object Duress : UnlockResult
}


class VaultManager(val crypto: CryptoManager, dir: File, wrapper: VaultWrapper = VaultWrapper.NONE) {

    private val vault = Vault(dir, wrapper)



    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }


    private val io = Any()


    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = java.lang.Object()


    internal val argon2Runs = AtomicInteger()


    internal val files: Vault get() = vault

    private class Session(var key: ByteArray, var salt: ByteArray) {

        var pending: VaultData? = null
        var writing = false

        var holds = 0
        var closed = false
    }

    private var session: Session? = null


    private val closing = ArrayList<Session>()

    fun firstRunNeeded(): Boolean = !vault.exists()

    fun isUnlocked(): Boolean = synchronized(lock) { session != null }




    fun createVault(pin: String, faceName: String): VaultData = synchronized(io) {
        val (pk, sk) = crypto.newIdentityKeypair()
        val face = Face(
            id = crypto.randomHex(8),
            name = faceName.ifBlank { "Wanderer" },
            publicKey = pk,
            secretKey = sk,
        )
        val data = VaultData(faces = listOf(face))
        val salt = crypto.randomSalt()
        val key = derive(pin, salt)
        vault.write(salt, seal(key, data))
        startSession(Session(key, salt))
        data
    }


    fun unlock(pin: String): UnlockResult {
        awaitLateSaves()
        synchronized(io) {
            writeClosing()
            val current = vault.read()
            if (current != null) {
                val key = derive(pin, current.salt)
                val data = open(current.sealed, key)
                if (data != null) {
                    vault.discardLeftovers()
                    startSession(Session(key, current.salt))
                    migrateWrap(current, key, data)
                    return UnlockResult.Success(data)
                }
                key.fill(0)
            }
            vault.readPrevious()?.let { prev ->
                val key = derive(pin, prev.salt)
                val data = open(prev.sealed, key)
                if (data != null) {
                    vault.restorePrevious()
                    startSession(Session(key, prev.salt))
                    migrateWrap(prev, key, data)
                    return UnlockResult.Success(data)
                }
                key.fill(0)
            }
            val reversed = pin.reversed()
            if (reversed != pin) {
                for (blob in listOfNotNull(current, vault.readPrevious())) {
                    val k = derive(reversed, blob.salt)
                    val opens = open(blob.sealed, k) != null
                    k.fill(0)
                    if (opens) {
                        wipe()
                        return UnlockResult.Duress
                    }
                }
            }
            return UnlockResult.WrongPin
        }
    }


    private fun migrateWrap(blob: Vault.Blob, key: ByteArray, data: VaultData) {
        if (!vault.wrapActive() || blob.wasWrapped) return
        runCatching { vault.write(blob.salt, seal(key, data)) }
            .onFailure { org.cmchat.app.diag.Diag.e("vault", "wrap migration deferred", it) }
    }


    fun verify(pin: String): Boolean = synchronized(io) {
        val current = vault.read() ?: return false
        val k = derive(pin, current.salt)
        try {
            crypto.open(current.sealed, k)?.also { it.fill(0) } != null
        } finally {
            k.fill(0)
        }
    }




    fun queueSave(data: VaultData): Boolean = synchronized(lock) {
        val s = session ?: return false
        s.pending = data
        true
    }


    fun flush() = synchronized(io) {
        writeClosing()
        synchronized(lock) { session }?.let { writePending(it) }
    }

    private fun writeClosing() {
        val owed = synchronized(lock) { ArrayList(closing) }
        owed.forEach { writePending(it) }
    }


    private fun writePending(s: Session) {
        val data = synchronized(lock) {
            val d = s.pending ?: return
            s.pending = null
            s.writing = true
            d
        }
        try {
            vault.write(s.salt, seal(s.key, data))
        } catch (e: Exception) {
            org.cmchat.app.diag.Diag.e("vault", "save failed", e)

            synchronized(lock) { if (!s.closed && s.pending == null) s.pending = data }
        } finally {
            synchronized(lock) {
                s.writing = false
                settle(s)
            }
        }
    }




    fun lock() = synchronized(lock) {
        val s = session ?: return
        session = null
        s.closed = true
        closing += s
        settle(s)
    }


    private fun settle(s: Session) {
        if (s.closed && s.pending == null && !s.writing && s.holds == 0) {
            s.key.fill(0)
            closing.remove(s)
            lock.notifyAll()
        }
    }

    private fun startSession(s: Session) = synchronized(lock) {
        session?.let { old ->
            old.closed = true
            if (old !in closing) closing += old
            settle(old)
        }
        session = s
    }


    fun holdForLateSave(): LateSave? = synchronized(lock) {
        val s = session ?: return null
        s.holds++
        LateSave(
            onSave = { d ->
                synchronized(lock) {
                    s.pending = d
                    s.holds--
                    settle(s)
                }
                VaultIO.flushSoon(this)
            },
            onRelease = { synchronized(lock) { s.holds--; settle(s) } },
        )
    }

    class LateSave internal constructor(
        private val onSave: (VaultData) -> Unit,
        private val onRelease: () -> Unit,
    ) {
        private val done = AtomicBoolean(false)
        fun save(data: VaultData) { if (done.compareAndSet(false, true)) onSave(data) }
        fun release() { if (done.compareAndSet(false, true)) onRelease() }
    }

    private fun awaitLateSaves(maxMs: Long = LATE_SAVE_WAIT_MS) = synchronized(lock) {
        val end = System.currentTimeMillis() + maxMs
        while (closing.any { it.holds > 0 }) {
            val left = end - System.currentTimeMillis()
            if (left <= 0) break
            lock.wait(left)
        }
    }




    fun changePin(oldPin: String, newPin: String): Boolean {
        if (!isValidNewPin(newPin)) return false
        synchronized(io) {
            flush()
            val current = vault.read() ?: return false
            val oldKey = derive(oldPin, current.salt)
            val data = try { open(current.sealed, oldKey) } finally { oldKey.fill(0) } ?: return false
            val newSalt = crypto.randomSalt()
            val newKey = derive(newPin, newSalt)
            val ok = try {
                vault.replaceForPinChange(newSalt, seal(newKey, data)) { blob ->
                    blob.salt.contentEquals(newSalt) && open(blob.sealed, newKey) == data
                }
            } catch (e: Exception) {
                org.cmchat.app.diag.Diag.e("vault", "PIN change failed", e)
                false
            }
            if (!ok) { newKey.fill(0); return false }
            synchronized(lock) {

                closing.forEach { it.pending = null; it.holds = 0; it.key.fill(0) }
                closing.clear()
                val s = session
                if (s != null) {
                    s.key.fill(0)
                    s.key = newKey
                    s.salt = newSalt
                } else newKey.fill(0)
                lock.notifyAll()
            }
            return true
        }
    }


    fun wipe() = synchronized(io) {
        synchronized(lock) {
            session?.key?.fill(0)
            session = null
            closing.forEach { it.key.fill(0) }
            closing.clear()
            lock.notifyAll()
        }
        vault.wipe()
    }



    private fun derive(pin: String, salt: ByteArray): ByteArray {
        argon2Runs.incrementAndGet()
        return crypto.deriveKey(pin, salt)
    }

    private fun seal(key: ByteArray, data: VaultData): ByteArray {
        val plain = json.encodeToString(VaultData.serializer(), data).toByteArray(Charsets.UTF_8)
        try {
            return crypto.seal(plain, key)
        } finally {
            plain.fill(0)
        }
    }

    private fun open(sealed: ByteArray, key: ByteArray): VaultData? {
        val plain = crypto.open(sealed, key) ?: return null
        return try {
            json.decodeFromString(VaultData.serializer(), String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        } finally {
            plain.fill(0)
        }
    }

    companion object {
        const val MIN_PASSCODE = 4
        const val MAX_PASSCODE = 56
        private const val LATE_SAVE_WAIT_MS = 20_000L


        fun isValidNewPin(pin: String): Boolean =
            pin.length in MIN_PASSCODE..MAX_PASSCODE && pin != pin.reversed()

        enum class Strength(val label: String) { WEAK("Weak"), FAIR("Fair"), GOOD("Good"), STRONG("Strong") }


        fun strength(pin: String): Strength {
            val kinds = listOf(
                pin.any { it.isDigit() }, pin.any { it.isLowerCase() },
                pin.any { it.isUpperCase() }, pin.any { !it.isLetterOrDigit() },
            ).count { it }
            return when {
                pin.length < 6 -> Strength.WEAK
                pin.length < 8 -> if (kinds >= 3) Strength.FAIR else Strength.WEAK
                pin.length < 12 -> if (kinds >= 3) Strength.GOOD else Strength.FAIR
                else -> if (kinds >= 2) Strength.STRONG else Strength.GOOD
            }
        }
    }
}
