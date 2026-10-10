package org.cmchat.app.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.cmchat.app.chat.ChatStore
import org.cmchat.app.chat.MsgState
import org.cmchat.app.chat.SelfTimer
import org.cmchat.app.crypto.CmId
import org.cmchat.app.crypto.CmIdData
import org.cmchat.app.crypto.CryptoManager
import org.cmchat.app.diag.ConnDiag
import org.cmchat.app.diag.Redact
import org.cmchat.app.tor.ServerController
import org.cmchat.app.tor.TorService
import org.cmchat.app.tor.TorStatus
import java.io.File
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * The message spine, kept deliberately boring (modelled on the old Termux
 * CM-chat: a stable onion + a small listener, one request/response per frame):
 *
 *  ADD A FRIEND   knock = anonymous sealed box to their identity key. Sending
 *                 one immediately adds them on MY side as a PENDING friend, so
 *                 their acceptance (and any message) can reach me.
 *  ACCEPT         they tap Accept → they add me, and send KNOCK_ACCEPT over the
 *                 forward-secret channel. ANY authenticated frame from a pending
 *                 friend (accept or message) confirms them on my side. Pending
 *                 friends are re-knocked whenever Tor comes online (a knock or an
 *                 acceptance lost to a closed app is recovered); a pending add can
 *                 be CANCELLED (their request card is withdrawn).
 *  REMOVE         delete a friend (my side only), or TERMINATE (also removes me
 *                 from their list when it reaches them; kept until delivered).
 *  MESSAGES       every frame to a friend goes over the handshake in
 *                 [SecureChannel] (one-time prekey + X3DH + AEAD + receipt).
 *  DELIVERED      means THEIR phone said "stored" in an authenticated receipt.
 *                 While my vault is locked (minimised and re-locked, or the app
 *                 swiped away with the Buzz listener on) what arrives is HELD on
 *                 flash ([HeldInbox], sealed to my identity key) BEFORE the
 *                 receipt goes out, and replayed at the next unlock — nothing
 *                 is dropped and then counted as delivered.
 *  NO RETRY BUTTON  everything outgoing goes through the silent [Outbox]: it
 *                 retries in the background, and the screen never shows whether
 *                 a friend is online or offline.
 *
 * Every step is logged to the Connection diagnostic, redacted (no keys,
 * contents or full onion addresses). Chats live only in [ChatStore] (RAM).
 * Real delivery needs Tor ONLINE on a device; the loopback tests drive this exact
 * object over local sockets (only [dialer] — the Tor dial — is swapped).
 */
object MessageService {

    data class KnockRequest(val displayName: String, val cmId: String)

    private val _incomingKnocks = MutableStateFlow<List<KnockRequest>>(emptyList())
    val incomingKnocks: StateFlow<List<KnockRequest>> = _incomingKnocks.asStateFlow()

    /** Set by AppNav to persist an accepted contact into the vault. */
    @Volatile
    var onContactAccepted: ((KnockRequest) -> Unit)? = null

    /** Set by AppNav: a PENDING friend proved they accepted us → persist it. */
    @Volatile
    var onFriendConfirmed: ((cmId: String) -> Unit)? = null

    /** Set by AppNav to persist a contact's rotated address (old cmId -> new). */
    @Volatile
    var onContactAddressUpdated: ((oldCmId: String, newCmId: String) -> Unit)? = null

    /** Set by AppNav to persist a Team Clock the friend set (null = turned off) and
     * when they set it (newest wins). */
    @Volatile
    var onTeamClockChanged: ((cmId: String, value: String?, atMs: Long) -> Unit)? = null

    /** Set by AppNav: MY Team Clock change [atMs] reached that friend → stop re-sending it. */
    @Volatile
    var onTeamClockSynced: ((cmId: String, atMs: Long) -> Unit)? = null

    /** Set by AppNav: a friend's OWN nickname (from their acceptance, or they changed it). */
    @Volatile
    var onFriendName: ((cmId: String, name: String) -> Unit)? = null

    /** Set by AppNav: my nickname [name] reached that friend → stop re-sending it. */
    @Volatile
    var onNameConfirmed: ((cmId: String, name: String) -> Unit)? = null

    /** Set by AppNav: a friend TERMINATED (removed me from their list) → drop them too. */
    @Volatile
    var onFriendTerminated: ((cmId: String) -> Unit)? = null

    /** Set by AppNav: my TERMINATE reached that ex-friend → forget it was pending. */
    @Volatile
    var onTerminationDelivered: ((cmId: String) -> Unit)? = null

    /** Set by AppNav: a friend was active (sent me something real) → persist a
     * coarse "last seen" so "last seen recently" survives restarts and erases. */
    @Volatile
    var onPeerSeen: ((cmId: String, atMs: Long) -> Unit)? = null

    /** Set by AppNav: [friendCmId]'s phone confirmed it has my address [myCmId]
     * → persist, so it isn't sent again (until my address changes again). */
    @Volatile
    var onAddressConfirmed: ((friendCmId: String, myCmId: String) -> Unit)? = null

    /**
     * Set by AppNav: write any queued vault save NOW (blocking; called off the
     * main thread). Held records are shredded only after it returns, so a
     * friend's change that was held can't be lost between the two.
     */
    @Volatile
    var flushVault: (() -> Unit)? = null

    /** Where held frames are kept (filesDir/held). Set by MainActivity; tests use a temp dir. */
    @Volatile
    var heldDir: File? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var myName: String = ""
    private var myCmId: String? = null
    private var myPubHex: String? = null
    private var mySecHex: String? = null

    /** The forward-secret channel for my identity; null until configured. */
    @Volatile
    private var channel: SecureChannel? = null

    /**
     * The app was swiped away and only the Buzz listener runs. A Buzz still
     * notifies; everything else that arrives is HELD (the vault is locked) and
     * shows as "Missed Message" after the next unlock.
     */
    @Volatile
    var buzzOnlyMode: Boolean = false

    /** cmId -> decoded peer (onion + identity pubkey). Concurrent: read by the
     * incoming-connection threads while the UI adds/relinks contacts. */
    private val contacts = ConcurrentHashMap<String, CmIdData>()
    /** cmId -> my nickname for that friend. */
    private val names = ConcurrentHashMap<String, String>()
    /** Friends I added (knocked) who haven't confirmed yet. */
    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /**
     * A friend's OLD cmId -> their new one, after they rotated their onion (a
     * decoy does that). A chat screen still open on the old ID, and frames
     * already queued for it, keep reaching the friend instead of silently going
     * nowhere. RAM-only.
     */
    private val relinked = ConcurrentHashMap<String, String>()

    /** The friend's current cmId ([cmId] itself unless they moved address). */
    fun currentId(cmId: String): String {
        var id = cmId
        repeat(8) { id = relinked[id] ?: return id }   // bounded: never loops forever
        return id
    }

    /**
     * Held while a friend's chat moves to their new address, and while a chat
     * write picks which address to file under — so a message sent or received
     * right during the move can never land in the old (now hidden) chat. Never
     * held across network I/O or a vault save.
     */
    private val relinkLock = Any()

    /** Run a chat write under the friend's CURRENT id, atomically w.r.t. a move. */
    private inline fun <T> inChat(cmId: String, write: (String) -> T): T =
        synchronized(relinkLock) { write(currentId(cmId)) }

    /** The chat currently open in the foreground, or null. Set by ChatScreen. */
    @Volatile
    var activeChatCmId: String? = null

    /** Per-contact post-auth rate limit (drop a contact that floods us). */
    private val contactRate = RateLimiter(burst = 20, refillPerSec = 5.0)

    /** Incoming knocks: a burst of 6, then one per 20 s — a stranger can't spam. */
    private val knockRate = RateLimiter(burst = 6, refillPerSec = 1.0 / 20)
    /** A knock from someone I declined is ignored for this long. */
    private const val DECLINE_COOLDOWN_MS = 60 * 60_000L
    private val declinedAt = ConcurrentHashMap<String, Long>()
    /** I can't re-knock the same person faster than this (no mash-storms). */
    private const val KNOCK_RESEND_MS = 60_000L
    /** Automatic re-knock of a still-pending friend at most this often. */
    private const val KNOCK_AUTO_RESEND_MS = 10 * 60_000L
    private val knockSentAt = ConcurrentHashMap<String, Long>()
    /** Knocks that reached their phone THIS run: not re-sent automatically again
     * (their request card is already there) until the next app start. */
    private val knockDelivered: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Ex-friends I TERMINATED whose phones haven't received it yet. */
    private val terminations: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** friend cmId -> the address of MINE their phone confirmed (from the vault).
     * Anyone not on my current address gets it again until they confirm it. */
    private val addressConfirmed = ConcurrentHashMap<String, String>()

    /** friend cmId -> my nickname their phone confirmed (re-sent until it's my current one). */
    private val nameConfirmed = ConcurrentHashMap<String, String>()

    /** friend cmId -> when the Team Clock I hold for that chat was set (newest wins). */
    private val teamClockAt = ConcurrentHashMap<String, Long>()

    /** "Last seen" is persisted at most this often per friend (it's coarse anyway). */
    private const val SEEN_PERSIST_EVERY_MS = 30 * 60_000L
    private val seenPersistedAt = ConcurrentHashMap<String, Long>()

    /**
     * Wire protocol version. Bumped whenever the framing/crypto changes so two
     * peers on different builds detect the mismatch instead of failing silently.
     * See [SecureChannel.WIRE_VERSION] for what each version added.
     */
    const val WIRE_VERSION = SecureChannel.WIRE_VERSION

    /** Per-app-run session id + per-peer counters, and the anti-replay windows.
     * Process-lifetime, so re-configuring (e.g. a contact added) keeps them. */
    private val codec = InnerCodec()
    private val replayGuard = ReplayGuard()

    /** How long a sender waits for the contact's prekey reply / receipt over Tor. */
    private const val HANDSHAKE_READ_TIMEOUT_MS = 30_000

    /** Set true when an authenticated frame from a DIFFERENT wire version arrives;
     * the UI shows "Update both apps to the same version." */
    val versionMismatch = MutableStateFlow(false)

    /** Cap pending knock requests so a knock flood can't grow RAM without bound. */
    private const val MAX_PENDING_KNOCKS = 20

    /** Files go on their own queue per friend, so a big one never holds up messages. */
    private const val FILE_LANE = "#file"
    /** Incoming files at once (each can take up to 100 MB of RAM). */
    private const val MAX_INCOMING_FILES = 1
    private val incomingFiles = java.util.concurrent.atomic.AtomicInteger(0)

    /** Silent background delivery (RAM-only). See [Outbox]. */
    private val outbox = Outbox(scope)
    @Volatile private var torWatch: Job? = null

    // ---- locked: HOLD, never drop -------------------------------------------------

    /** Unlocked, and everything held has been replayed: frames go straight into
     * the chat. Otherwise they are HELD ([HeldInbox]) until the next unlock. */
    @Volatile private var vaultOpen = false
    @Volatile private var vaultWanted = false
    /** Taken while deciding hold-vs-deliver and while replaying, so a new frame
     * can never overtake an older held one. Never held across network I/O. */
    private val holdLock = Any()
    @Volatile private var held: HeldInbox? = null

    /** MSG ids already put in a chat ("identity:msgId"): a frame re-sent after
     * a lost receipt never shows twice. Bounded; guarded by [holdLock]. */
    private const val MAX_DEDUP = 4096
    private val deliveredIds = object : LinkedHashMap<String, Boolean>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > MAX_DEDUP
    }
    /** MSG ids held but not replayed yet (guarded by [holdLock]). */
    private val heldIds = HashSet<String>()

    /**
     * How a socket to a friend's onion is opened. Production: through Tor (fails
     * closed if Tor is down). The loopback tests swap ONLY this, so everything
     * after the socket is the real code. Not reachable from outside the app.
     */
    @Volatile
    internal var dialer: (CmIdData) -> Socket = { peer -> torDial(peer) }

    /**
     * Anti-forensics: drop the identity key material, contact table and queued
     * frames held in RAM. Called from Exit, wipe paths and the crash handler;
     * safe to call anytime (the next configure() repopulates it). Held records
     * stay on flash, sealed to the identity key that just left RAM.
     */
    fun zeroKeys() {
        vaultWanted = false
        vaultOpen = false
        channel = null
        myName = ""
        myCmId = null
        myPubHex = null
        mySecHex = null
        held = null
        buzzOnlyMode = false
        contacts.clear()
        names.clear()
        pending.clear()
        outbox.clear()
        knockSentAt.clear()
        knockDelivered.clear()
        declinedAt.clear()
        relinked.clear()
        terminations.clear()
        addressConfirmed.clear()
        nameConfirmed.clear()
        teamClockAt.clear()
        seenPersistedAt.clear()
        knockRate.clear()
        contactRate.clear()
        synchronized(holdLock) { deliveredIds.clear(); heldIds.clear() }
        _incomingKnocks.value = emptyList()   // a stranger's name + ID must not survive a wipe
        activeChatCmId = null
        org.cmchat.app.vault.PendingVaultEdits.clear()
    }

    /** Is any identity key / friend table still in RAM? (Exit must leave none.) */
    internal fun keysInRam(): Boolean =
        channel != null || mySecHex != null || myPubHex != null || contacts.isNotEmpty()

    /** Drop every queued outgoing frame (wipe/exit paths). */
    fun clearOutbox() = outbox.clear()

    /**
     * The app was swiped away: queued chat content leaves RAM, but what the
     * friendship itself needs (my new address, an acceptance, a terminate, a
     * knock) keeps going out — otherwise a friend could silently lose me.
     */
    fun dropQueuedContent() = outbox.retainOnly { it.keepOnClose }

    /** Wipe paths (Cerberus, Kill, decoy): shred everything held, unread. */
    fun dropHeld() {
        heldDir?.let { HeldInbox.shredAll(it) }
        synchronized(holdLock) { heldIds.clear() }
    }

    fun configure(
        crypto: CryptoManager,
        myDisplayName: String,
        myIdentityPubHex: String,
        myIdentitySecHex: String,
        myCmId: String?,
        knownContactCmIds: List<String>,
        contactNames: Map<String, String> = emptyMap(),
        pendingCmIds: Collection<String> = emptyList(),
        pendingTerminations: Collection<String> = emptyList(),
        /** friend cmId -> the address of mine they confirmed (null/absent = never). */
        confirmedAddresses: Map<String, String> = emptyMap(),
        /** friend cmId -> my nickname they confirmed (absent = never). */
        confirmedNames: Map<String, String> = emptyMap(),
        /** friend cmId -> when the stored Team Clock was set. */
        teamClockTimes: Map<String, Long> = emptyMap(),
        /** friend cmId -> (value, setAt): MY Team Clock changes that haven't reached them yet. */
        unsyncedTeamClocks: Map<String, Pair<String, Long>> = emptyMap(),
    ) {
        this.myName = myDisplayName
        this.myCmId = myCmId
        this.myPubHex = myIdentityPubHex
        this.mySecHex = myIdentitySecHex
        this.channel = SecureChannel(crypto, myIdentityPubHex, myIdentitySecHex, codec, replayGuard)
        heldDir?.let { dir -> if (held == null) held = HeldInbox(dir, crypto) }
        // Update the friend table WITHOUT an empty moment: configure() re-runs on
        // every vault save, and a clear-then-refill would let a frame arriving
        // in between be dropped as "not from a known contact".
        // A friend who moved address this run (see [relinked]) stays on the new
        // one even if the vault still lists the old (it catches up after unlock).
        val fresh = HashMap<String, CmIdData>()
        knownContactCmIds.forEach { raw -> currentId(raw).let { id -> CmId.decode(id)?.let { fresh[id] = it } } }
        contacts.keys.retainAll(fresh.keys)
        contacts.putAll(fresh)
        val freshNames = contactNames.mapKeys { (id, _) -> currentId(id) }
        names.keys.retainAll(freshNames.keys)
        names.putAll(freshNames)
        val freshPending = pendingCmIds.map { currentId(it) }.toSet()
        pending.retainAll(freshPending)
        pending.addAll(freshPending)
        // What the vault says each friend confirmed; a confirmation that arrived
        // this run (not saved yet) is kept.
        confirmedAddresses.forEach { (id, mine) -> addressConfirmed.putIfAbsent(currentId(id), mine) }
        addressConfirmed.keys.retainAll(contacts.keys)
        confirmedNames.forEach { (id, n) -> nameConfirmed.putIfAbsent(currentId(id), n) }
        nameConfirmed.keys.retainAll(contacts.keys)
        teamClockTimes.forEach { (id, at) -> teamClockAt.merge(currentId(id), at) { a, b -> maxOf(a, b) } }
        ServerController.onIncoming = { socket -> handleIncoming(socket) }
        // Terminations not yet delivered (kept in the vault) go out again.
        pendingTerminations.forEach { id -> if (id !in terminations) queueTerminate(id) }
        // Whenever Tor comes (back) online, retry anything queued right away,
        // re-knock friends still pending, and re-send my address to anyone who
        // hasn't confirmed it.
        if (torWatch == null) {
            torWatch = scope.launch {
                launch {
                    TorService.status.collect {
                        if (it is TorStatus.Online) {
                            outbox.kickAll(); resendPendingKnocks(); resendAddress()
                            org.cmchat.app.tor.TorClock.log()   // diagnostics: is this phone's clock off?
                        }
                    }
                }
                // A soft reconnect after a network change: the onion stayed up, so
                // there is no Offline→Online edge — retry right away anyway.
                TorService.reconnects.collect { if (it > 0) { outbox.kickAll(); resendAddress() } }
            }
        }
        resendPendingKnocks()
        resendAddress()
        resendName()
        // My Team Clock changes that never reached them (kept in the vault) go again.
        unsyncedTeamClocks.forEach { (id, v) ->
            val cur = currentId(id)
            if (!outbox.has(cur) { it.replaceKey == "teamclock" && it.tag == v.second }) sendTeamClock(cur, v.first, v.second)
        }
    }

    // ---- outgoing ----------------------------------------------------------

    enum class KnockResult { QUEUED, NOT_READY, INVALID, SELF, TOO_SOON }

    /**
     * Add a friend: knock on [cmId]. They're added on MY side right away as a
     * PENDING friend (so their acceptance can reach me), and the knock is queued
     * for silent delivery — it keeps trying in the background until it lands.
     */
    fun sendKnock(cmId: String): KnockResult {
        val target = CmId.decode(cmId) ?: return KnockResult.INVALID
        val ch = channel; val myId = myCmId
        if (ch == null || myId == null) return KnockResult.NOT_READY
        if (cmId == myId || target.identityPubKeyHex.equals(myPubHex, ignoreCase = true)) return KnockResult.SELF
        val now = System.currentTimeMillis()
        val last = knockSentAt[cmId]
        if (last != null && now - last < KNOCK_RESEND_MS) return KnockResult.TOO_SOON
        knockSentAt[cmId] = now
        val alreadyFriend = contacts.containsKey(cmId) && cmId !in pending
        contacts.putIfAbsent(cmId, target)
        if (!alreadyFriend) pending.add(cmId)
        queueKnock(cmId, target)
        return KnockResult.QUEUED
    }

    /**
     * Queue an anonymous knock (or, with [withdraw], the withdrawal of one) to
     * [target]. It carries my CURRENT name + CMC-ID and a nonce for its receipt;
     * latest one per friend wins. Delivered = their phone's receipt says stored.
     */
    private fun queueKnock(cmId: String, target: CmIdData, withdraw: Boolean = false) {
        val ch = channel ?: return
        val myId = myCmId ?: return
        val nonce = java.security.SecureRandom().let { r -> ByteArray(SecureChannel.CHALLENGE).also { r.nextBytes(it) } }
        val payload = Messages.json.encodeToString(KnockPayload.serializer(),
            KnockPayload(myName, myId, withdraw, nonce = toHex(nonce))).toByteArray()
        val sealed = ch.sealKnock(payload, target.identityPubKeyHex)
        val what = if (withdraw) "request withdrawal" else "knock"
        ConnDiag.sys("Add friend: $what queued → ${Redact.onionShort(target.onion)}")
        outbox.enqueue(Outbox.Item(
            peer = cmId, label = what, replaceKey = "knock", keepOnClose = true,
            deliver = {
                withConnection(target) { s ->
                    s.soTimeout = HANDSHAKE_READ_TIMEOUT_MS
                    val ack = SecureWire.sendKnock(ch, s.getInputStream(), s.getOutputStream(), sealed, nonce,
                        target.identityPubKeyHex, onStage = { ConnDiag.out(it) })
                    requireStored(ack)
                }
            },
            onDelivered = {
                if (!withdraw) knockDelivered.add(cmId)
                ConnDiag.sys(if (withdraw) "Add friend: request withdrawn on their phone"
                    else "Add friend: knock delivered (their phone confirmed) — waiting for them to accept")
            },
        ))
    }

    /**
     * Re-knock every friend still PENDING (my knock, or their acceptance, may have
     * been lost when an app was closed). Once per app run once it's delivered,
     * and at most once per [KNOCK_AUTO_RESEND_MS] before that; their phone merges
     * it (no duplicate card) or, if they already accepted, simply re-sends the
     * acceptance — which resolves the pending.
     */
    internal fun resendPendingKnocks(now: Long = System.currentTimeMillis()) {
        if (channel == null || myCmId == null) return
        for (id in pending.toList()) {
            if (id in knockDelivered) continue
            val last = knockSentAt[id]
            if (last != null && now - last < KNOCK_AUTO_RESEND_MS) continue
            val target = contacts[id] ?: CmId.decode(id) ?: continue
            knockSentAt[id] = now
            queueKnock(id, target)
        }
    }

    /**
     * Cancel my still-pending add of [cmId]: forget them here and withdraw my
     * request card from their phone (best-effort, anonymous like the knock).
     */
    fun cancelPending(cmId: String): Boolean {
        val id = currentId(cmId)
        if (id !in pending) return false
        val target = contacts[id] ?: CmId.decode(id)
        forget(id)
        ConnDiag.sys("Add friend: request cancelled")
        if (target != null) queueKnock(id, target, withdraw = true)
        return true
    }

    /**
     * Delete a friend on MY side only (their phone isn't told): gone from the
     * list, the chat, the queue — and anything of theirs still held is shredded.
     */
    fun deleteFriend(cmId: String) {
        val id = currentId(cmId)
        val pub = contacts[id]?.identityPubKeyHex ?: CmId.decode(id)?.identityPubKeyHex
        forget(id)
        if (pub != null) scope.launch { synchronized(holdLock) { dropHeldFrom(pub) } }
        ConnDiag.sys("Friend deleted (my side)")
    }

    /**
     * TERMINATE: delete the friend here AND remove me from their list once it
     * reaches their phone. The caller keeps it (in the vault) until
     * [onTerminationDelivered], so it survives an app restart.
     */
    fun terminateFriend(cmId: String): Boolean {
        val id = currentId(cmId)
        val peer = contacts[id] ?: CmId.decode(id) ?: return false
        forget(id)
        ConnDiag.sys("Terminate: queued (removes you from their list when it reaches them)")
        queueTerminate(id, peer)
        return true
    }

    private fun queueTerminate(cmId: String, peer: CmIdData? = CmId.decode(cmId)) {
        if (peer == null || channel == null) return
        terminations.add(cmId)
        outbox.enqueue(Outbox.Item(peer = cmId, label = "terminate", replaceKey = "terminate", keepOnClose = true,
            deliver = {
                try {
                    sendSecure(peer, FrameType.TERMINATE, ByteArray(0))
                } catch (e: SecureWire.HandshakeFailed) {
                    // Their phone answered but no longer accepts me as a friend:
                    // they already dropped me — that IS the goal.
                    if (e.message?.startsWith("no prekey reply") != true) throw e
                }
            },
            stillWanted = { cmId in terminations },
            onDelivered = {
                terminations.remove(cmId)
                ConnDiag.sys("Terminate: delivered — you're off their list")
                onTerminationDelivered?.invoke(cmId)
            }))
    }

    /**
     * Drop [cmId] from every RAM table — friend, nickname, pending flag, old
     * addresses, queued frames, the chat itself. (The vault is the caller's.)
     */
    private fun forget(cmId: String) {
        val keys = HashSet<String>()
        synchronized(relinkLock) {
            keys += cmId
            relinked.keys.filter { currentId(it) == cmId }.forEach { keys += it }
            keys.forEach { k ->
                relinked.remove(k)
                contacts.remove(k); names.remove(k); pending.remove(k); addressConfirmed.remove(k)
                ChatStore.forget(k)
                if (activeChatCmId == k) activeChatCmId = null
            }
        }
        keys.forEach { k ->
            outbox.clearPeer(k); outbox.clearPeer(k + FILE_LANE)
            knockSentAt.remove(k); knockDelivered.remove(k); seenPersistedAt.remove(k)
        }
    }

    /** Is this friend still waiting to accept me? */
    fun isPending(cmId: String): Boolean = currentId(cmId) in pending

    /**
     * Fire-and-forget BUZZ: no retry, no state, no content. Rate-limited to one
     * per contact per [BuzzPolicy.SEND_COOLDOWN_MS]. Returns false if on
     * cooldown or not configured.
     */
    fun sendBuzz(cmId: String): Boolean {
        val chatCmId = currentId(cmId)
        val peer = contacts[chatCmId]
        if (channel == null || peer == null) return false
        if (!org.cmchat.app.buzz.BuzzPolicy.canSend(chatCmId)) return false
        org.cmchat.app.buzz.BuzzPolicy.markSent(chatCmId)
        scope.launch {
            runCatching { sendSecure(peer, FrameType.BUZZ, ByteArray(0)) }
                .onFailure { org.cmchat.app.diag.Diag.e("buzz", "send failed", it) }
        }
        return true
    }

    /**
     * Cover traffic: send a decoy frame to a contact. It goes through the exact
     * same handshake + padding + sealing as a real frame (so an observer can't
     * tell them apart) and the receiver silently discards it. Random inner size
     * within the base bucket so it looks like a short real message.
     */
    fun sendCover(cmId: String) {
        val peer = contacts[currentId(cmId)] ?: return
        if (channel == null) return
        val junk = ByteArray((8..400).random()).also { java.security.SecureRandom().nextBytes(it) }
        scope.launch {
            runCatching { sendSecure(peer, FrameType.COVER, junk) }
                .onFailure { org.cmchat.app.diag.Diag.e("cover", "send failed", it) }
        }
    }

    /** Contacts currently known (for the cover-traffic picker). */
    fun contactIds(): List<String> = contacts.keys.toList()

    /**
     * Send a text message. It appears in the chat immediately and is delivered
     * silently in the background (retried until it lands). Nothing on screen
     * reveals whether the friend is online.
     */
    fun sendText(cmId: String, text: String, timer: SelfTimer) {
        // Engine logs never go into a conversation (the composer says why).
        if (org.cmchat.app.chat.EngineLog.looksLikeLog(text)) return
        // Per-message timer wins; otherwise fall back to the general timer.
        val effective = if (timer != SelfTimer.OFF) timer
            else org.cmchat.app.settings.AppSettings.generalTimer.value
        val (chatCmId, msg) = inChat(cmId) { id -> id to ChatStore.addMine(id, text, effective) }
        // Messaging a person re-opens their "Once only" buzzes.
        org.cmchat.app.buzz.BuzzPolicy.onMessagedContact(chatCmId)
        if (channel == null || !contacts.containsKey(chatCmId)) return
        val payload = Messages.json.encodeToString(TextPayload.serializer(),
            TextPayload(msg.id, text, effective.label)).toByteArray()
        outbox.enqueue(Outbox.Item(
            peer = chatCmId, label = "message",
            deliver = { sendSecureTo(chatCmId, FrameType.MSG, payload) },
            // Erased / wiped / expired before delivery → never sent.
            stillWanted = { ChatStore.thread(currentId(chatCmId)).messages.any { it.id == msg.id } },
            onDelivered = { ChatStore.setState(currentId(chatCmId), msg.id, MsgState.SENT) },
        ))
    }

    enum class FileResult { QUEUED, TOO_BIG, EMPTY, NOT_READY }

    /**
     * Send a file that the caller already cleaned ([org.cmchat.app.media.MediaPolicy]).
     * It shows in the chat at once and is delivered silently in the background
     * like a message — on its own queue, so it never holds messages up — and
     * counts as delivered only on their final receipt (all pieces stored).
     * Over 100 MB is refused here too (defence in depth). RAM only.
     */
    fun sendFile(cmId: String, name: String, mime: String, file: org.cmchat.app.media.Chunked,
                 timer: SelfTimer): FileResult {
        if (file.size == 0L) return FileResult.EMPTY
        if (file.size > FileTransfer.MAX_BYTES) return FileResult.TOO_BIG
        if (channel == null) return FileResult.NOT_READY
        val effective = if (timer != SelfTimer.OFF) timer else org.cmchat.app.settings.AppSettings.generalTimer.value
        val safe = org.cmchat.app.media.FileNames.safe(name)
        val chatFile = org.cmchat.app.chat.ChatFile(safe, file.size, mime, file.pieces)
        val (chatCmId, msg) = inChat(cmId) { id -> id to ChatStore.addMine(id, safe, effective, chatFile) }
        if (!contacts.containsKey(chatCmId)) return FileResult.NOT_READY
        val rnd = java.security.SecureRandom()
        val fileId = ByteArray(FileTransfer.ID_BYTES).also { rnd.nextBytes(it) }
        val key = ByteArray(FileTransfer.KEY_BYTES).also { rnd.nextBytes(it) }
        val offer = Messages.json.encodeToString(FileOffer.serializer(), FileOffer(
            id = toHex(fileId), name = safe, size = file.size, mime = mime, key = toHex(key),
            chunks = file.pieces.size, selfTimer = effective.label)).toByteArray()
        val wanted = { ChatStore.thread(currentId(chatCmId)).messages.any { it.id == msg.id } }
        ConnDiag.sys("File: queued (${file.pieces.size} piece(s))")
        outbox.enqueue(Outbox.Item(
            peer = chatCmId + FILE_LANE, label = "file",
            deliver = { sendFileTo(chatCmId, offer, fileId, key, file.pieces, wanted) },
            stillWanted = wanted,
            onDelivered = { ChatStore.setState(currentId(chatCmId), msg.id, MsgState.SENT) },
        ))
        return FileResult.QUEUED
    }

    /** The normal chat Erase: wipes BOTH sides (theirs as soon as it reaches them). */
    fun sendErase(cmId: String) {
        val chatCmId = inChat(cmId) { id -> ChatStore.erase(id); id }
        if (channel == null || !contacts.containsKey(chatCmId)) return
        outbox.enqueue(Outbox.Item(peer = chatCmId, label = "erase", replaceKey = "erase",
            deliver = { sendSecureTo(chatCmId, FrameType.ERASE_CHAT, ByteArray(0)) }))
    }

    /**
     * Decoy tripped (this phone may be in someone else's hands): INSTANTLY wipe
     * MY side from RAM — every conversation, queued frame, buzz marker, note and
     * held frame — and queue a DECOY_ALERT to every friend. It does NOT instantly
     * destroy their copy: they see "Decoy chat triggered — chat erased." in the
     * chat, and it's erased for them once they leave it. Delivered silently in
     * the background (a friend who's offline gets it when they're back, while my
     * engine runs). The caller then rotates the onion address and locks the app.
     */
    fun tripDecoy(): Int {
        // Only CONFIRMED friends: a pending one never had a conversation with me.
        val peers = contacts.keys.filter { it !in pending }
        outbox.clear()
        ChatStore.clearAll()
        dropHeld()
        org.cmchat.app.buzz.BuzzPolicy.clear()
        org.cmchat.app.tools.ToolsState.clear()
        activeChatCmId = null
        ConnDiag.sys("Decoy tripped: my chats wiped")
        if (channel == null || peers.isEmpty()) {
            ConnDiag.sys("Decoy: no confirmed friends to signal")
            return 0
        }
        // Best-effort BURN signal: it wipes our chat on THEIR phone too — but only
        // when it reaches them (they must be online while my engine still runs).
        ConnDiag.sys("Decoy: burn signal sent to ${peers.size} friend(s) — it wipes our chat on " +
            "their phone when it reaches them (only while they're online)")
        peers.forEach { id ->
            outbox.enqueue(Outbox.Item(peer = id, label = "burn signal", replaceKey = "decoy", keepOnClose = true,
                deliver = { sendSecureTo(id, FrameType.DECOY_ALERT, ByteArray(0)) },
                onDelivered = { ConnDiag.out("burn signal delivered — a friend's copy of the chat is wiped") }))
        }
        return peers.size
    }

    /**
     * Share this conversation's Team Clock with the friend ([value] = canonical
     * offset, or "" to turn it off), stamped with when it was set ([atMs]) so
     * the NEWEST setting wins on both phones. Kept until their phone confirms
     * it — and re-sent after a restart from the vault ([onTeamClockSynced]).
     */
    fun sendTeamClock(cmId: String, value: String, atMs: Long = System.currentTimeMillis()): Boolean {
        val chatCmId = currentId(cmId)
        if (channel == null || !contacts.containsKey(chatCmId)) return false
        if (value.isNotEmpty() && org.cmchat.app.chat.TeamClock.decode(value) == null) return false
        teamClockAt.merge(chatCmId, atMs) { a, b -> maxOf(a, b) }
        val bytes = "$value|$atMs".toByteArray(Charsets.US_ASCII)
        outbox.enqueue(Outbox.Item(peer = chatCmId, label = "team clock", replaceKey = "teamclock",
            keepOnClose = true, tag = atMs,
            deliver = { sendSecureTo(chatCmId, FrameType.TEAM_CLOCK, bytes) },
            onDelivered = { onTeamClockSynced?.invoke(currentId(chatCmId), atMs) }))
        return true
    }

    /** My nickname changed: every friend gets it, until their phone confirms it. */
    fun setMyName(name: String) {
        myName = name
        resendName()
    }

    private fun resendName() {
        if (channel == null || myName.isBlank()) return
        val mine = myName
        for (id in contacts.keys.toList()) {
            if (id in pending || nameConfirmed[id] == mine) continue
            if (outbox.has(id) { it.replaceKey == "nick" && it.tag == mine }) continue
            outbox.enqueue(Outbox.Item(peer = id, label = "nickname", replaceKey = "nick", keepOnClose = true, tag = mine,
                deliver = { sendSecureTo(id, FrameType.NICKNAME, mine.toByteArray(Charsets.UTF_8)) },
                stillWanted = { myName == mine && nameConfirmed[currentId(id)] != mine },
                onDelivered = {
                    val now = currentId(id)
                    nameConfirmed[now] = mine
                    onNameConfirmed?.invoke(now, mine)
                }))
        }
    }

    /**
     * Tell every friend my new CMC-ID after rotating my onion. Authenticated by
     * my identity key inside the forward-secret frame (only I can produce it) —
     * the "signed" address-update. It is re-sent to each friend until THEIR
     * phone confirms it ([onAddressConfirmed], saved in the vault), across app
     * restarts — a friend who misses it would otherwise keep dialing a dead
     * address and never reach me.
     */
    fun sendAddressUpdate(newCmId: String) {
        if (channel == null) return
        myCmId = newCmId
        val friends = contacts.keys.filter { it !in pending }
        ConnDiag.sys("Telling ${friends.size} friend(s) my new address (re-sent until each one confirms)")
        resendAddress()
    }

    /** Queue my CURRENT address to every confirmed friend who hasn't confirmed it. */
    private fun resendAddress() {
        if (channel == null) return
        val mine = myCmId ?: return
        for (id in contacts.keys.toList()) {
            if (id in pending || addressConfirmed[id] == mine) continue
            // Already on its way (configure() re-runs on every vault save).
            if (outbox.has(id) { it.replaceKey == "addr" && it.tag == mine }) continue
            outbox.enqueue(Outbox.Item(peer = id, label = "address update", replaceKey = "addr", keepOnClose = true,
                tag = mine,
                deliver = { sendSecureTo(id, FrameType.ADDR_UPDATE, mine.toByteArray()) },
                stillWanted = { myCmId == mine && addressConfirmed[currentId(id)] != mine },
                onDelivered = { addressReached(id, mine) }))
        }
    }

    /** [friendCmId]'s phone has my address [mine] (an update or my acceptance reached it). */
    private fun addressReached(friendCmId: String, mine: String) {
        val now = currentId(friendCmId)
        if (!contacts.containsKey(now)) return
        addressConfirmed[now] = mine
        ConnDiag.sys("My address: confirmed by a friend's phone")
        onAddressConfirmed?.invoke(now, mine)
    }

    fun acceptKnock(req: KnockRequest) {
        ConnDiag.sys("Add friend: knock accepted → added as friend")
        CmId.decode(req.cmId)?.let { contacts[req.cmId] = it }
        pending.remove(req.cmId)   // if I had knocked them too, that settles it
        _incomingKnocks.value = _incomingKnocks.value.filterNot { it.cmId == req.cmId }
        onContactAccepted?.invoke(req)
        queueAccept(req.cmId)
    }

    /** Tell [cmId] we accepted (forward-secret, since we know their key) —
     * silently retried until it reaches them. It carries my CURRENT address, so
     * once it's delivered they have that address. */
    private fun queueAccept(cmId: String, via: String? = null) {
        val myId = myCmId ?: return
        if (channel == null || !contacts.containsKey(cmId)) return
        val payloadName = myName
        val payload = Messages.json.encodeToString(KnockPayload.serializer(),
            KnockPayload(payloadName, myId, yours = cmId)).toByteArray()
        val viaPeer = via?.let { CmId.decode(it) }
            ?.takeIf { it.identityPubKeyHex.equals(contacts[cmId]?.identityPubKeyHex, ignoreCase = true) }
        outbox.enqueue(Outbox.Item(peer = cmId, label = "accept", replaceKey = "accept", keepOnClose = true,
            deliver = { if (viaPeer != null) sendSecure(viaPeer, FrameType.KNOCK_ACCEPT, payload)
                        else sendSecureTo(cmId, FrameType.KNOCK_ACCEPT, payload) },
            onDelivered = {
                ConnDiag.sys("Add friend: acceptance delivered")
                addressReached(cmId, myId)
                // My nickname travelled in it too.
                nameConfirmed[currentId(cmId)] = payloadName
                onNameConfirmed?.invoke(currentId(cmId), payloadName)
            }))
    }

    fun declineKnock(req: KnockRequest) {
        ConnDiag.sys("Add friend: knock declined (ignored from them for 1 h)")
        declinedAt[req.cmId] = System.currentTimeMillis()
        _incomingKnocks.value = _incomingKnocks.value.filterNot { it.cmId == req.cmId }
    }

    // ---- unlock / lock: replay what was held -------------------------------------

    /**
     * The vault was unlocked (AppNav, after [configure] and its callbacks): replay
     * everything held while locked into the normal dispatch — in arrival order,
     * before any newer frame — then shred it. Idempotent.
     */
    fun openVault() {
        vaultWanted = true
        if (vaultOpen) return
        scope.launch {
            synchronized(holdLock) {
                if (!vaultWanted || vaultOpen) return@synchronized
                replayHeldLocked()
                vaultOpen = vaultWanted
            }
        }
    }

    /** The vault was locked: from now on what arrives is held until the next unlock. */
    fun closeVault() {
        vaultWanted = false
        vaultOpen = false
    }

    /** Unlocked and replayed (tests wait for this after [openVault]). */
    internal fun vaultIsOpen(): Boolean = vaultOpen

    /** Caller holds [holdLock]. */
    private fun replayHeldLocked() {
        val h = held ?: return
        val pub = myPubHex ?: return
        val sec = mySecHex ?: return
        val entries = h.readAll(pub, sec)
        if (entries.isEmpty()) return
        ConnDiag.sys("Unlocked: ${entries.size} item(s) that arrived while locked → delivered now")
        for (e in entries) {
            runCatching { replayOne(e.record) }
                .onFailure { ConnDiag.sys("a held item couldn't be replayed (${it.javaClass.simpleName})") }
        }
        // Anything a held frame changed in the vault is ON DISK before the held
        // copy goes (a crash in between replays it again — replay is idempotent).
        val saved = runCatching { flushVault?.invoke() }.isSuccess
        if (saved) entries.forEach { h.remove(it) }
        heldIds.clear()
    }

    private fun replayOne(rec: HeldInbox.Record) {
        if (rec.type == FrameType.KNOCK) {
            val kp = decodeKnock(rec.body) ?: return
            val knocker = CmId.decode(kp.cmId) ?: return
            val declined = declinedAt[kp.cmId]?.let { System.currentTimeMillis() - it < DECLINE_COOLDOWN_MS } == true
            if (!declined) addKnockCard(kp, knocker, notify = false)
            return
        }
        val from = rec.fromPub ?: return
        val id = contactIdFor(from) ?: return            // no longer a friend: nothing to deliver
        val peer = contacts[id] ?: return
        confirmIfPending(id)
        dispatchFromContact(id, peer, rec.type, rec.body, replay = rec)
    }

    // ---- incoming ----------------------------------------------------------

    /**
     * Handle one incoming connection SYNCHRONOUSLY (ServerController owns the
     * socket lifecycle, the concurrency cap and the per-read timeout, and closes
     * the socket afterward). [SecureWire.receive] reads one length-bounded frame
     * and authenticates it BEFORE doing anything else: an anonymous knock, or a
     * prekey request from a known contact — answered with a one-time prekey, then
     * exactly one forward-secret frame is read and opened. It is then STORED
     * (chat, or held while locked) and only then answered with an OK receipt.
     * Everything else is dropped. Received bytes are only ever decrypted/parsed
     * — never executed.
     */
    private fun handleIncoming(socket: Socket) {
        // Any failure (truncated frame, malformed crypto, bad JSON, a peer that
        // resets mid-handshake) just drops this connection cleanly — it never
        // throws up into the accept loop.
        try {
            val ch = channel ?: return
            val known = HashMap<String, String>().also { m ->
                for ((id, peer) in contacts) m[id] = peer.identityPubKeyHex
            }
            val r = SecureWire.receive(
                ch, socket.getInputStream(), socket.getOutputStream(), known,
                allow = { contactRate.allow(it) },
                onStage = { ConnDiag.inc(it) },
            )
            when (r) {
                is SecureWire.Received.Knock -> {
                    ConnDiag.inc("opened as KNOCK (anonymous sealed box)")
                    onKnock(r)
                }
                is SecureWire.Received.Message -> {
                    // Removed meanwhile: no receipt — nothing was stored.
                    val peer = contacts[r.cmId] ?: return
                    ConnDiag.inc("forward-secret frame opened from ${Redact.onionShort(peer.onion)}")
                    // Any authenticated frame proves this friend has me: confirm a
                    // pending one, and retry anything queued for them right now.
                    confirmIfPending(r.cmId)
                    outbox.kick(r.cmId); outbox.kick(r.cmId + FILE_LANE)
                    // Anything they deliberately sent = they were around ("last
                    // seen recently"). Cover traffic is noise and doesn't count.
                    if (r.type != FrameType.COVER) markSeen(r.cmId)
                    if (r.type == FrameType.FILE_OFFER) { receiveFile(r, socket, ch, peer); return }
                    val ack = try {
                        take(r.cmId, peer, r.type, r.body)
                    } catch (_: Exception) {
                        ConnDiag.inc("${r.type} couldn't be stored → they'll retry")
                        Ack.RETRY
                    }
                    ConnDiag.inc(if (r.reply(ack)) "receipt sent: ${ack.name}" else "receipt not sent (connection gone) — they'll retry")
                }
                SecureWire.Received.VersionMismatch -> {
                    versionMismatch.value = true
                    ConnDiag.inc("wire version mismatch → dropped (update both apps)")
                    org.cmchat.app.diag.Diag.droppedFrame()
                }
                is SecureWire.Received.Dropped -> {
                    // Static reason strings only — never keys, contents or addresses.
                    ConnDiag.inc("${r.reason} → dropped")
                    org.cmchat.app.diag.Diag.droppedFrame()
                }
            }
        } catch (_: Exception) {
            ConnDiag.inc("incoming error → connection dropped")
            org.cmchat.app.diag.Diag.droppedFrame()
        }
    }

    /**
     * A friend offers a file. Everything is checked against the OFFER before a
     * single byte of the file is read or allocated: the 100 MB cap, the piece
     * count, free memory, one incoming file at a time — and while locked the
     * answer is "retry later" (files are RAM-only and not held). Then exactly
     * the announced pieces are read and verified; only when ALL of them opened
     * is it kept (RAM) and the final receipt sent. Never opened or interpreted.
     */
    private fun receiveFile(r: SecureWire.Received.Message, socket: Socket, ch: SecureChannel, peer: CmIdData) {
        val offer = runCatching { Messages.json.decodeFromString(FileOffer.serializer(), String(r.body)) }.getOrNull()
        val fileId = offer?.id?.let { fromHex(it) }?.takeIf { it.size == FileTransfer.ID_BYTES }
        val key = offer?.key?.let { fromHex(it) }?.takeIf { it.size == FileTransfer.KEY_BYTES }
        if (offer == null || fileId == null || key == null) {
            ConnDiag.inc("file offer malformed → refused"); r.reply(Ack.REJECTED); return
        }
        if (offer.size <= 0 || offer.size > FileTransfer.MAX_BYTES || offer.chunks != FileTransfer.chunkCount(offer.size)) {
            ConnDiag.inc("file refused: over 100 MB or malformed (nothing read)"); r.reply(Ack.REJECTED); return
        }
        val dedup = "${peer.identityPubKeyHex.lowercase()}:file:${offer.id}"
        if (synchronized(holdLock) { deliveredIds.containsKey(dedup) }) {
            ConnDiag.inc("file already here → told them"); r.reply(Ack.HAVE); return
        }
        if (!vaultOpen) { ConnDiag.inc("file while locked → they'll retry after unlock"); r.reply(Ack.RETRY); return }
        if (!FileTransfer.fitsInMemory(offer.size)) { ConnDiag.inc("file: not enough free memory now → retry"); r.reply(Ack.RETRY); return }
        if (incomingFiles.incrementAndGet() > MAX_INCOMING_FILES) {
            incomingFiles.decrementAndGet(); ConnDiag.inc("file: another one is arriving → retry"); r.reply(Ack.RETRY); return
        }
        try {
            if (!r.reply(Ack.OK)) return
            ConnDiag.inc("file offer accepted (${offer.chunks} piece(s))")
            // Generous for a slow Tor link (~16 KB/s), but never forever.
            val deadline = System.currentTimeMillis() + 10 * 60_000L + offer.size / 16
            val pieces = SecureWire.readFileChunks(ch, socket.getInputStream(), key, fileId, offer.size,
                offer.chunks, deadline)
            if (pieces == null) { ConnDiag.inc("file broke off or was tampered with → discarded"); return }
            val file = org.cmchat.app.chat.ChatFile(org.cmchat.app.media.FileNames.safe(offer.name), offer.size,
                safeMime(offer.mime), pieces)
            synchronized(holdLock) {
                deliveredIds[dedup] = true
                val invisible = org.cmchat.app.settings.AppSettings.invisibleMode.value
                val chatCmId = inChat(r.cmId) { id ->
                    ChatStore.addTheirs(id, "f-${offer.id}", file.name, SelfTimer.fromLabel(offer.selfTimer),
                        missed = invisible, file = file); id
                }
                if (!invisible && !chatOnScreen(chatCmId)) notify(Notice.MESSAGE)
            }
            Transport.writeFrame(socket.getOutputStream(), ch.fileReceipt(fileId, Ack.OK, peer.identityPubKeyHex))
            ConnDiag.inc("file stored (RAM) → final receipt sent")
        } finally {
            key.fill(0)
            incomingFiles.decrementAndGet()
        }
    }

    /** A type label we pass on only if it looks like one (it's only a hint for saving). */
    private fun safeMime(m: String): String =
        m.takeIf { it.length <= 80 && Regex("^[a-z]+/[a-z0-9.+-]+$").matches(it) } ?: "application/octet-stream"

    private fun confirmIfPending(cmId: String) {
        if (pending.remove(cmId)) {
            ConnDiag.sys("Add friend: they accepted — friend confirmed")
            onFriendConfirmed?.invoke(cmId)
        }
    }

    /** A friend was active: refresh "last seen" (RAM) and, now and then, persist it. */
    private fun markSeen(fromCmId: String, now: Long = System.currentTimeMillis()) {
        val id = inChat(fromCmId) { id -> ChatStore.touchPeer(id, now); id }
        val last = seenPersistedAt[id]
        if (last == null || now - last >= SEEN_PERSIST_EVERY_MS) {
            seenPersistedAt[id] = now
            onPeerSeen?.invoke(id, now)
        }
    }

    /** The friend whose identity key is [pubHex] (their current cmId), if any. */
    private fun contactIdFor(pubHex: String): String? =
        contacts.entries.firstOrNull { it.value.identityPubKeyHex.equals(pubHex, ignoreCase = true) }?.key

    /**
     * One authenticated frame: deliver it now, or HOLD it while locked — and say
     * whether it is safely stored (the receipt). A Buzz and cover traffic carry
     * no state, so they never wait.
     */
    private fun take(fromCmId: String, peer: CmIdData, type: FrameType, body: ByteArray): Ack {
        ConnDiag.inc("dispatched $type")
        when (type) {
            FrameType.COVER -> { ConnDiag.inc("cover frame discarded"); return Ack.OK }
            FrameType.BUZZ -> { onBuzz(fromCmId); return Ack.OK }
            else -> {}
        }
        synchronized(holdLock) {
            return if (vaultOpen) dispatchFromContact(fromCmId, peer, type, body, replay = null)
                else hold(fromCmId, peer, type, body)
        }
    }

    /**
     * Locked: keep the frame on flash (sealed) until the next unlock, then OK.
     * What the RUNNING engine needs right away also applies now: a new address
     * (so my replies go to it), a terminate (so they're dropped), an erase or a
     * decoy (their held frames are shredded at once). Caller holds [holdLock].
     */
    private fun hold(fromCmId: String, peer: CmIdData, type: FrameType, body: ByteArray): Ack {
        val now = System.currentTimeMillis()
        // Nothing malformed is ever stored.
        var msgKey: String? = null
        when (type) {
            FrameType.MSG -> {
                val t = decodeText(body) ?: return Ack.REJECTED
                val key = "${peer.identityPubKeyHex.lowercase()}:${t.id}"
                if (key in deliveredIds || key in heldIds) return Ack.OK   // a re-send after a lost receipt
                msgKey = key
            }
            FrameType.TEAM_CLOCK -> if (teamClock(body) == null) return Ack.REJECTED
            FrameType.NICKNAME -> if (nicknameFrom(body) == null) return Ack.REJECTED
            FrameType.ADDR_UPDATE -> if (addressFrom(peer, body) == null) return Ack.REJECTED
            FrameType.ERASE_CHAT -> {
                // Their earlier held frames go now, and the chat in RAM too.
                val gone = dropHeldFrom(peer.identityPubKeyHex)
                inChat(fromCmId) { ChatStore.erase(it) }
                ConnDiag.inc("erase while locked → chat erased (+$gone held item(s) shredded)")
                return Ack.OK
            }
            FrameType.KNOCK_ACCEPT, FrameType.TERMINATE, FrameType.DECOY_ALERT -> {}
            else -> return Ack.REJECTED
        }
        if (type == FrameType.DECOY_ALERT) {
            // Their phone may be in someone else's hands: what they sent and is
            // still held is shredded now; the notice waits for the unlock.
            dropHeldFrom(peer.identityPubKeyHex)
        }
        val h = held
        val pub = myPubHex
        if (h == null || pub == null ||
            !h.put(HeldInbox.Record(type, peer.identityPubKeyHex, body, now, closed = buzzOnlyMode), pub)) {
            ConnDiag.inc("couldn't hold $type (storage full or unavailable) → they'll retry")
            return Ack.RETRY
        }
        msgKey?.let { heldIds += it }
        ConnDiag.inc("held while locked: $type (shown after unlock)")
        when (type) {
            FrameType.ADDR_UPDATE -> onAddressUpdate(currentId(fromCmId), peer, body)
            FrameType.KNOCK_ACCEPT -> followAcceptance(fromCmId, peer, body)
            FrameType.TERMINATE -> friendTerminated(fromCmId)
            FrameType.MSG -> {
                // Same rule as when unlocked: a message notifies only while Online.
                // (Closing the app always makes the next start Invisible.)
                if (!org.cmchat.app.settings.AppSettings.invisibleMode.value) notify(Notice.MESSAGE)
            }
            // A friend's decoy notifies even while I'm Invisible (decision A).
            FrameType.DECOY_ALERT -> notify(Notice.MESSAGE)
            else -> {}
        }
        return Ack.OK
    }

    /** Shred every held frame from [pubHex]. Caller holds [holdLock]. */
    private fun dropHeldFrom(pubHex: String): Int {
        val h = held ?: return 0
        val pub = myPubHex ?: return 0
        val sec = mySecHex ?: return 0
        return h.removeFrom(pubHex, pub, sec)
    }

    /**
     * An anonymous knock (friend request): decode it, store the request card,
     * and answer with a receipt — OK only once it's stored. It ALWAYS reaches
     * the Friends screen, even while I'm Invisible or the app is closed (then
     * it's also held, so a killed app can't lose it). One-time and rate-limited:
     * duplicates are merged, at most [MAX_PENDING_KNOCKS] wait at once, a
     * declined sender is ignored for an hour, and bursts are asked to retry.
     */
    private fun onKnock(r: SecureWire.Received.Knock) {
        val kp = decodeKnock(r.body)
        val knocker = kp?.let { CmId.decode(it.cmId) }
        val nonce = kp?.nonce?.let { fromHex(it) }?.takeIf { it.size == SecureChannel.CHALLENGE }
        if (kp == null || knocker == null || nonce == null) {
            ConnDiag.inc("KNOCK ignored (malformed)"); return           // no one to send a receipt to
        }
        val ack = try {
            synchronized(holdLock) { takeKnock(kp, knocker, r.body) }
        } catch (_: Exception) {
            Ack.RETRY
        }
        ConnDiag.inc(if (r.reply(nonce, knocker.identityPubKeyHex, ack)) "knock receipt sent: ${ack.name}"
            else "knock receipt not sent (connection gone)")
    }

    /** Caller holds [holdLock]. */
    private fun takeKnock(kp: KnockPayload, knocker: CmIdData, raw: ByteArray): Ack {
        if (kp.cmId == myCmId || knocker.identityPubKeyHex.equals(myPubHex, ignoreCase = true)) {
            ConnDiag.inc("KNOCK ignored (my own ID)"); return Ack.REJECTED
        }
        if (kp.withdraw) {
            // They cancelled their request: take the card away (nothing else) —
            // also the held copy, or the next unlock would bring it back.
            val cur = _incomingKnocks.value
            if (cur.any { it.cmId == kp.cmId }) {
                _incomingKnocks.value = cur.filterNot { it.cmId == kp.cmId }
                ConnDiag.inc("KNOCK withdrawn by the sender → request removed")
            }
            val h = held; val pub = myPubHex; val sec = mySecHex
            if (h != null && pub != null && sec != null) {
                h.removeIf(pub, sec) { it.type == FrameType.KNOCK && decodeKnock(it.body)?.cmId == kp.cmId }
            }
            return Ack.OK
        }
        val now = System.currentTimeMillis()
        declinedAt[kp.cmId]?.let { if (now - it < DECLINE_COOLDOWN_MS) {
            ConnDiag.inc("KNOCK ignored (declined recently)"); return Ack.OK
        } }
        // Already my (confirmed) friend — same identity key: they never got my
        // acceptance (e.g. my app closed before it went out) and knocked again.
        // No duplicate card — just re-send the acceptance. A knock isn't
        // authenticated, but this only ever sends to that friend's REAL key +
        // stored address, so a forged one gains nothing.
        val friendId = contactIdFor(knocker.identityPubKeyHex)
        if (friendId != null && friendId !in pending) {
            if (!knockRate.allow("knock")) { ConnDiag.inc("KNOCK ignored (rate limit)"); return Ack.RETRY }
            ConnDiag.inc("KNOCK from an existing friend → acceptance re-sent")
            // They may knock from a NEW address (theirs changed before my
            // acceptance reached them — the "stuck on Pending" case): send it
            // there. Only their identity key can answer that handshake, so a
            // forged address gets nothing; I don't move them on this unproven
            // hint — once confirmed, their own signed address update does that.
            queueAccept(friendId, via = kp.cmId.takeIf { it != friendId })
            return Ack.OK
        }
        val cur = _incomingKnocks.value
        if (cur.any { it.cmId == kp.cmId }) { ConnDiag.inc("KNOCK ignored (duplicate)"); return Ack.OK }
        if (cur.size >= MAX_PENDING_KNOCKS) { ConnDiag.inc("KNOCK ignored (pending cap)"); return Ack.RETRY }
        if (!knockRate.allow("knock")) { ConnDiag.inc("KNOCK ignored (rate limit)"); return Ack.RETRY }
        if (!vaultOpen) {
            // Locked or closed: keep it on flash too, so it survives a killed app.
            val h = held; val pub = myPubHex
            if (h == null || pub == null || !h.put(HeldInbox.Record(FrameType.KNOCK, null, raw, now,
                    closed = buzzOnlyMode), pub)) {
                ConnDiag.inc("couldn't hold the knock → they'll retry"); return Ack.RETRY
            }
        }
        addKnockCard(kp, knocker, notify = true)
        return Ack.OK
    }

    /** Show a request card (deduplicated). [notify]: a friend request always notifies. */
    private fun addKnockCard(kp: KnockPayload, knocker: CmIdData, notify: Boolean) {
        if (contactIdFor(knocker.identityPubKeyHex)?.let { it !in pending } == true) return   // already a friend
        val cur = _incomingKnocks.value
        if (cur.any { it.cmId == kp.cmId } || cur.size >= MAX_PENDING_KNOCKS) return
        ConnDiag.inc("KNOCK received (waiting for you to accept)")
        _incomingKnocks.value = cur + KnockRequest(cleanName(kp.displayName) ?: "", kp.cmId)
        // A friend request always notifies (also while Invisible).
        if (notify) notify(Notice.FRIEND_REQUEST)
    }

    /**
     * Deliver one frame from a contact (unlocked, or replaying a held one) and
     * return its receipt. [replay] = the held record being replayed (no
     * notification then: it was notified when it arrived).
     */
    private fun dispatchFromContact(
        fromCmId: String, peer: CmIdData, type: FrameType, body: ByteArray, replay: HeldInbox.Record?,
    ): Ack {
        if (replay != null) ConnDiag.inc("replayed $type")
        when (type) {
            FrameType.MSG -> {
                val t = decodeText(body) ?: return Ack.REJECTED
                val key = "${peer.identityPubKeyHex.lowercase()}:${t.id}"
                if (deliveredIds.containsKey(key)) return Ack.OK   // a re-send after a lost receipt
                deliveredIds[key] = true
                // A pasted engine log is never shown as a message (logs stay in
                // Connection/Diagnostics) — a grey notice says one arrived.
                if (org.cmchat.app.chat.EngineLog.looksLikeLog(t.text)) {
                    ConnDiag.inc("message was an engine log → not shown in the chat")
                    inChat(fromCmId) { id -> ChatStore.addSystemLine(id, org.cmchat.app.chat.EngineLog.HIDDEN_NOTICE) }
                    return Ack.OK
                }
                // While Invisible, the message is held as "missed" (blue dot); the
                // receipt says only "stored" (never "read"), so the sender learns
                // nothing, and it surfaces once we go Online. One that arrived
                // while the app was CLOSED also shows "Missed Message" after that.
                val invisible = org.cmchat.app.settings.AppSettings.invisibleMode.value
                if (invisible) ConnDiag.inc("held (Invisible): message kept as missed")
                val chatCmId = inChat(fromCmId) { id ->
                    ChatStore.addTheirs(id, t.id, t.text, SelfTimer.fromLabel(t.selfTimer), missed = invisible,
                        at = replay?.atMs, closedMiss = replay?.closed == true); id
                }
                // Generic "Notification" — but NOT while Invisible (then only a Buzz,
                // a decoy alert or a friend request notifies; the message waits as
                // "Missed"), and not for the chat that's open in front of the user.
                if (replay == null && !invisible && !chatOnScreen(chatCmId)) notify(Notice.MESSAGE)
            }
            FrameType.DECOY_ALERT -> onDecoyAlert(fromCmId, peer, replay)
            FrameType.TEAM_CLOCK -> {
                val (v, at) = teamClock(body) ?: return Ack.REJECTED
                val id = currentId(fromCmId)
                // Newest wins: an older setting (re-sent, or crossed with mine) changes nothing.
                if (at <= (teamClockAt[id] ?: Long.MIN_VALUE)) { ConnDiag.inc("older Team Clock ignored (newest wins)"); return Ack.OK }
                teamClockAt[id] = at
                val value = v.ifEmpty { null }
                val chatCmId = inChat(fromCmId) { cid ->
                    ChatStore.setTeamHour(cid, value, names[cid]?.ifBlank { null } ?: ""); cid
                }
                onTeamClockChanged?.invoke(chatCmId, value, at)
            }
            FrameType.ERASE_CHAT -> inChat(fromCmId) { ChatStore.erase(it) }
            FrameType.KNOCK_ACCEPT -> followAcceptance(fromCmId, peer, body)   // confirmed + seen already
            FrameType.TERMINATE -> friendTerminated(fromCmId)
            FrameType.BUZZ -> onBuzz(fromCmId)
            FrameType.ADDR_UPDATE -> {
                if (addressFrom(peer, body) == null) return Ack.REJECTED
                onAddressUpdate(currentId(fromCmId), peer, body)
            }
            FrameType.COVER -> ConnDiag.inc("cover frame discarded")
            FrameType.NICKNAME -> {
                val n = nicknameFrom(body) ?: return Ack.REJECTED
                onFriendName?.invoke(currentId(fromCmId), n)
            }
            else -> return Ack.REJECTED
        }
        return Ack.OK
    }

    /**
     * Their acceptance carries their CURRENT address (authenticated, like an
     * address update): if they moved since I scanned them, follow them now.
     */
    private fun followAcceptance(fromCmId: String, peer: CmIdData, body: ByteArray) {
        val kp = decodeKnock(body) ?: return
        if (kp.cmId != currentId(fromCmId) && addressFrom(peer, kp.cmId.toByteArray()) != null) {
            onAddressUpdate(currentId(fromCmId), peer, kp.cmId.toByteArray())
        }
        // They told me which address of MINE they stored (the one in the request
        // they accepted). Only if that isn't my current one does mine follow.
        val mine = myPubHex
        kp.yours.takeIf { y -> mine != null && CmId.decode(y)?.identityPubKeyHex.equals(mine, ignoreCase = true) }
            ?.let { addressReached(currentId(fromCmId), it) }
        resendAddress()
        // Their OWN nickname travels in the acceptance: shown unless I named them.
        cleanName(kp.displayName)?.let { onFriendName?.invoke(currentId(fromCmId), it) }
    }

    /** They removed me from their list: remove them from mine too. */
    private fun friendTerminated(fromCmId: String) {
        val id = currentId(fromCmId)
        if (!contacts.containsKey(id)) return
        ConnDiag.inc("friend removed you (terminate) → removed them too")
        forget(id)
        onFriendTerminated?.invoke(id)
    }

    /** A Team Clock change "value|setAtMs", strictly validated: a canonical offset
     * or "" (off), and a positive time. */
    private fun teamClock(body: ByteArray): Pair<String, Long>? {
        if (body.size > 40) return null
        val s = String(body, Charsets.US_ASCII)
        val v = s.substringBefore('|', missingDelimiterValue = "\u0000")
        val at = s.substringAfter('|', "").toLongOrNull() ?: return null
        if (at <= 0 || !(v.isEmpty() || org.cmchat.app.chat.TeamClock.decode(v) != null)) return null
        return v to at
    }

    /** A nickname frame: short, plain text (no control / invisible characters). */
    private fun nicknameFrom(body: ByteArray): String? {
        if (body.isEmpty() || body.size > 96) return null
        return cleanName(String(body, Charsets.UTF_8))
    }

    /** A name that came from another phone, made safe to show: no control or
     * invisible formatting characters (a right-to-left override can make a name
     * read as something else), trimmed, at most 24 characters. Null if empty. */
    internal fun cleanName(raw: String): String? =
        raw.filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }
            .trim().take(24).ifEmpty { null }

    /** The new CMC-ID in an address update — only if it keeps the SAME identity key. */
    private fun addressFrom(peer: CmIdData, body: ByteArray): CmIdData? {
        val newCmId = runCatching { String(body) }.getOrNull() ?: return null
        val decoded = CmId.decode(newCmId) ?: return null
        return decoded.takeIf { it.identityPubKeyHex.equals(peer.identityPubKeyHex, ignoreCase = true) }
    }

    /**
     * A contact rotated their onion. The frame is authenticated (it opened under
     * a key that needed [peer]'s identity), so we trust the new cmId ONLY if it
     * carries the same identity pubkey — then we re-link to the new onion and persist.
     */
    private fun onAddressUpdate(oldCmId: String, peer: CmIdData, body: ByteArray) {
        val decoded = addressFrom(peer, body) ?: return
        val newCmId = String(body)
        if (newCmId == oldCmId) return
        synchronized(relinkLock) {
            // Order matters for readers that don't take the lock: the new address
            // exists before the old one goes, and the chat has MOVED before the
            // new id is published — whoever sees the new id sees the moved chat.
            contacts[newCmId] = decoded
            names[oldCmId]?.let { names[newCmId] = it }
            if (oldCmId in pending) pending.add(newCmId)
            addressConfirmed.remove(oldCmId)?.let { addressConfirmed[newCmId] = it }
            ChatStore.rekey(oldCmId, newCmId)   // the conversation follows the friend
            relinked.remove(newCmId)            // moving back to an earlier address
            relinked[oldCmId] = newCmId
            if (activeChatCmId == oldCmId) activeChatCmId = newCmId
            contacts.remove(oldCmId)
            names.remove(oldCmId)
            pending.remove(oldCmId)
        }
        ConnDiag.inc("friend moved to a new address → relinked")
        onContactAddressUpdated?.invoke(oldCmId, newCmId)
        org.cmchat.app.diag.Diag.i("addr", "contact relinked to new address")
    }

    /**
     * A friend's decoy was tripped: the notice line (chat erased when I leave
     * it). It notifies even while I'm Invisible (decision A) — unless that chat
     * is open in front of me.
     */
    private fun onDecoyAlert(fromCmId: String, peer: CmIdData, replay: HeldInbox.Record?) {
        dropHeldFrom(peer.identityPubKeyHex)
        val chatCmId = inChat(fromCmId) { id -> ChatStore.addDecoyNotice(id, at = replay?.atMs ?: System.currentTimeMillis()); id }
        ConnDiag.inc("decoy alert received")
        if (replay == null && !chatOnScreen(chatCmId)) notify(Notice.MESSAGE)
    }

    /** What a notification is about (its text is always generic). */
    internal enum class Notice { MESSAGE, FRIEND_REQUEST, BUZZ }

    /**
     * Posts a notification. While Invisible only a Buzz, a friend's decoy alert
     * or a friend request may notify (the callers decide). Tests swap this to
     * count them, like [dialer].
     */
    @Volatile
    internal var notify: (Notice) -> Unit = { n ->
        org.cmchat.app.settings.AppSettings.appContext?.let { ctx ->
            when (n) {
                Notice.MESSAGE -> org.cmchat.app.notify.Notifier.message(ctx)
                Notice.FRIEND_REQUEST -> org.cmchat.app.notify.Notifier.activity(ctx)
                Notice.BUZZ -> org.cmchat.app.notify.Notifier.buzz(ctx)
            }
        }
    }

    /** That friend's chat is open AND the app is in front of the user. */
    private fun chatOnScreen(chatCmId: String): Boolean =
        activeChatCmId == chatCmId && org.cmchat.app.LifecycleController.inForeground

    /** A buzz arrived: throttle by the receiver setting, then shake + notify. */
    private fun onBuzz(fromCmId: String) {
        val chatCmId = currentId(fromCmId)
        if (!org.cmchat.app.buzz.BuzzPolicy.accept(chatCmId)) return
        // Shake the chat if it's on screen (the UI collects this per-chat);
        // otherwise leave a blue Buzz dot on that friend until the chat is opened.
        org.cmchat.app.buzz.BuzzPolicy.requestShake(chatCmId)
        inChat(chatCmId) { id -> if (activeChatCmId != id) ChatStore.markBuzzed(id) }
        // A real notification in the bar (heads-up + vibration), generic text only —
        // also while Invisible, and also while the app is closed (Buzz listener).
        notify(Notice.BUZZ)
    }

    // ---- wire helpers ------------------------------------------------------

    /** [sendSecure] to the friend's CURRENT address (looked up at attempt time). */
    private fun sendSecureTo(cmId: String, type: FrameType, payload: ByteArray) {
        val peer = contacts[currentId(cmId)] ?: throw IOException("not a friend any more")
        sendSecure(peer, type, payload)
    }

    /**
     * Send ONE content frame to a contact over the forward-secret handshake:
     * request a one-time prekey, verify it against their identity key, send the
     * X3DH-sealed frame, and read their receipt. Returns only when their phone
     * confirmed it STORED it; throws otherwise (the Outbox retries silently, or
     * drops it for good if their phone refused it).
     */
    private fun sendSecure(peer: CmIdData, type: FrameType, payload: ByteArray) {
        val ch = channel ?: throw IOException("engine not ready")
        withConnection(peer) { s ->
            // The sender READS (the prekey reply, the receipt): never wait forever.
            s.soTimeout = HANDSHAKE_READ_TIMEOUT_MS
            requireStored(SecureWire.send(
                ch, s.getInputStream(), s.getOutputStream(), peer.identityPubKeyHex, type, payload,
                onStage = { ConnDiag.out(it) },
                onVersionMismatch = { versionMismatch.value = true },
            ))
        }
    }

    /** One file to a friend's CURRENT address: offer, pieces, final receipt. */
    private fun sendFileTo(cmId: String, offer: ByteArray, fileId: ByteArray, key: ByteArray,
                           pieces: List<ByteArray>, stillWanted: () -> Boolean) {
        val peer = contacts[currentId(cmId)] ?: throw IOException("not a friend any more")
        val ch = channel ?: throw IOException("engine not ready")
        withConnection(peer) { s ->
            s.soTimeout = HANDSHAKE_READ_TIMEOUT_MS
            requireStored(SecureWire.sendFile(ch, s.getInputStream(), s.getOutputStream(), peer.identityPubKeyHex,
                offer, fileId, key, pieces, onStage = { ConnDiag.out(it) },
                onVersionMismatch = { versionMismatch.value = true }, stillWanted = stillWanted))
        }
    }

    /** OK = stored on their phone. RETRY = keep it and try later; REJECTED = never. */
    private fun requireStored(ack: Ack) = when (ack) {
        Ack.OK, Ack.HAVE -> Unit
        Ack.RETRY -> throw SecureWire.HandshakeFailed("their phone asked to retry later")
        Ack.REJECTED -> throw Outbox.GiveUp("their phone refused it")
    }

    /** Open a connection to [peer]'s onion via [dialer], run [block], close it. */
    private fun withConnection(peer: CmIdData, block: (Socket) -> Unit) {
        val short = Redact.onionShort(peer.onion)
        ConnDiag.out("resolve $short")
        val t0 = System.currentTimeMillis()
        val sock = try {
            dialer(peer)
        } catch (e: Exception) {
            ConnDiag.out("FAILED: ${Transport.failureReason(e)}")
            throw e
        }
        try {
            sock.use { block(it) }
        } catch (e: Exception) {
            // HandshakeFailed / GiveUp carry a fixed, content-free reason; anything
            // else is reduced to a short category. Never keys, contents or addresses.
            val why = if (e is SecureWire.HandshakeFailed || e is Outbox.GiveUp) e.message
                else Transport.failureReason(e)
            ConnDiag.out("FAILED: $why")
            throw e
        }
        ConnDiag.out("CONNECTED — delivered, their phone confirmed (${System.currentTimeMillis() - t0}ms)")
    }

    /** The production dialer: through Tor's SOCKS port to the onion, fail-closed. */
    private fun torDial(peer: CmIdData): Socket {
        // Never attempt a connection unless Tor is up. Retry with backoff so a
        // send right after publish (descriptor still uploading) doesn't hard-fail.
        if (TorService.status.value !is TorStatus.Online) {
            ConnDiag.out("FAILED: Tor offline")
            throw IOException("Tor offline")
        }
        // Timing jitter: a small randomized delay so exact send time doesn't map
        // 1:1 to typing/sending. Applies to every outbound frame (incl. cover).
        runCatching { Thread.sleep((30..260).random().toLong()) }
        return Transport.connectThroughTorRetry(
            TorService.socksPort(), peer.onion.removeSuffix(".onion"), 80,
            onStage = { ConnDiag.out(it) },
        )
    }

    /**
     * Link Test: a real end-to-end connectivity probe to one contact. Sends a
     * lightweight BUZZ (no content) over the full Tor→onion path and the whole
     * forward-secret handshake, so BOTH phones log every stage — outgoing here,
     * incoming on the contact's Connection log.
     */
    fun linkTest(cmId: String) {
        val peer = contacts[currentId(cmId)]
        if (channel == null || peer == null) {
            ConnDiag.sys("Link Test: contact or engine not ready"); return
        }
        ConnDiag.sys("── Link Test → ${Redact.onionShort(peer.onion)} ──")
        scope.launch {
            val ok = runCatching { sendSecure(peer, FrameType.BUZZ, ByteArray(0)); true }
                .getOrDefault(false)
            ConnDiag.sys("Link Test result: ${if (ok) "CONNECTED" else "FAILED"}")
        }
    }

    private fun decodeKnock(body: ByteArray): KnockPayload? = runCatching {
        Messages.json.decodeFromString(KnockPayload.serializer(), String(body))
    }.getOrNull()

    private fun decodeText(body: ByteArray): TextPayload? = runCatching {
        Messages.json.decodeFromString(TextPayload.serializer(), String(body))
    }.getOrNull()

    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun fromHex(s: String): ByteArray? {
        if (s.length % 2 != 0 || s.length > 64) return null
        return runCatching { ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() } }.getOrNull()
    }
}
