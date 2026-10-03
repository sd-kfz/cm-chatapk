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
import java.net.Socket

/**
 * Frames over Tor. KNOCK is an anonymous sealed box (sender not yet known);
 * MSG/ACK/STATUS/ERASE_CHAT are crypto_box between two known identities.
 * Anything that won't open is dropped. Chats live only in [ChatStore] (RAM).
 *
 * Real delivery needs Tor ONLINE on a device; CI verifies compile + crypto.
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var crypto: CryptoManager? = null
    private var myPub: String? = null
    private var mySec: String? = null
    private var myName: String = ""
    private var myCmId: String? = null

    /**
     * Buzz-only mode: the app was swiped away but the scout listener is alive.
     * Only BUZZ frames produce an "Activity" notification; everything else is
     * dropped, and no chat state is kept (ChatStore is already cleared).
     */
    @Volatile
    var buzzOnlyMode: Boolean = false

    /** cmId -> decoded peer (onion + identity pubkey). */
    private val contacts = mutableMapOf<String, CmIdData>()
    /** cmId -> contact nickname (only used if the user opts into showing it). */
    private val names = mutableMapOf<String, String>()

    /** The chat currently open in the foreground, or null. Set by ChatScreen. */
    @Volatile
    var activeChatCmId: String? = null

    /** Per-contact post-auth rate limit (drop a contact that floods us). */
    private val contactRate = RateLimiter(burst = 20, refillPerSec = 5.0)

    /** Cap pending knock requests so a knock flood can't grow RAM without bound. */
    private const val MAX_PENDING_KNOCKS = 20

    fun configure(
        crypto: CryptoManager,
        myDisplayName: String,
        myIdentityPubHex: String,
        myIdentitySecHex: String,
        myCmId: String?,
        knownContactCmIds: List<String>,
        contactNames: Map<String, String> = emptyMap(),
    ) {
        this.crypto = crypto
        this.myName = myDisplayName
        this.myPub = myIdentityPubHex
        this.mySec = myIdentitySecHex
        this.myCmId = myCmId
        contacts.clear()
        knownContactCmIds.forEach { id -> CmId.decode(id)?.let { contacts[id] = it } }
        names.clear(); names.putAll(contactNames)
        ServerController.onIncoming = { socket -> handleIncoming(socket) }
    }

    // ---- outgoing ----------------------------------------------------------

    fun sendKnock(cmId: String, onResult: (Boolean) -> Unit) {
        val c = crypto; val myId = myCmId; val target = CmId.decode(cmId)
        if (c == null || myId == null || target == null) { onResult(false); return }
        scope.launch {
            val ok = runCatching {
                val inner = framed(FrameType.KNOCK,
                    Messages.json.encodeToString(KnockPayload.serializer(), KnockPayload(myName, myId)).toByteArray())
                sendRaw(target, c.sealedSeal(inner, target.identityPubKeyHex))
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
        val c = crypto; val sec = mySec; val peer = contacts[chatCmId]
        if (c == null || sec == null || peer == null) return false
        if (!org.cmchat.app.buzz.BuzzPolicy.canSend(chatCmId)) return false
        org.cmchat.app.buzz.BuzzPolicy.markSent(chatCmId)
        scope.launch {
            runCatching { sendBox(c, sec, peer, FrameType.BUZZ, ByteArray(0)) }
                .onFailure { org.cmchat.app.diag.Diag.e("buzz", "send failed", it) }
        }
        return true
    }

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
        val c = crypto; val sec = mySec; val peer = contacts[chatCmId]
        if (c == null || sec == null || peer == null) {
            ChatStore.setState(chatCmId, msg.id, MsgState.OFFLINE); return
        }
        scope.launch {
            val ok = runCatching {
                val payload = Messages.json.encodeToString(TextPayload.serializer(),
                    TextPayload(msg.id, text, timer.label)).toByteArray()
                sendBox(c, sec, peer, FrameType.MSG, payload)
                true
            }.getOrDefault(false)
            ChatStore.setState(chatCmId, msg.id, if (ok) MsgState.SENT else MsgState.OFFLINE)
        }
    }

    fun retry(chatCmId: String, msgId: String, text: String, timer: SelfTimer) {
        val c = crypto; val sec = mySec; val peer = contacts[chatCmId] ?: return
        if (c == null || sec == null) return
        ChatStore.setState(chatCmId, msgId, MsgState.SENDING)
        scope.launch {
            val ok = runCatching {
                val payload = Messages.json.encodeToString(TextPayload.serializer(),
                    TextPayload(msgId, text, timer.label)).toByteArray()
                sendBox(c, sec, peer, FrameType.MSG, payload); true
            }.getOrDefault(false)
            ChatStore.setState(chatCmId, msgId, if (ok) MsgState.SENT else MsgState.OFFLINE)
        }
    }

    fun sendErase(chatCmId: String) {
        ChatStore.erase(chatCmId)
        val c = crypto; val sec = mySec; val peer = contacts[chatCmId] ?: return
        if (c == null || sec == null) return
        scope.launch { runCatching { sendBox(c, sec, peer, FrameType.ERASE_CHAT, ByteArray(0)) } }
    }

    /**
     * Tell every contact my new CMC-ID after rotating my onion. Authenticated by
     * crypto_box from my identity key (only I can produce it) — the "signed"
     * address-update. Contacts auto-relink to the new onion.
     */
    fun sendAddressUpdate(newCmId: String) {
        val c = crypto ?: return; val sec = mySec ?: return
        myCmId = newCmId
        val payload = newCmId.toByteArray()
        contacts.values.toList().forEach { peer ->
            scope.launch { runCatching { sendBox(c, sec, peer, FrameType.ADDR_UPDATE, payload) } }
        }
    }

    fun sendStatus(word: String, colorArgb: Long) {
        val c = crypto ?: return; val sec = mySec ?: return
        val payload = Messages.json.encodeToString(StatusPayload.serializer(),
            StatusPayload(word, colorArgb)).toByteArray()
        contacts.values.forEach { peer ->
            scope.launch { runCatching { sendBox(c, sec, peer, FrameType.STATUS, payload) } }
        }
    }

    fun acceptKnock(req: KnockRequest) {
        CmId.decode(req.cmId)?.let { contacts[req.cmId] = it }
        _incomingKnocks.value = _incomingKnocks.value.filterNot { it.cmId == req.cmId }
        onContactAccepted?.invoke(req)
        // Tell them we accepted (crypto_box, now that we know their key).
        val c = crypto; val sec = mySec; val peer = contacts[req.cmId]
        val myId = myCmId
        if (c != null && sec != null && peer != null && myId != null) {
            scope.launch {
                runCatching {
                    val payload = Messages.json.encodeToString(KnockPayload.serializer(),
                        KnockPayload(myName, myId)).toByteArray()
                    sendBox(c, sec, peer, FrameType.KNOCK_ACCEPT, payload)
                }
            }
        }
    }

    fun declineKnock(req: KnockRequest) {
        _incomingKnocks.value = _incomingKnocks.value.filterNot { it.cmId == req.cmId }
    }

    // ---- incoming ----------------------------------------------------------

    /**
     * Handle one incoming connection SYNCHRONOUSLY (ServerController owns the
     * socket lifecycle, the concurrency cap and the socket read timeout, and
     * closes the socket afterward). We read exactly ONE length-bounded frame,
     * then authenticate it (open) BEFORE doing any further work; a frame that
     * opens with no key is dropped without allocating or acting on anything.
     * Received bytes are only ever decrypted/parsed — never executed.
     */
    private fun handleIncoming(socket: Socket) {
        val sealed = Transport.readFrame(socket.getInputStream()) ?: return
        val c = crypto ?: return
        val pub = myPub ?: return
        val sec = mySec ?: return

        // 1) KNOCK: anonymous sealed box, openable with my key alone.
        c.sealedOpen(sealed, pub, sec)?.let { inner ->
            dispatchAnonymous(inner); return
        }
        // 2) crypto_box from a known contact: try each contact's key.
        for ((cmId, peer) in contacts) {
            val inner = c.boxOpen(sealed, peer.identityPubKeyHex, sec) ?: continue
            // Per-contact (post-auth) rate limit — drop a contact that floods us.
            if (!contactRate.allow(cmId)) { org.cmchat.app.diag.Diag.droppedFrame(); return }
            if (buzzOnlyMode) dispatchBuzzOnly(cmId, inner)
            else dispatchFromContact(cmId, peer, inner)
            return
        }
        // Couldn't decrypt with any key -> drop (count only, no content).
        org.cmchat.app.diag.Diag.droppedFrame()
    }

    private fun dispatchAnonymous(inner: ByteArray) {
        if (inner.isEmpty()) return
        val type = FrameType.fromCode(inner[0].toInt() and 0xff) ?: return
        if (type == FrameType.KNOCK) {
            val kp = decodeKnock(inner) ?: return
            val cur = _incomingKnocks.value
            // Cap pending knocks (flood guard) and de-dup by cmId.
            if (cur.size >= MAX_PENDING_KNOCKS || cur.any { it.cmId == kp.cmId }) return
            _incomingKnocks.value = cur + KnockRequest(kp.displayName, kp.cmId)
        }
    }

    private fun dispatchFromContact(chatCmId: String, peer: CmIdData, inner: ByteArray) {
        if (inner.isEmpty()) return
        val type = FrameType.fromCode(inner[0].toInt() and 0xff) ?: return
        val body = inner.copyOfRange(1, inner.size)
        when (type) {
            FrameType.MSG -> {
                val t = runCatching {
                    Messages.json.decodeFromString(TextPayload.serializer(), String(body))
                }.getOrNull() ?: return
                // No delivery/read receipt is ever sent back (receipts dropped).
                // While Invisible, the message is held as "missed" (orange dot);
                // the sender learns nothing, and it surfaces once we go Online.
                val invisible = org.cmchat.app.settings.AppSettings.invisibleMode.value
                ChatStore.addTheirs(chatCmId, t.id, t.text, SelfTimer.fromLabel(t.selfTimer), missed = invisible)
                // Generic "Notification" unless that chat is already on screen.
                if (activeChatCmId != chatCmId) {
                    org.cmchat.app.settings.AppSettings.appContext?.let { ctx ->
                        org.cmchat.app.notify.Notifier.message(ctx)
                    }
                }
            }
            FrameType.STATUS -> {
                val st = runCatching {
                    Messages.json.decodeFromString(StatusPayload.serializer(), String(body))
                }.getOrNull() ?: return
                ChatStore.setPeerStatus(chatCmId, st.word, st.colorArgb)
            }
            FrameType.ERASE_CHAT -> ChatStore.erase(chatCmId)
            FrameType.KNOCK_ACCEPT -> ChatStore.touchPeer(chatCmId)
            FrameType.BUZZ -> onBuzz(chatCmId)
            FrameType.ADDR_UPDATE -> onAddressUpdate(chatCmId, peer, body)
            else -> {}
        }
    }

    /**
     * A contact rotated their onion. The frame is authenticated (we opened it
     * with [peer]'s identity key), so we trust the new cmId ONLY if it carries
     * the same identity pubkey — then we re-link to the new onion and persist.
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

    /** When the scout listener is alive, only a BUZZ does anything. */
    private fun dispatchBuzzOnly(chatCmId: String, inner: ByteArray) {
        if (inner.isEmpty()) return
        val type = FrameType.fromCode(inner[0].toInt() and 0xff) ?: return
        if (type == FrameType.BUZZ) onBuzz(chatCmId)
    }

    /** A buzz arrived: throttle by the receiver setting, then shake + notify. */
    private fun onBuzz(chatCmId: String) {
        if (!org.cmchat.app.buzz.BuzzPolicy.accept(chatCmId)) return
        // Shake the chat if it's on screen (the UI collects this per-chat).
        org.cmchat.app.buzz.BuzzPolicy.requestShake(chatCmId)
        // Generic "Activity" bar notification; nickname only if opted in.
        org.cmchat.app.settings.AppSettings.appContext?.let { ctx ->
            org.cmchat.app.notify.Notifier.activity(ctx)
        }
    }

    // ---- wire helpers ------------------------------------------------------

    private fun sendBox(c: CryptoManager, mySecHex: String, peer: CmIdData, type: FrameType, payload: ByteArray) {
        val sealed = c.boxSeal(framed(type, payload), peer.identityPubKeyHex, mySecHex)
        sendRaw(peer, sealed)
    }

    private fun sendRaw(peer: CmIdData, sealed: ByteArray) {
        // Fail closed: never attempt a connection unless Tor is up. Retry with
        // backoff so a send right after publish (descriptor still uploading)
        // doesn't hard-fail. Onion-only guard lives in Transport.
        if (TorService.status.value !is TorStatus.Online) throw java.io.IOException("Tor offline")
        Transport.connectThroughTorRetry(TorService.socksPort(), peer.onion.removeSuffix(".onion"), 80)
            .use { s -> Transport.writeFrame(s.getOutputStream(), sealed) }
    }

    private fun decodeKnock(inner: ByteArray): KnockPayload? = runCatching {
        Messages.json.decodeFromString(KnockPayload.serializer(), String(inner, 1, inner.size - 1))
    }.getOrNull()

    private fun framed(type: FrameType, payload: ByteArray): ByteArray {
        val out = ByteArray(1 + payload.size)
        out[0] = type.code.toByte()
        payload.copyInto(out, 1)
        return out
    }
}
