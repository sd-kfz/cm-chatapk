package org.cmchat.app.transport

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cmchat.app.chat.ChatStore
import org.cmchat.app.chat.MsgState
import org.cmchat.app.chat.SelfTimer
import org.cmchat.app.crypto.CmId
import org.cmchat.app.crypto.CmIdData
import org.cmchat.app.crypto.CryptoManager
import org.cmchat.app.tor.ServerController
import org.cmchat.app.tor.TorService
import org.cmchat.app.tor.TorStatus
import java.io.IOException
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Frames over Tor. Every frame to a contact (message, status, buzz, erase,
 * address update, cover, knock-accept) goes over the forward-secret v3 handshake
 * in [SecureChannel]: fetch a one-time prekey, X3DH, one AEAD frame. Only the
 * anonymous KNOCK (to a not-yet-contact) is a sealed box. Anything that won't
 * open is dropped. Chats live only in [ChatStore] (RAM).
 *
 * Real delivery needs Tor ONLINE on a device; CI verifies compile + crypto, and
 * the loopback tests run the full handshake over local sockets.
 */
object MessageService {

    data class KnockRequest(val displayName: String, val cmId: String)

    private val _incomingKnocks = MutableStateFlow<List<KnockRequest>>(emptyList())
    val incomingKnocks: StateFlow<List<KnockRequest>> = _incomingKnocks.asStateFlow()

    /** Set by AppNav to persist an accepted contact into the vault. */
    @Volatile
    var onContactAccepted: ((KnockRequest) -> Unit)? = null

    /** Set by AppNav to persist a contact's rotated address (old cmId -> new). */
    @Volatile
    var onContactAddressUpdated: ((oldCmId: String, newCmId: String) -> Unit)? = null

    /** Set by AppNav to persist a Team Clock the friend set (null = turned off). */
    @Volatile
    var onTeamClockChanged: ((cmId: String, value: String?) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var myName: String = ""
    private var myCmId: String? = null

    /** The forward-secret channel for the active Face; null until configured. */
    @Volatile
    private var channel: SecureChannel? = null

    /**
     * Buzz-only mode: the app was swiped away but the scout listener is alive.
     * Only BUZZ frames produce an "Activity" notification; everything else is
     * dropped, and no chat state is kept (ChatStore is already cleared).
     */
    @Volatile
    var buzzOnlyMode: Boolean = false

    /** cmId -> decoded peer (onion + identity pubkey). Concurrent: read by the
     * incoming-connection threads while the UI adds/relinks contacts. */
    private val contacts = ConcurrentHashMap<String, CmIdData>()
    /** cmId -> contact nickname (only used if the user opts into showing it). */
    private val names = ConcurrentHashMap<String, String>()

    /** The chat currently open in the foreground, or null. Set by ChatScreen. */
    @Volatile
    var activeChatCmId: String? = null

    /** Per-contact post-auth rate limit (drop a contact that floods us). */
    private val contactRate = RateLimiter(burst = 20, refillPerSec = 5.0)

    /**
     * Wire protocol version. Bumped whenever the framing/crypto changes so two
     * peers on different builds detect the mismatch instead of failing silently.
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

    /**
     * Anti-forensics: drop the identity key material and contact table held in
     * RAM. Called from the crash handler before the process dies, and safe to
     * call anytime (the next configure() repopulates it).
     */
    fun zeroKeys() {
        channel = null
        myCmId = null
        contacts.clear()
        names.clear()
    }

    fun configure(
        crypto: CryptoManager,
        myDisplayName: String,
        myIdentityPubHex: String,
        myIdentitySecHex: String,
        myCmId: String?,
        knownContactCmIds: List<String>,
        contactNames: Map<String, String> = emptyMap(),
    ) {
        this.myName = myDisplayName
        this.myCmId = myCmId
        this.channel = SecureChannel(crypto, myIdentityPubHex, myIdentitySecHex, codec, replayGuard)
        // Update the friend table WITHOUT an empty moment: configure() re-runs on
        // every vault save, and a clear-then-refill would let a frame arriving
        // in between be dropped as "not from a known contact".
        val fresh = HashMap<String, CmIdData>()
        knownContactCmIds.forEach { id -> CmId.decode(id)?.let { fresh[id] = it } }
        contacts.keys.retainAll(fresh.keys)
        contacts.putAll(fresh)
        names.keys.retainAll(contactNames.keys)
        names.putAll(contactNames)
        ServerController.onIncoming = { socket -> handleIncoming(socket) }
    }

    // ---- outgoing ----------------------------------------------------------

    fun sendKnock(cmId: String, onResult: (Boolean) -> Unit) {
        val ch = channel; val myId = myCmId; val target = CmId.decode(cmId)
        if (ch == null || myId == null || target == null) { onResult(false); return }
        scope.launch {
            val ok = runCatching {
                val payload = Messages.json.encodeToString(KnockPayload.serializer(), KnockPayload(myName, myId))
                    .toByteArray()
                val sealed = ch.sealKnock(payload, target.identityPubKeyHex)
                withTorConnection(target) { s ->
                    Transport.writeFrame(s.getOutputStream(), sealed)
                    org.cmchat.app.diag.ConnDiag.out("knock sent (anonymous sealed box, ${sealed.size}b)")
                }
                true
            }.getOrElse { org.cmchat.app.diag.Diag.e("knock", "send failed", it); false }
            org.cmchat.app.diag.Diag.i("knock", "sent=$ok")
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    /**
     * Fire-and-forget BUZZ: no ack, no retry, no state, no content. Rate-limited
     * to one per contact per [BuzzPolicy.SEND_COOLDOWN_MS]. Returns false if on
     * cooldown or not configured.
     */
    fun sendBuzz(chatCmId: String): Boolean {
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
    fun sendCover(chatCmId: String) {
        val peer = contacts[chatCmId] ?: return
        if (channel == null) return
        val junk = ByteArray((8..400).random()).also { java.security.SecureRandom().nextBytes(it) }
        scope.launch {
            runCatching { sendSecure(peer, FrameType.COVER, junk) }
                .onFailure { org.cmchat.app.diag.Diag.e("cover", "send failed", it) }
        }
    }

    /** Contacts currently known (for the cover-traffic picker). */
    fun contactIds(): List<String> = contacts.keys.toList()

    /** Send a text message; updates [ChatStore] state to SENT or OFFLINE. */
    fun sendText(chatCmId: String, text: String, timer: SelfTimer) {
        // Messaging a person re-opens their "Once only" buzzes.
        org.cmchat.app.buzz.BuzzPolicy.onMessagedContact(chatCmId)
        // Per-message timer wins; otherwise fall back to the general timer.
        val effective = if (timer != SelfTimer.OFF) timer
            else org.cmchat.app.settings.AppSettings.generalTimer.value
        sendTextResolved(chatCmId, text, effective)
    }

    private fun sendTextResolved(chatCmId: String, text: String, timer: SelfTimer) {
        val msg = ChatStore.addMine(chatCmId, text, timer)
        val peer = contacts[chatCmId]
        if (channel == null || peer == null) {
            ChatStore.setState(chatCmId, msg.id, MsgState.OFFLINE); return
        }
        scope.launch {
            val ok = runCatching {
                val payload = Messages.json.encodeToString(TextPayload.serializer(),
                    TextPayload(msg.id, text, timer.label)).toByteArray()
                sendSecure(peer, FrameType.MSG, payload)
                true
            }.getOrDefault(false)
            ChatStore.setState(chatCmId, msg.id, if (ok) MsgState.SENT else MsgState.OFFLINE)
        }
    }

    fun retry(chatCmId: String, msgId: String, text: String, timer: SelfTimer) {
        val peer = contacts[chatCmId] ?: return
        if (channel == null) return
        ChatStore.setState(chatCmId, msgId, MsgState.SENDING)
        scope.launch {
            val ok = runCatching {
                val payload = Messages.json.encodeToString(TextPayload.serializer(),
                    TextPayload(msgId, text, timer.label)).toByteArray()
                sendSecure(peer, FrameType.MSG, payload); true
            }.getOrDefault(false)
            ChatStore.setState(chatCmId, msgId, if (ok) MsgState.SENT else MsgState.OFFLINE)
        }
    }

    fun sendErase(chatCmId: String) {
        ChatStore.erase(chatCmId)
        val peer = contacts[chatCmId] ?: return
        if (channel == null) return
        scope.launch { runCatching { sendSecure(peer, FrameType.ERASE_CHAT, ByteArray(0)) } }
    }

    /**
     * Decoy tripped (this phone may be in someone else's hands): INSTANTLY wipe
     * MY side from RAM — every conversation, buzz marker and tool note — and send
     * every friend a DECOY_ALERT. The alert deletes NOTHING on their side: it
     * shows a red timestamped "Decoy chat tripped." line in their chat and they
     * keep their history until they clear it. Best-effort: a friend who is
     * offline right now won't get it (there's no server to hold it).
     * The caller then rotates the onion address and locks the app.
     */
    fun tripDecoy() {
        val peers = contacts.values.toList()
        // Clear local RAM first so the wipe is immediate even if sends are slow.
        ChatStore.clearAll()
        org.cmchat.app.buzz.BuzzPolicy.clear()
        org.cmchat.app.tools.ToolsState.clear()
        activeChatCmId = null
        if (channel == null) return
        peers.forEach { peer ->
            scope.launch {
                runCatching { sendSecure(peer, FrameType.DECOY_ALERT, ByteArray(0)) }
                    .onFailure { org.cmchat.app.diag.ConnDiag.out("decoy alert not delivered (friend offline?)") }
            }
        }
    }

    /**
     * Share this conversation's Team Clock with the friend ([value] = canonical
     * "UTC+hh:mm", or "" to turn it off). Rides the forward-secret channel like a
     * message. Returns false if the engine/friend isn't ready (shown to the user).
     */
    fun sendTeamClock(chatCmId: String, value: String): Boolean {
        val peer = contacts[chatCmId] ?: return false
        if (channel == null) return false
        if (value.isNotEmpty() && org.cmchat.app.chat.TeamClock.decode(value) == null) return false
        scope.launch {
            runCatching { sendSecure(peer, FrameType.TEAM_CLOCK, value.toByteArray(Charsets.US_ASCII)) }
                .onFailure { org.cmchat.app.diag.Diag.e("teamclock", "send failed", it) }
        }
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
        contacts.values.toList().forEach { peer ->
            scope.launch { runCatching { sendSecure(peer, FrameType.ADDR_UPDATE, payload) } }
        }
    }

    fun acceptKnock(req: KnockRequest) {
        org.cmchat.app.diag.ConnDiag.inc("knock accepted → added as contact")
        CmId.decode(req.cmId)?.let { contacts[req.cmId] = it }
        _incomingKnocks.value = _incomingKnocks.value.filterNot { it.cmId == req.cmId }
        onContactAccepted?.invoke(req)
        // Tell them we accepted (forward-secret, now that we know their key).
        val peer = contacts[req.cmId]
        val myId = myCmId
        if (channel != null && peer != null && myId != null) {
            scope.launch {
                runCatching {
                    val payload = Messages.json.encodeToString(KnockPayload.serializer(),
                        KnockPayload(myName, myId)).toByteArray()
                    sendSecure(peer, FrameType.KNOCK_ACCEPT, payload)
                }
            }
        }
    }

    fun declineKnock(req: KnockRequest) {
        org.cmchat.app.diag.ConnDiag.inc("knock declined")
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
                onStage = { org.cmchat.app.diag.ConnDiag.inc(it) },
            )
            when (r) {
                is SecureWire.Received.Knock -> {
                    org.cmchat.app.diag.ConnDiag.inc("opened as KNOCK (anonymous sealed box)")
                    dispatchAnonymous(r.body)
                }
                is SecureWire.Received.Message -> {
                    val peer = contacts[r.cmId] ?: return
                    org.cmchat.app.diag.ConnDiag.inc(
                        "forward-secret frame opened from ${org.cmchat.app.diag.Redact.onionShort(peer.onion)}")
                    if (buzzOnlyMode) dispatchBuzzOnly(r.cmId, r.type)
                    else dispatchFromContact(r.cmId, peer, r.type, r.body)
                }
                SecureWire.Received.VersionMismatch -> {
                    versionMismatch.value = true
                    org.cmchat.app.diag.ConnDiag.inc("wire version mismatch → dropped (update both apps)")
                    org.cmchat.app.diag.Diag.droppedFrame()
                }
                is SecureWire.Received.Dropped -> {
                    // Static reason strings only — never keys, contents or addresses.
                    org.cmchat.app.diag.ConnDiag.inc("${r.reason} → dropped")
                    org.cmchat.app.diag.Diag.droppedFrame()
                }
            }
        } catch (_: Exception) {
            org.cmchat.app.diag.ConnDiag.inc("incoming error → connection dropped")
            org.cmchat.app.diag.Diag.droppedFrame()
        }
    }

    private fun dispatchAnonymous(body: ByteArray) {
        val kp = decodeKnock(body) ?: return
        val cur = _incomingKnocks.value
        // Cap pending knocks (flood guard) and de-dup by cmId.
        if (cur.size >= MAX_PENDING_KNOCKS || cur.any { it.cmId == kp.cmId }) {
            org.cmchat.app.diag.ConnDiag.inc("KNOCK ignored (pending cap or duplicate)"); return
        }
        org.cmchat.app.diag.ConnDiag.inc("KNOCK received (pending accept)")
        _incomingKnocks.value = cur + KnockRequest(kp.displayName, kp.cmId)
    }

    private fun dispatchFromContact(chatCmId: String, peer: CmIdData, type: FrameType, body: ByteArray) {
        org.cmchat.app.diag.ConnDiag.inc("dispatched $type")
        when (type) {
            FrameType.MSG -> {
                val t = runCatching {
                    Messages.json.decodeFromString(TextPayload.serializer(), String(body))
                }.getOrNull() ?: return
                // No delivery/read receipt is ever sent back (receipts dropped).
                // While Invisible, the message is held as "missed" (orange dot);
                // the sender learns nothing, and it surfaces once we go Online.
                val invisible = org.cmchat.app.settings.AppSettings.invisibleMode.value
                if (invisible) org.cmchat.app.diag.ConnDiag.inc("held (Invisible): message kept as missed")
                ChatStore.addTheirs(chatCmId, t.id, t.text, SelfTimer.fromLabel(t.selfTimer), missed = invisible)
                // Generic "Notification" unless that chat is already on screen.
                if (activeChatCmId != chatCmId) {
                    org.cmchat.app.settings.AppSettings.appContext?.let { ctx ->
                        org.cmchat.app.notify.Notifier.message(ctx)
                    }
                }
            }
            FrameType.DECOY_ALERT -> onDecoyAlert(chatCmId)
            FrameType.TEAM_CLOCK -> {
                val v = runCatching { String(body, Charsets.US_ASCII) }.getOrNull() ?: return
                // Strictly validated: only a canonical offset or "" (off) is accepted.
                val value = if (v.isEmpty()) null else v.takeIf { org.cmchat.app.chat.TeamClock.decode(it) != null } ?: return
                ChatStore.setTeamHour(chatCmId, value, names[chatCmId] ?: "Your friend")
                onTeamClockChanged?.invoke(chatCmId, value)
            }
            FrameType.ERASE_CHAT -> ChatStore.erase(chatCmId)
            FrameType.KNOCK_ACCEPT -> ChatStore.touchPeer(chatCmId)
            FrameType.BUZZ -> onBuzz(chatCmId)
            FrameType.ADDR_UPDATE -> onAddressUpdate(chatCmId, peer, body)
            FrameType.COVER -> org.cmchat.app.diag.ConnDiag.inc("cover frame discarded")
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
        contacts.remove(oldCmId)
        contacts[newCmId] = decoded
        names.remove(oldCmId)?.let { names[newCmId] = it }
        onContactAddressUpdated?.invoke(oldCmId, newCmId)
        org.cmchat.app.diag.Diag.i("addr", "contact relinked to new address")
    }

    /** When the scout listener is alive, only a BUZZ — and a decoy ALERT, which
     * is a safety signal that mustn't be lost — does anything. */
    private fun dispatchBuzzOnly(chatCmId: String, type: FrameType) {
        when (type) {
            FrameType.BUZZ -> onBuzz(chatCmId)
            FrameType.DECOY_ALERT -> onDecoyAlert(chatCmId)
            else -> {}
        }
    }

    /** A friend's decoy was tripped: red timestamped line + a generic notification. */
    private fun onDecoyAlert(chatCmId: String) {
        ChatStore.addAlert(chatCmId, "Decoy chat tripped.")
        org.cmchat.app.diag.ConnDiag.inc("decoy alert received")
        if (activeChatCmId != chatCmId) {
            org.cmchat.app.settings.AppSettings.appContext?.let { org.cmchat.app.notify.Notifier.message(it) }
        }
    }

    /** A buzz arrived: throttle by the receiver setting, then shake + notify. */
    private fun onBuzz(chatCmId: String) {
        if (!org.cmchat.app.buzz.BuzzPolicy.accept(chatCmId)) return
        // Shake the chat if it's on screen (the UI collects this per-chat);
        // otherwise leave a blue Buzz dot on that friend until the chat is opened.
        org.cmchat.app.buzz.BuzzPolicy.requestShake(chatCmId)
        if (activeChatCmId != chatCmId) ChatStore.markBuzzed(chatCmId)
        // Generic "Activity" bar notification; nickname only if opted in.
        org.cmchat.app.settings.AppSettings.appContext?.let { ctx ->
            org.cmchat.app.notify.Notifier.activity(ctx)
        }
    }

    // ---- wire helpers ------------------------------------------------------

    /**
     * Send ONE content frame to a contact over the forward-secret handshake:
     * request a one-time prekey, verify it against their identity key, then send
     * the X3DH-sealed frame. Throws on any failure, so callers mark the message
     * OFFLINE (with retry) — a send never fails silently.
     */
    private fun sendSecure(peer: CmIdData, type: FrameType, payload: ByteArray) {
        val ch = channel ?: throw IOException("engine not ready")
        withTorConnection(peer) { s ->
            // The sender now READS one frame (the prekey reply): never wait forever.
            s.soTimeout = HANDSHAKE_READ_TIMEOUT_MS
            SecureWire.send(
                ch, s.getInputStream(), s.getOutputStream(), peer.identityPubKeyHex, type, payload,
                onStage = { org.cmchat.app.diag.ConnDiag.out(it) },
                onVersionMismatch = { versionMismatch.value = true },
            )
        }
    }

    /** Open a Tor connection to [peer]'s onion, run [block] on it, then close it. */
    private fun withTorConnection(peer: CmIdData, block: (Socket) -> Unit) {
        // Fail closed: never attempt a connection unless Tor is up. Retry with
        // backoff so a send right after publish (descriptor still uploading)
        // doesn't hard-fail. Onion-only guard lives in Transport. Every stage is
        // logged to the Connection diagnostic (addresses redacted to a prefix).
        val short = org.cmchat.app.diag.Redact.onionShort(peer.onion)
        org.cmchat.app.diag.ConnDiag.out("resolve $short")
        if (TorService.status.value !is TorStatus.Online) {
            org.cmchat.app.diag.ConnDiag.out("FAILED: Tor offline")
            throw IOException("Tor offline")
        }
        // Timing jitter: a small randomized delay so exact send time doesn't map
        // 1:1 to typing/sending. Applies to every outbound frame (incl. cover).
        runCatching { Thread.sleep((30..260).random().toLong()) }
        val t0 = System.currentTimeMillis()
        val sock = try {
            Transport.connectThroughTorRetry(
                TorService.socksPort(), peer.onion.removeSuffix(".onion"), 80,
                onStage = { org.cmchat.app.diag.ConnDiag.out(it) },
            )
        } catch (e: Exception) {
            org.cmchat.app.diag.ConnDiag.out("FAILED: ${Transport.failureReason(e)}")
            throw e
        }
        try {
            sock.use { block(it) }
        } catch (e: Exception) {
            // HandshakeFailed carries a fixed, content-free reason; anything else
            // is reduced to a short category. Never keys, contents or addresses.
            val why = if (e is SecureWire.HandshakeFailed) e.message else Transport.failureReason(e)
            org.cmchat.app.diag.ConnDiag.out("FAILED: $why")
            throw e
        }
        org.cmchat.app.diag.ConnDiag.out("CONNECTED — frame delivered (${System.currentTimeMillis() - t0}ms)")
    }

    /**
     * Link Test: a real end-to-end connectivity probe to one contact. Sends a
     * lightweight BUZZ (no content) over the full Tor→onion path and the whole
     * forward-secret handshake, so BOTH phones log every stage — outgoing here,
     * incoming on the contact's Connection log.
     */
    fun linkTest(cmId: String) {
        val peer = contacts[cmId]
        if (channel == null || peer == null) {
            org.cmchat.app.diag.ConnDiag.sys("Link Test: contact or engine not ready"); return
        }
        org.cmchat.app.diag.ConnDiag.sys("── Link Test → ${org.cmchat.app.diag.Redact.onionShort(peer.onion)} ──")
        scope.launch {
            val ok = runCatching { sendSecure(peer, FrameType.BUZZ, ByteArray(0)); true }
                .getOrDefault(false)
            org.cmchat.app.diag.ConnDiag.sys("Link Test result: ${if (ok) "CONNECTED" else "FAILED"}")
        }
    }

    private fun decodeKnock(body: ByteArray): KnockPayload? = runCatching {
        Messages.json.decodeFromString(KnockPayload.serializer(), String(body))
    }.getOrNull()
}
