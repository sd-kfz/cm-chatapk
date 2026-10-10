package org.cmchat.app.vault

import kotlinx.serialization.json.Json
import org.cmchat.app.crypto.CryptoManager
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

sealed interface UnlockResult {
    data class Success(val data: VaultData) : UnlockResult
    object WrongPin : UnlockResult
    /** The real PIN was entered in exact reverse (duress). Vault already wiped. */
    object Duress : UnlockResult
}

/**
 * Owns the vault lifecycle: first-run creation, unlock (incl. duress
 * detection), saving, PIN change and wipe.
 *
 * ARGON2ID RUNS ONLY WHEN A PIN IS TYPED. [unlock] / [createVault] derive the
 * vault key ONCE (Argon2id, 64 MiB, ops 2 — never weakened) and keep it in RAM
 * for the session; every save re-encrypts with that cached key, so a save
 * costs a secretbox (microseconds) instead of a 64 MiB hash. [lock] wipes the
 * key — after any save still queued has been written. Only typing a PIN
 * (unlock, verify, change) ever runs Argon2id.
 *
 * DURESS: no stored PIN is needed. On a failed unlock we try the reversed
 * input; if THAT opens the vault, the user typed their real PIN backwards, so
 * we wipe and report Duress. Palindrome PINs are refused at creation so that
 * reverse != forward.
 */
class VaultManager(val crypto: CryptoManager, dir: File) {

    private val vault = Vault(dir)

    // encodeDefaults: every setting is written explicitly, so changing a default
    // in a later build can never silently change a saved choice.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Serializes EVERY read and write of the vault file. Never taken on the main thread. */
    private val io = Any()

    /** Guards the session state below; only ever held for a moment. */
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
    private val lock = java.lang.Object()

    /** Argon2id runs in this process. Tests use it to prove that saves never run it. */
    internal val argon2Runs = AtomicInteger()

    /** Test hook into the file layer (simulated crashes). */
    internal val files: Vault get() = vault

    private class Session(var key: ByteArray, var salt: ByteArray) {
        /** The newest state not yet written. Older ones are simply superseded. */
        var pending: VaultData? = null
        var writing = false
        /** Late saves still to come (the decoy's new address). */
        var holds = 0
        var closed = false
    }

    private var session: Session? = null

    /** Locked sessions that still owe the disk a save; each key is wiped right after. */
    private val closing = ArrayList<Session>()

    fun firstRunNeeded(): Boolean = !vault.exists()

    fun isUnlocked(): Boolean = synchronized(lock) { session != null }

    // ---- create / unlock ---------------------------------------------------------

    /** First run: a new vault, unlocked. One Argon2id. */
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

    /**
     * Check the PIN (one Argon2id) and keep the derived key for this session.
     * Also settles a PIN change that was cut off by a crash: the PIN that opens
     * the live vault confirms it; the OLD PIN opening the kept copy means it
     * never finished, and the old vault goes back.
     */
    fun unlock(pin: String): UnlockResult {
        awaitLateSaves()
        synchronized(io) {
            writeClosing()                      // whatever a locked session still owes the disk
            val current = vault.read()
            if (current != null) {
                val key = derive(pin, current.salt)
                val data = open(current.sealed, key)
                if (data != null) {
                    vault.discardLeftovers()
                    startSession(Session(key, current.salt))
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

    /** Is [pin] the vault PIN? (One Argon2id, no side effects — no duress wipe.) For PIN gates. */
    fun verify(pin: String): Boolean = synchronized(io) {
        val current = vault.read() ?: return false
        val k = derive(pin, current.salt)
        try {
            crypto.open(current.sealed, k)?.also { it.fill(0) } != null
        } finally {
            k.fill(0)
        }
    }

    // ---- saving ------------------------------------------------------------------

    /**
     * Queue [data] to be written by [flush] with the cached key. Only the newest
     * queued state is ever written. Returns false when locked.
     */
    fun queueSave(data: VaultData): Boolean = synchronized(lock) {
        val s = session ?: return false
        s.pending = data
        true
    }

    /** Write whatever is queued (no Argon2id). Call on a background thread. */
    fun flush() = synchronized(io) {
        writeClosing()
        synchronized(lock) { session }?.let { writePending(it) }
    }

    private fun writeClosing() {
        val owed = synchronized(lock) { ArrayList(closing) }
        owed.forEach { writePending(it) }
    }

    /** Caller holds [io], so the key and salt can't change underneath. */
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
            // Kept for the next save while logged in (unless something newer came).
            synchronized(lock) { if (!s.closed && s.pending == null) s.pending = data }
        } finally {
            synchronized(lock) {
                s.writing = false
                settle(s)
            }
        }
    }

    // ---- locking -----------------------------------------------------------------

    /**
     * Log out of the vault: the key leaves RAM. A save still queued or being
     * written finishes first; the key is wiped the moment it's done.
     */
    fun lock() = synchronized(lock) {
        val s = session ?: return
        session = null
        s.closed = true
        closing += s
        settle(s)
    }

    /** Caller holds [lock]. A closed session with nothing left to write loses its key. */
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

    /**
     * The decoy locks the app at once, but its NEW onion address only exists a
     * moment later. This keeps the key for exactly ONE more save ([LateSave.save]);
     * [LateSave.release] (or the caller's timeout) gives it up. The next unlock
     * waits briefly for it, so it can never overwrite newer data.
     */
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

    // ---- PIN change / wipe -------------------------------------------------------

    /**
     * Change the vault PIN: verify [oldPin] against the vault on disk, re-seal
     * the SAME data under [newPin] with a fresh salt, and swap it in crash-safely
     * ([Vault.replaceForPinChange]: written, read back and checked BEFORE it goes
     * live; the old copy is kept until then). Returns false (nothing changed) if
     * [newPin] is invalid or [oldPin] is wrong. Runs Argon2id twice (both PINs
     * were just typed), so call it off the main thread.
     */
    fun changePin(oldPin: String, newPin: String): Boolean {
        if (!isValidNewPin(newPin)) return false
        synchronized(io) {
            flush()                                   // nothing queued may be lost or re-keyed late
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
                // Locked sessions still holding the OLD key must never write again.
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

    /** Erase the vault file and every vault key in RAM. */
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

    // ---- sealing -------------------------------------------------------------------

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        argon2Runs.incrementAndGet()
        return crypto.deriveKey(pin, salt)
    }

    private fun seal(key: ByteArray, data: VaultData): ByteArray {
        val plain = json.encodeToString(VaultData.serializer(), data).toByteArray(Charsets.UTF_8)
        try {
            return crypto.seal(plain, key)
        } finally {
            plain.fill(0)                       // zero the serialized plaintext
        }
    }

    private fun open(sealed: ByteArray, key: ByteArray): VaultData? {
        val plain = crypto.open(sealed, key) ?: return null
        return try {
            json.decodeFromString(VaultData.serializer(), String(plain, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        } finally {
            plain.fill(0)                       // zero the decrypted plaintext
        }
    }

    companion object {
        const val MIN_PASSCODE = 4
        const val MAX_PASSCODE = 56
        private const val LATE_SAVE_WAIT_MS = 20_000L

        /**
         * Accepts any NEW passcode of 4..56 characters (any mix of digits,
         * letters and symbols) that is not a palindrome. Argon2id (cryptoPwHash)
         * hashes the raw bytes, so any length/charset derives a valid key; the
         * passcode is treated as OPAQUE BYTES only and never interpreted. The
         * palindrome rejection is required: the Shredder fires on the passcode
         * typed BACKWARDS, so a passcode must differ from its reverse. Short
         * passcodes are allowed (the UI encourages 8+ via [strength]); existing
         * vaults with longer legacy passcodes still unlock (unlock doesn't check).
         */
        fun isValidNewPin(pin: String): Boolean =
            pin.length in MIN_PASSCODE..MAX_PASSCODE && pin != pin.reversed()

        enum class Strength(val label: String) { WEAK("Weak"), FAIR("Fair"), GOOD("Good"), STRONG("Strong") }

        /** A simple, honest strength hint: length first, then character variety. */
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
