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
 *  MESSAGES       every frame to a friend goes over the v4 handshake in
 *                 [SecureChannel] (one-time prekey + X3DH + AEAD).
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

    /** Set by AppNav to persist a Team Clock the friend set (null = turned off). */
    @Volatile
    var onTeamClockChanged: ((cmId: String, value: String?) -> Unit)? = null

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var myName: String = ""
    private var myCmId: String? = null
    private var myPubHex: String? = null

    /** The forward-secret channel for my identity; null until configured. */
    @Volatile
    private var channel: SecureChannel? = null

    /**
     * Buzz-only mode: the app was swiped away but the scout listener is alive.
     * Only BUZZ frames (and decoy alerts) do anything; everything else is
     * dropped, and no chat state is kept (ChatStore is already cleared).
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

    /** "Last seen" is persisted at most this often per friend (it's coarse anyway). */
    private const val SEEN_PERSIST_EVERY_MS = 30 * 60_000L
    private val seenPersistedAt = ConcurrentHashMap<String, Long>()

    /**
     * Wire protocol version. Bumped whenever the framing/crypto changes so two
     * peers on different builds detect the mismatch instead of failing silently.
     * v5 = v4 + TERMINATE + knock withdrawal + minute-precise Team Clock;
     * v4 = forward-secret handshake + decoy alert + Team Clock (v3 = forward
     * secrecy only; v2 = static crypto_box + replay counter).
     */
    const val WIRE_VERSION = SecureChannel.WIRE_VERSION

    /** Per-app-run session id + per-peer counters, and the anti-replay windows.
     * Process-lifetime, so re-configuring (e.g. a contact added) keeps them. */
    private val codec = InnerCodec()
    private val replayGuard = ReplayGuard()

    /** How long a sender waits for the contact's prekey reply over Tor. */
    private const val HANDSHAKE_READ_TIMEOUT_MS = 30_000

    /** Set true when an authenticated frame from a DIFFERENT wire version arrives;
     * the UI shows "Update both apps to the same version." */
    val versionMismatch = MutableStateFlow(false)

    /** Cap pending knock requests so a knock flood can't grow RAM without bound. */
    private const val MAX_PENDING_KNOCKS = 20

    /** Silent background delivery (RAM-only). See [Outbox]. */
    private val outbox = Outbox(scope)
    @Volatile private var torWatch: Job? = null

    /**
     * How a socket to a friend's onion is opened. Production: through Tor (fails
     * closed if Tor is down). The loopback tests swap ONLY this, so everything
     * after the socket is the real code. Not reachable from outside the app.
     */
    @Volatile
    internal var dialer: (CmIdData) -> Socket = { peer -> torDial(peer) }

    /**
     * Anti-forensics: drop the identity key material, contact table and queued
     * frames held in RAM. Called from wipe paths and the crash handler; safe to
     * call anytime (the next configure() repopulates it).
     */
    fun zeroKeys() {
        channel = null
        myName = ""
        myCmId = null
        myPubHex = null
        contacts.clear()
        names.clear()
        pending.clear()
        outbox.clear()
        knockSentAt.clear()
        knockDelivered.clear()
        declinedAt.clear()
        relinked.clear()
        terminations.clear()
        seenPersistedAt.clear()
        _incomingKnocks.value = emptyList()   // a stranger's name + ID must not survive a wipe
        activeChatCmId = null
        org.cmchat.app.vault.PendingVaultEdits.clear()
    }

    /** Drop every queued outgoing frame (wipe/exit paths). */
    fun clearOutbox() = outbox.clear()

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
    ) {
        this.myName = myDisplayName
        this.myCmId = myCmId
        this.myPubHex = myIdentityPubHex
        this.channel = SecureChannel(crypto, myIdentityPubHex, myIdentitySecHex, codec, replayGuard)
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
        ServerController.onIncoming = { socket -> handleIncoming(socket) }
        // Terminations not yet delivered (kept in the vault) go out again.
        pendingTerminations.forEach { id -> if (id !in terminations) queueTerminate(id) }
        // Whenever Tor comes (back) online, retry anything queued right away and
        // re-knock friends still pending.
        if (torWatch == null) {
            torWatch = scope.launch {
                TorService.status.collect {
                    if (it is TorStatus.Online) {
                        outbox.kickAll(); resendPendingKnocks()
                        org.cmchat.app.tor.TorClock.log()   // diagnostics: is this phone's clock off?
                    }
                }
            }
        }
        resendPendingKnocks()
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
     * [target]. It carries my CURRENT name + CMC-ID; latest one per friend wins.
     */
    private fun queueKnock(cmId: String, target: CmIdData, withdraw: Boolean = false) {
        val ch = channel ?: return
        val myId = myCmId ?: return
        val payload = Messages.json.encodeToString(KnockPayload.serializer(),
            KnockPayload(myName, myId, withdraw)).toByteArray()
        val sealed = ch.sealKnock(payload, target.identityPubKeyHex)
        val what = if (withdraw) "request withdrawal" else "knock"
        ConnDiag.sys("Add friend: $what queued → ${Redact.onionShort(target.onion)}")
        outbox.enqueue(Outbox.Item(
            peer = cmId, label = what, replaceKey = "knock",
            deliver = {
                withConnection(target) { s ->
                    Transport.writeFrame(s.getOutputStream(), sealed)
                    ConnDiag.out("$what sent (anonymous sealed box, ${sealed.size}b)")
                }
            },
            onDelivered = {
                if (!withdraw) knockDelivered.add(cmId)
                ConnDiag.sys(if (withdraw) "Add friend: request withdrawn on their phone"
                    else "Add friend: knock delivered — waiting for them to accept")
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

    /** Delete a friend on MY side only (their phone isn't told). */
    fun deleteFriend(cmId: String) {
        forget(currentId(cmId))
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
        outbox.enqueue(Outbox.Item(peer = cmId, label = "terminate", replaceKey = "terminate",
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
                contacts.remove(k); names.remove(k); pending.remove(k)
                ChatStore.forget(k)
                if (activeChatCmId == k) activeChatCmId = null
            }
        }
        keys.forEach { k -> outbox.clearPeer(k); knockSentAt.remove(k); knockDelivered.remove(k); seenPersistedAt.remove(k) }
    }

    /** Is this friend still waiting to accept me? */
    fun isPending(cmId: String): Boolean = currentId(cmId) in pending

    /**
     * Fire-and-forget BUZZ: no ack, no retry, no state, no content. Rate-limited
     * to one per contact per [BuzzPolicy.SEND_COOLDOWN_MS]. Returns false if on
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

    /** The normal chat Erase: wipes BOTH sides (theirs as soon as it reaches them). */
    fun sendErase(cmId: String) {
        val chatCmId = inChat(cmId) { id -> ChatStore.erase(id); id }
        if (channel == null || !contacts.containsKey(chatCmId)) return
        outbox.enqueue(Outbox.Item(peer = chatCmId, label = "erase", replaceKey = "erase",
            deliver = { sendSecureTo(chatCmId, FrameType.ERASE_CHAT, ByteArray(0)) }))
    }

    /**
     * Decoy tripped (this phone may be in someone else's hands): INSTANTLY wipe
     * MY side from RAM — every conversation, queued frame, buzz marker and note —
     * and queue a DECOY_ALERT to every friend. It does NOT instantly destroy their
     * copy: they see "Decoy chat triggered — chat erased." in the chat, and it's
     * erased for them once they leave it. Delivered silently in the background
     * (a friend who's offline gets it when they're back, while my engine runs).
     * The caller then rotates the onion address and locks the app.
     */
    fun tripDecoy() {
        val peers = contacts.keys.toList()
        outbox.clear()
        ChatStore.clearAll()
        org.cmchat.app.buzz.BuzzPolicy.clear()
        org.cmchat.app.tools.ToolsState.clear()
        activeChatCmId = null
        ConnDiag.sys("Decoy tripped: my chats wiped; alerting ${peers.size} friend(s)")
        if (channel == null) return
        peers.forEach { id ->
            outbox.enqueue(Outbox.Item(peer = id, label = "decoy alert", replaceKey = "decoy",
                deliver = { sendSecureTo(id, FrameType.DECOY_ALERT, ByteArray(0)) },
                onDelivered = { ConnDiag.out("decoy alert delivered") }))
        }
    }

    /**
     * Share this conversation's Team Clock with the friend ([value] = canonical
     * "UTC+hh:mm", or "" to turn it off). Queued like a message (latest wins).
     */
    fun sendTeamClock(cmId: String, value: String): Boolean {
        val chatCmId = currentId(cmId)
        if (channel == null || !contacts.containsKey(chatCmId)) return false
        if (value.isNotEmpty() && org.cmchat.app.chat.TeamClock.decode(value) == null) return false
        val bytes = value.toByteArray(Charsets.US_ASCII)
        outbox.enqueue(Outbox.Item(peer = chatCmId, label = "team clock", replaceKey = "teamclock",
            deliver = { sendSecureTo(chatCmId, FrameType.TEAM_CLOCK, bytes) }))
        return true
    }

    /**
     * Tell every contact my new CMC-ID after rotating my onion. Authenticated by
     * my identity key inside the forward-secret frame (only I can produce it) —
     * the "signed" address-update. Contacts auto-relink to the new onion.
     */
    fun sendAddressUpdate(newCmId: String) {
        if (channel == null) return
        myCmId = newCmId
        val payload = newCmId.toByteArray()
        val friends = contacts.keys.toList()
        ConnDiag.sys("Telling ${friends.size} friend(s) my new address (each must receive it, or they keep dialing the old one)")
        friends.forEach { id ->
            outbox.enqueue(Outbox.Item(peer = id, label = "address update", replaceKey = "addr",
                deliver = { sendSecureTo(id, FrameType.ADDR_UPDATE, payload) },
                onDelivered = { ConnDiag.sys("New address delivered to a friend") }))
        }
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
     * silently retried until it reaches them. */
    private fun queueAccept(cmId: String) {
        val myId = myCmId ?: return
        if (channel == null || !contacts.containsKey(cmId)) return
        val payload = Messages.json.encodeToString(KnockPayload.serializer(),
            KnockPayload(myName, myId)).toByteArray()
        outbox.enqueue(Outbox.Item(peer = cmId, label = "accept", replaceKey = "accept",
            deliver = { sendSecureTo(cmId, FrameType.KNOCK_ACCEPT, payload) },
            onDelivered = { ConnDiag.sys("Add friend: acceptance delivered") }))
    }

    fun declineKnock(req: KnockRequest) {
        ConnDiag.sys("Add friend: knock declined (ignored from them for 1 h)")
        declinedAt[req.cmId] = System.currentTimeMillis()
        _incomingKnocks.value = _incomingKnocks.value.filterNot { it.cmId == req.cmId }
    }

    // ---- incoming ----------------------------------------------------------

    /**
     * Handle one incoming connection SYNCHRONOUSLY (ServerController owns the
     * socket lifecycle, the concurrency cap and the per-read timeout, and closes
     * the socket afterward). [SecureWire.receive] reads one length-bounded frame
     * and authenticates it BEFORE doing anything else: an anonymous knock, or a
     * prekey request from a known contact — answered with a one-time prekey, then
     * exactly one forward-secret frame is read and opened. Everything else is
     * dropped. Received bytes are only ever decrypted/parsed — never executed.
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
                    dispatchAnonymous(r.body)
                }
                is SecureWire.Received.Message -> {
                    val peer = contacts[r.cmId] ?: return
                    ConnDiag.inc("forward-secret frame opened from ${Redact.onionShort(peer.onion)}")
                    // Any authenticated frame proves this friend has me: confirm a
                    // pending one, and retry anything queued for them right now.
                    confirmIfPending(r.cmId)
                    outbox.kick(r.cmId)
                    // Anything they deliberately sent = they were around ("last
                    // seen recently"). Cover traffic is noise and doesn't count.
                    if (r.type != FrameType.COVER) markSeen(r.cmId)
                    if (buzzOnlyMode) dispatchBuzzOnly(r.cmId, r.type)
                    else dispatchFromContact(r.cmId, peer, r.type, r.body)
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

    /**
     * A knock (friend request). It ALWAYS reaches the Friends screen, even while
     * I'm Invisible — first contact has to get through somehow — but it's
     * one-time and rate-limited: duplicates are merged, at most
     * [MAX_PENDING_KNOCKS] wait at once, a declined sender is ignored for an
     * hour, and bursts are throttled.
     */
    private fun dispatchAnonymous(body: ByteArray) {
        val kp = decodeKnock(body) ?: return
        if (CmId.decode(kp.cmId) == null || kp.cmId == myCmId) {
            ConnDiag.inc("KNOCK ignored (malformed or my own ID)"); return
        }
        if (kp.withdraw) {
            // They cancelled their request: take the card away (nothing else).
            val cur = _incomingKnocks.value
            if (cur.any { it.cmId == kp.cmId }) {
                _incomingKnocks.value = cur.filterNot { it.cmId == kp.cmId }
                ConnDiag.inc("KNOCK withdrawn by the sender → request removed")
            }
            return
        }
        val now = System.currentTimeMillis()
        declinedAt[kp.cmId]?.let { if (now - it < DECLINE_COOLDOWN_MS) {
            ConnDiag.inc("KNOCK ignored (declined recently)"); return
        } }
        // Already my (confirmed) friend: they never got my acceptance (e.g. my app
        // closed before it went out) and knocked again. No duplicate card — just
        // re-send the acceptance. A knock isn't authenticated, but this only ever
        // sends to that friend's REAL key + address, so a forged one gains nothing.
        if (contacts.containsKey(kp.cmId) && kp.cmId !in pending) {
            if (!knockRate.allow("knock")) { ConnDiag.inc("KNOCK ignored (rate limit)"); return }
            ConnDiag.inc("KNOCK from an existing friend → acceptance re-sent")
            queueAccept(kp.cmId)
            return
        }
        val cur = _incomingKnocks.value
        if (cur.size >= MAX_PENDING_KNOCKS || cur.any { it.cmId == kp.cmId }) {
            ConnDiag.inc("KNOCK ignored (pending cap or duplicate)"); return
        }
        if (!knockRate.allow("knock")) {
            ConnDiag.inc("KNOCK ignored (rate limit)"); return
        }
        ConnDiag.inc("KNOCK received (waiting for you to accept)")
        _incomingKnocks.value = cur + KnockRequest(kp.displayName.take(24), kp.cmId)
        // A friend request always notifies (also while Invisible).
        notify(Notice.FRIEND_REQUEST)
    }

    private fun dispatchFromContact(fromCmId: String, peer: CmIdData, type: FrameType, body: ByteArray) {
        ConnDiag.inc("dispatched $type")
        when (type) {
            FrameType.MSG -> {
                val t = runCatching {
                    Messages.json.decodeFromString(TextPayload.serializer(), String(body))
                }.getOrNull() ?: return
                // A pasted engine log is never shown as a message (logs stay in
                // Connection/Diagnostics) — a grey notice says one arrived.
                if (org.cmchat.app.chat.EngineLog.looksLikeLog(t.text)) {
                    ConnDiag.inc("message was an engine log → not shown in the chat")
                    inChat(fromCmId) { id -> ChatStore.addSystemLine(id, org.cmchat.app.chat.EngineLog.HIDDEN_NOTICE) }
                    return
                }
                // No delivery/read receipt is ever sent back (receipts dropped).
                // While Invisible, the message is held as "missed" (blue dot);
                // the sender learns nothing, and it surfaces once we go Online.
                val invisible = org.cmchat.app.settings.AppSettings.invisibleMode.value
                if (invisible) ConnDiag.inc("held (Invisible): message kept as missed")
                val chatCmId = inChat(fromCmId) { id ->
                    ChatStore.addTheirs(id, t.id, t.text, SelfTimer.fromLabel(t.selfTimer), missed = invisible); id
                }
                // Generic "Notification" — but NOT while Invisible (then only a Buzz
                // or a friend request notifies; the message waits as "Missed"), and
                // not for the chat that's open in front of the user.
                if (!invisible && !chatOnScreen(chatCmId)) notify(Notice.MESSAGE)
            }
            FrameType.DECOY_ALERT -> onDecoyAlert(fromCmId)
            FrameType.TEAM_CLOCK -> {
                val v = runCatching { String(body, Charsets.US_ASCII) }.getOrNull() ?: return
                // Strictly validated: only a canonical offset or "" (off) is accepted.
                val value = if (v.isEmpty()) null else v.takeIf { org.cmchat.app.chat.TeamClock.decode(it) != null } ?: return
                val chatCmId = inChat(fromCmId) { id ->
                    ChatStore.setTeamHour(id, value, names[id] ?: "Your friend"); id
                }
                onTeamClockChanged?.invoke(chatCmId, value)
            }
            FrameType.ERASE_CHAT -> inChat(fromCmId) { ChatStore.erase(it) }
            FrameType.KNOCK_ACCEPT -> {}   // confirmed + marked seen above
            FrameType.TERMINATE -> {
                // They removed me from their list: remove them from mine too.
                val id = currentId(fromCmId)
                ConnDiag.inc("friend removed you (terminate) → removed them too")
                forget(id)
                onFriendTerminated?.invoke(id)
            }
            FrameType.BUZZ -> onBuzz(fromCmId)
            FrameType.ADDR_UPDATE -> onAddressUpdate(currentId(fromCmId), peer, body)
            FrameType.COVER -> ConnDiag.inc("cover frame discarded")
            else -> {}
        }
    }

    /**
     * A contact rotated their onion. The frame is authenticated (it opened under
     * a key that needed [peer]'s identity), so we trust the new cmId ONLY if it
     * carries the same identity pubkey — then we re-link to the new onion and persist.
     */
    private fun onAddressUpdate(oldCmId: String, peer: CmIdData, body: ByteArray) {
        val newCmId = runCatching { String(body) }.getOrNull() ?: return
        val decoded = CmId.decode(newCmId) ?: return
        if (!decoded.identityPubKeyHex.equals(peer.identityPubKeyHex, ignoreCase = true)) return
        if (newCmId == oldCmId) return
        synchronized(relinkLock) {
            // Order matters for readers that don't take the lock: the new address
            // exists before the old one goes, and the chat has MOVED before the
            // new id is published — whoever sees the new id sees the moved chat.
            contacts[newCmId] = decoded
            names[oldCmId]?.let { names[newCmId] = it }
            if (oldCmId in pending) pending.add(newCmId)
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

    /** When the scout listener is alive, only a BUZZ — and a decoy ALERT, which
     * is a safety signal that mustn't be lost — does anything. */
    private fun dispatchBuzzOnly(chatCmId: String, type: FrameType) {
        when (type) {
            FrameType.BUZZ -> onBuzz(chatCmId)
            FrameType.DECOY_ALERT -> onDecoyAlert(chatCmId)
            FrameType.TERMINATE -> {
                val id = currentId(chatCmId)
                forget(id)
                onFriendTerminated?.invoke(id)
            }
            FrameType.COVER -> {}
            // The app was closed (swiped away): only a Buzz gets through. Say so,
            // so a "my friend's messages never arrive" log shows WHY.
            else -> ConnDiag.inc("app is closed (Buzz-only) → $type dropped; reopen the app to receive messages")
        }
    }

    /** A friend's decoy was tripped: the notice line (chat erased when they leave). */
    private fun onDecoyAlert(fromCmId: String) {
        val chatCmId = inChat(fromCmId) { id -> ChatStore.addDecoyNotice(id); id }
        ConnDiag.inc("decoy alert received")
        // Not a Buzz or a friend request: no notification while Invisible.
        if (!org.cmchat.app.settings.AppSettings.invisibleMode.value && !chatOnScreen(chatCmId)) notify(Notice.MESSAGE)
    }

    /** What a notification is about (its text is always generic). */
    internal enum class Notice { MESSAGE, FRIEND_REQUEST, BUZZ }

    /**
     * Posts a notification. While Invisible only a Buzz or a friend request may
     * notify (the callers decide). Tests swap this to count them, like [dialer].
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
        // also while Invisible.
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
     * request a one-time prekey, verify it against their identity key, then send
     * the X3DH-sealed frame. Throws on any failure (the Outbox retries silently).
     */
    private fun sendSecure(peer: CmIdData, type: FrameType, payload: ByteArray) {
        val ch = channel ?: throw IOException("engine not ready")
        withConnection(peer) { s ->
            // The sender READS one frame (the prekey reply): never wait forever.
            s.soTimeout = HANDSHAKE_READ_TIMEOUT_MS
            SecureWire.send(
                ch, s.getInputStream(), s.getOutputStream(), peer.identityPubKeyHex, type, payload,
                onStage = { ConnDiag.out(it) },
                onVersionMismatch = { versionMismatch.value = true },
            )
        }
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
            // HandshakeFailed carries a fixed, content-free reason; anything else
            // is reduced to a short category. Never keys, contents or addresses.
            val why = if (e is SecureWire.HandshakeFailed) e.message else Transport.failureReason(e)
            ConnDiag.out("FAILED: $why")
            throw e
        }
        ConnDiag.out("CONNECTED — frame delivered (${System.currentTimeMillis() - t0}ms)")
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
}
