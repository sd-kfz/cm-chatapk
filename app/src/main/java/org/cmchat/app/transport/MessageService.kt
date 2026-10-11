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


object MessageService {

    data class KnockRequest(val displayName: String, val cmId: String)

    private val _incomingKnocks = MutableStateFlow<List<KnockRequest>>(emptyList())
    val incomingKnocks: StateFlow<List<KnockRequest>> = _incomingKnocks.asStateFlow()


    @Volatile
    var onContactAccepted: ((KnockRequest) -> Unit)? = null


    @Volatile
    var onFriendConfirmed: ((cmId: String) -> Unit)? = null


    @Volatile
    var onContactAddressUpdated: ((oldCmId: String, newCmId: String) -> Unit)? = null


    @Volatile
    var onTeamClockChanged: ((cmId: String, value: String?, atMs: Long) -> Unit)? = null


    @Volatile
    var onTeamClockSynced: ((cmId: String, atMs: Long) -> Unit)? = null


    @Volatile
    var onFriendName: ((cmId: String, name: String) -> Unit)? = null


    @Volatile
    var onNameConfirmed: ((cmId: String, name: String) -> Unit)? = null


    @Volatile
    var onFriendTerminated: ((cmId: String) -> Unit)? = null


    @Volatile
    var onTerminationDelivered: ((cmId: String) -> Unit)? = null


    @Volatile
    var onPeerSeen: ((cmId: String, atMs: Long) -> Unit)? = null


    @Volatile
    var onAddressConfirmed: ((friendCmId: String, myCmId: String) -> Unit)? = null


    @Volatile
    var flushVault: (() -> Unit)? = null


    @Volatile
    var heldDir: File? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var myName: String = ""
    private var myCmId: String? = null
    private var myPubHex: String? = null
    private var mySecHex: String? = null


    @Volatile
    private var channel: SecureChannel? = null


    @Volatile
    var buzzOnlyMode: Boolean = false


    private val contacts = ConcurrentHashMap<String, CmIdData>()

    private val names = ConcurrentHashMap<String, String>()

    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val relinked = ConcurrentHashMap<String, String>()


    fun currentId(cmId: String): String {
        var id = cmId
        repeat(8) { id = relinked[id] ?: return id }
        return id
    }


    private val relinkLock = Any()


    private inline fun <T> inChat(cmId: String, write: (String) -> T): T =
        synchronized(relinkLock) { write(currentId(cmId)) }


    @Volatile
    var activeChatCmId: String? = null


    private val contactRate = RateLimiter(burst = 20, refillPerSec = 5.0)


    private val knockRate = RateLimiter(burst = 6, refillPerSec = 1.0 / 20)

    private const val DECLINE_COOLDOWN_MS = 60 * 60_000L
    private val declinedAt = ConcurrentHashMap<String, Long>()

    private const val KNOCK_RESEND_MS = 60_000L

    private const val KNOCK_AUTO_RESEND_MS = 10 * 60_000L
    private val knockSentAt = ConcurrentHashMap<String, Long>()

    private val knockDelivered: MutableSet<String> = ConcurrentHashMap.newKeySet()


    private val terminations: MutableSet<String> = ConcurrentHashMap.newKeySet()


    private val addressConfirmed = ConcurrentHashMap<String, String>()


    private val nameConfirmed = ConcurrentHashMap<String, String>()


    private val teamClockAt = ConcurrentHashMap<String, Long>()


    private const val SEEN_PERSIST_EVERY_MS = 30 * 60_000L
    private val seenPersistedAt = ConcurrentHashMap<String, Long>()


    const val WIRE_VERSION = SecureChannel.WIRE_VERSION


    private val codec = InnerCodec()
    private val replayGuard = ReplayGuard()


    private const val HANDSHAKE_READ_TIMEOUT_MS = 30_000


    val versionMismatch = MutableStateFlow(false)


    private const val MAX_PENDING_KNOCKS = 20


    private const val FILE_LANE = "#file"

    private const val MAX_INCOMING_FILES = 1
    private val incomingFiles = java.util.concurrent.atomic.AtomicInteger(0)


    private val outbox = Outbox(scope)
    @Volatile private var torWatch: Job? = null




    @Volatile private var vaultOpen = false
    @Volatile private var vaultWanted = false

    private val holdLock = Any()
    @Volatile private var held: HeldInbox? = null


    private const val MAX_DEDUP = 4096
    private val deliveredIds = object : LinkedHashMap<String, Boolean>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > MAX_DEDUP
    }

    private val heldIds = HashSet<String>()


    @Volatile
    internal var dialer: (CmIdData) -> Socket = { peer -> torDial(peer) }


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
        _incomingKnocks.value = emptyList()
        activeChatCmId = null
        org.cmchat.app.vault.PendingVaultEdits.clear()
    }


    internal fun keysInRam(): Boolean =
        channel != null || mySecHex != null || myPubHex != null || contacts.isNotEmpty()


    internal fun contactCount(): Int = contacts.size


    internal fun outboxIsEmpty(): Boolean = outbox.isEmpty()


    fun clearOutbox() = outbox.clear()


    fun dropQueuedContent() = outbox.retainOnly { it.keepOnClose }


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

        confirmedAddresses: Map<String, String> = emptyMap(),

        confirmedNames: Map<String, String> = emptyMap(),

        teamClockTimes: Map<String, Long> = emptyMap(),

        unsyncedTeamClocks: Map<String, Pair<String, Long>> = emptyMap(),
    ) {
        this.myName = myDisplayName
        this.myCmId = myCmId
        this.myPubHex = myIdentityPubHex
        this.mySecHex = myIdentitySecHex
        this.channel = SecureChannel(crypto, myIdentityPubHex, myIdentitySecHex, codec, replayGuard)
        heldDir?.let { dir -> if (held == null) held = HeldInbox(dir, crypto) }





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


        confirmedAddresses.forEach { (id, mine) -> addressConfirmed.putIfAbsent(currentId(id), mine) }
        addressConfirmed.keys.retainAll(contacts.keys)
        confirmedNames.forEach { (id, n) -> nameConfirmed.putIfAbsent(currentId(id), n) }
        nameConfirmed.keys.retainAll(contacts.keys)
        teamClockTimes.forEach { (id, at) -> teamClockAt.merge(currentId(id), at) { a, b -> maxOf(a, b) } }
        ServerController.onIncoming = { socket -> handleIncoming(socket) }

        pendingTerminations.forEach { id -> if (id !in terminations) queueTerminate(id) }



        if (torWatch == null) {
            torWatch = scope.launch {
                launch {
                    TorService.status.collect {
                        if (it is TorStatus.Online) {
                            outbox.kickAll(); resendPendingKnocks(); resendAddress()
                            org.cmchat.app.tor.TorClock.log()
                        }
                    }
                }


                TorService.reconnects.collect { if (it > 0) { outbox.kickAll(); resendAddress() } }
            }
        }
        resendPendingKnocks()
        resendAddress()
        resendName()

        unsyncedTeamClocks.forEach { (id, v) ->
            val cur = currentId(id)
            if (!outbox.has(cur) { it.replaceKey == "teamclock" && it.tag == v.second }) sendTeamClock(cur, v.first, v.second)
        }
    }



    enum class KnockResult { QUEUED, NOT_READY, INVALID, SELF, TOO_SOON }


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


    fun cancelPending(cmId: String): Boolean {
        val id = currentId(cmId)
        if (id !in pending) return false
        val target = contacts[id] ?: CmId.decode(id)
        forget(id)
        ConnDiag.sys("Add friend: request cancelled")
        if (target != null) queueKnock(id, target, withdraw = true)
        return true
    }


    fun deleteFriend(cmId: String) {
        val id = currentId(cmId)
        val pub = contacts[id]?.identityPubKeyHex ?: CmId.decode(id)?.identityPubKeyHex
        forget(id)
        if (pub != null) scope.launch { synchronized(holdLock) { dropHeldFrom(pub) } }
        ConnDiag.sys("Friend deleted (my side)")
    }


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


    fun isPending(cmId: String): Boolean = currentId(cmId) in pending


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


    fun sendCover(cmId: String) {
        val peer = contacts[currentId(cmId)] ?: return
        if (channel == null) return
        val junk = ByteArray((8..400).random()).also { java.security.SecureRandom().nextBytes(it) }
        scope.launch {
            runCatching { sendSecure(peer, FrameType.COVER, junk) }
                .onFailure { org.cmchat.app.diag.Diag.e("cover", "send failed", it) }
        }
    }


    fun contactIds(): List<String> = contacts.keys.toList()


    fun sendText(cmId: String, text: String, timer: SelfTimer) {

        if (org.cmchat.app.chat.EngineLog.looksLikeLog(text)) return

        val effective = if (timer != SelfTimer.OFF) timer
            else org.cmchat.app.settings.AppSettings.generalTimer.value
        val (chatCmId, msg) = inChat(cmId) { id -> id to ChatStore.addMine(id, text, effective) }

        org.cmchat.app.buzz.BuzzPolicy.onMessagedContact(chatCmId)
        if (channel == null || !contacts.containsKey(chatCmId)) return
        val payload = Messages.json.encodeToString(TextPayload.serializer(),
            TextPayload(msg.id, text, effective.label)).toByteArray()
        outbox.enqueue(Outbox.Item(
            peer = chatCmId, label = "message",
            deliver = { sendSecureTo(chatCmId, FrameType.MSG, payload) },

            stillWanted = { ChatStore.thread(currentId(chatCmId)).messages.any { it.id == msg.id } },
            onDelivered = { ChatStore.setState(currentId(chatCmId), msg.id, MsgState.SENT) },
        ))
    }

    enum class FileResult { QUEUED, TOO_BIG, EMPTY, NOT_READY }


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


    fun sendErase(cmId: String) {
        val chatCmId = inChat(cmId) { id -> ChatStore.erase(id); id }
        if (channel == null || !contacts.containsKey(chatCmId)) return
        outbox.enqueue(Outbox.Item(peer = chatCmId, label = "erase", replaceKey = "erase",
            deliver = { sendSecureTo(chatCmId, FrameType.ERASE_CHAT, ByteArray(0)) }))
    }


    fun tripDecoy(): Int {

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


        ConnDiag.sys("Decoy: burn signal sent to ${peers.size} friend(s) — it wipes our chat on " +
            "their phone when it reaches them (only while they're online)")
        peers.forEach { id ->
            outbox.enqueue(Outbox.Item(peer = id, label = "burn signal", replaceKey = "decoy", keepOnClose = true,
                deliver = { sendSecureTo(id, FrameType.DECOY_ALERT, ByteArray(0)) },
                onDelivered = { ConnDiag.out("burn signal delivered — a friend's copy of the chat is wiped") }))
        }
        return peers.size
    }


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


    fun sendAddressUpdate(newCmId: String) {
        if (channel == null) return
        myCmId = newCmId
        val friends = contacts.keys.filter { it !in pending }
        ConnDiag.sys("Telling ${friends.size} friend(s) my new address (re-sent until each one confirms)")
        resendAddress()
    }


    private fun resendAddress() {
        if (channel == null) return
        val mine = myCmId ?: return
        for (id in contacts.keys.toList()) {
            if (id in pending || addressConfirmed[id] == mine) continue

            if (outbox.has(id) { it.replaceKey == "addr" && it.tag == mine }) continue
            outbox.enqueue(Outbox.Item(peer = id, label = "address update", replaceKey = "addr", keepOnClose = true,
                tag = mine,
                deliver = { sendSecureTo(id, FrameType.ADDR_UPDATE, mine.toByteArray()) },
                stillWanted = { myCmId == mine && addressConfirmed[currentId(id)] != mine },
                onDelivered = { addressReached(id, mine) }))
        }
    }


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
        pending.remove(req.cmId)
        _incomingKnocks.value = _incomingKnocks.value.filterNot { it.cmId == req.cmId }
        onContactAccepted?.invoke(req)
        queueAccept(req.cmId)
    }


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

                nameConfirmed[currentId(cmId)] = payloadName
                onNameConfirmed?.invoke(currentId(cmId), payloadName)
            }))
    }

    fun declineKnock(req: KnockRequest) {
        ConnDiag.sys("Add friend: knock declined (ignored from them for 1 h)")
        declinedAt[req.cmId] = System.currentTimeMillis()
        _incomingKnocks.value = _incomingKnocks.value.filterNot { it.cmId == req.cmId }
    }




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


    fun closeVault() {
        vaultWanted = false
        vaultOpen = false
    }


    internal fun vaultIsOpen(): Boolean = vaultOpen


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
        val id = contactIdFor(from) ?: return
        val peer = contacts[id] ?: return
        confirmIfPending(id)
        dispatchFromContact(id, peer, rec.type, rec.body, replay = rec)
    }




    private fun handleIncoming(socket: Socket) {



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

                    val peer = contacts[r.cmId] ?: return
                    ConnDiag.inc("forward-secret frame opened from ${Redact.onionShort(peer.onion)}")


                    confirmIfPending(r.cmId)
                    outbox.kick(r.cmId); outbox.kick(r.cmId + FILE_LANE)


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

                    ConnDiag.inc("${r.reason} → dropped")
                    org.cmchat.app.diag.Diag.droppedFrame()
                }
            }
        } catch (_: Exception) {
            ConnDiag.inc("incoming error → connection dropped")
            org.cmchat.app.diag.Diag.droppedFrame()
        }
    }


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


    private fun safeMime(m: String): String =
        m.takeIf { it.length <= 80 && Regex("^[a-z]+/[a-z0-9.+-]+$").matches(it) } ?: "application/octet-stream"

    private fun confirmIfPending(cmId: String) {
        if (pending.remove(cmId)) {
            ConnDiag.sys("Add friend: they accepted — friend confirmed")
            onFriendConfirmed?.invoke(cmId)
        }
    }


    private fun markSeen(fromCmId: String, now: Long = System.currentTimeMillis()) {
        val id = inChat(fromCmId) { id -> ChatStore.touchPeer(id, now); id }
        val last = seenPersistedAt[id]
        if (last == null || now - last >= SEEN_PERSIST_EVERY_MS) {
            seenPersistedAt[id] = now
            onPeerSeen?.invoke(id, now)
        }
    }


    private fun contactIdFor(pubHex: String): String? =
        contacts.entries.firstOrNull { it.value.identityPubKeyHex.equals(pubHex, ignoreCase = true) }?.key


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


    private fun hold(fromCmId: String, peer: CmIdData, type: FrameType, body: ByteArray): Ack {
        val now = System.currentTimeMillis()

        var msgKey: String? = null
        when (type) {
            FrameType.MSG -> {
                val t = decodeText(body) ?: return Ack.REJECTED
                val key = "${peer.identityPubKeyHex.lowercase()}:${t.id}"
                if (key in deliveredIds || key in heldIds) return Ack.OK
                msgKey = key
            }
            FrameType.TEAM_CLOCK -> if (teamClock(body) == null) return Ack.REJECTED
            FrameType.NICKNAME -> if (nicknameFrom(body) == null) return Ack.REJECTED
            FrameType.ADDR_UPDATE -> if (addressFrom(peer, body) == null) return Ack.REJECTED
            FrameType.ERASE_CHAT -> {

                val gone = dropHeldFrom(peer.identityPubKeyHex)
                inChat(fromCmId) { ChatStore.erase(it) }
                ConnDiag.inc("erase while locked → chat erased (+$gone held item(s) shredded)")
                return Ack.OK
            }
            FrameType.KNOCK_ACCEPT, FrameType.TERMINATE, FrameType.DECOY_ALERT -> {}
            else -> return Ack.REJECTED
        }
        if (type == FrameType.DECOY_ALERT) {


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


                if (!org.cmchat.app.settings.AppSettings.invisibleMode.value) notify(Notice.MESSAGE)
            }

            FrameType.DECOY_ALERT -> notify(Notice.MESSAGE)
            else -> {}
        }
        return Ack.OK
    }


    private fun dropHeldFrom(pubHex: String): Int {
        val h = held ?: return 0
        val pub = myPubHex ?: return 0
        val sec = mySecHex ?: return 0
        return h.removeFrom(pubHex, pub, sec)
    }


    private fun onKnock(r: SecureWire.Received.Knock) {
        val kp = decodeKnock(r.body)
        val knocker = kp?.let { CmId.decode(it.cmId) }
        val nonce = kp?.nonce?.let { fromHex(it) }?.takeIf { it.size == SecureChannel.CHALLENGE }
        if (kp == null || knocker == null || nonce == null) {
            ConnDiag.inc("KNOCK ignored (malformed)"); return
        }
        val ack = try {
            synchronized(holdLock) { takeKnock(kp, knocker, r.body) }
        } catch (_: Exception) {
            Ack.RETRY
        }
        ConnDiag.inc(if (r.reply(nonce, knocker.identityPubKeyHex, ack)) "knock receipt sent: ${ack.name}"
            else "knock receipt not sent (connection gone)")
    }


    private fun takeKnock(kp: KnockPayload, knocker: CmIdData, raw: ByteArray): Ack {
        if (kp.cmId == myCmId || knocker.identityPubKeyHex.equals(myPubHex, ignoreCase = true)) {
            ConnDiag.inc("KNOCK ignored (my own ID)"); return Ack.REJECTED
        }
        if (kp.withdraw) {


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





        val friendId = contactIdFor(knocker.identityPubKeyHex)
        if (friendId != null && friendId !in pending) {
            if (!knockRate.allow("knock")) { ConnDiag.inc("KNOCK ignored (rate limit)"); return Ack.RETRY }
            ConnDiag.inc("KNOCK from an existing friend → acceptance re-sent")





            queueAccept(friendId, via = kp.cmId.takeIf { it != friendId })
            return Ack.OK
        }
        val cur = _incomingKnocks.value
        if (cur.any { it.cmId == kp.cmId }) { ConnDiag.inc("KNOCK ignored (duplicate)"); return Ack.OK }
        if (cur.size >= MAX_PENDING_KNOCKS) { ConnDiag.inc("KNOCK ignored (pending cap)"); return Ack.RETRY }
        if (!knockRate.allow("knock")) { ConnDiag.inc("KNOCK ignored (rate limit)"); return Ack.RETRY }
        if (!vaultOpen) {

            val h = held; val pub = myPubHex
            if (h == null || pub == null || !h.put(HeldInbox.Record(FrameType.KNOCK, null, raw, now,
                    closed = buzzOnlyMode), pub)) {
                ConnDiag.inc("couldn't hold the knock → they'll retry"); return Ack.RETRY
            }
        }
        addKnockCard(kp, knocker, notify = true)
        return Ack.OK
    }


    private fun addKnockCard(kp: KnockPayload, knocker: CmIdData, notify: Boolean) {
        if (contactIdFor(knocker.identityPubKeyHex)?.let { it !in pending } == true) return
        val cur = _incomingKnocks.value
        if (cur.any { it.cmId == kp.cmId } || cur.size >= MAX_PENDING_KNOCKS) return
        ConnDiag.inc("KNOCK received (waiting for you to accept)")
        _incomingKnocks.value = cur + KnockRequest(cleanName(kp.displayName) ?: "", kp.cmId)

        if (notify) notify(Notice.FRIEND_REQUEST)
    }


    private fun dispatchFromContact(
        fromCmId: String, peer: CmIdData, type: FrameType, body: ByteArray, replay: HeldInbox.Record?,
    ): Ack {
        if (replay != null) ConnDiag.inc("replayed $type")
        when (type) {
            FrameType.MSG -> {
                val t = decodeText(body) ?: return Ack.REJECTED
                val key = "${peer.identityPubKeyHex.lowercase()}:${t.id}"
                if (deliveredIds.containsKey(key)) return Ack.OK
                deliveredIds[key] = true


                if (org.cmchat.app.chat.EngineLog.looksLikeLog(t.text)) {
                    ConnDiag.inc("message was an engine log → not shown in the chat")
                    inChat(fromCmId) { id -> ChatStore.addSystemLine(id, org.cmchat.app.chat.EngineLog.HIDDEN_NOTICE) }
                    return Ack.OK
                }




                val invisible = org.cmchat.app.settings.AppSettings.invisibleMode.value
                if (invisible) ConnDiag.inc("held (Invisible): message kept as missed")
                val chatCmId = inChat(fromCmId) { id ->
                    ChatStore.addTheirs(id, t.id, t.text, SelfTimer.fromLabel(t.selfTimer), missed = invisible,
                        at = replay?.atMs, closedMiss = replay?.closed == true); id
                }



                if (replay == null && !invisible && !chatOnScreen(chatCmId)) notify(Notice.MESSAGE)
            }
            FrameType.DECOY_ALERT -> onDecoyAlert(fromCmId, peer, replay)
            FrameType.TEAM_CLOCK -> {
                val (v, at) = teamClock(body) ?: return Ack.REJECTED
                val id = currentId(fromCmId)

                if (at <= (teamClockAt[id] ?: Long.MIN_VALUE)) { ConnDiag.inc("older Team Clock ignored (newest wins)"); return Ack.OK }
                teamClockAt[id] = at
                val value = v.ifEmpty { null }
                val chatCmId = inChat(fromCmId) { cid ->
                    ChatStore.setTeamHour(cid, value, names[cid]?.ifBlank { null } ?: ""); cid
                }
                onTeamClockChanged?.invoke(chatCmId, value, at)
            }
            FrameType.ERASE_CHAT -> inChat(fromCmId) { ChatStore.erase(it) }
            FrameType.KNOCK_ACCEPT -> followAcceptance(fromCmId, peer, body)
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


    private fun followAcceptance(fromCmId: String, peer: CmIdData, body: ByteArray) {
        val kp = decodeKnock(body) ?: return
        if (kp.cmId != currentId(fromCmId) && addressFrom(peer, kp.cmId.toByteArray()) != null) {
            onAddressUpdate(currentId(fromCmId), peer, kp.cmId.toByteArray())
        }


        val mine = myPubHex
        kp.yours.takeIf { y -> mine != null && CmId.decode(y)?.identityPubKeyHex.equals(mine, ignoreCase = true) }
            ?.let { addressReached(currentId(fromCmId), it) }
        resendAddress()

        cleanName(kp.displayName)?.let { onFriendName?.invoke(currentId(fromCmId), it) }
    }


    private fun friendTerminated(fromCmId: String) {
        val id = currentId(fromCmId)
        if (!contacts.containsKey(id)) return
        ConnDiag.inc("friend removed you (terminate) → removed them too")
        forget(id)
        onFriendTerminated?.invoke(id)
    }


    private fun teamClock(body: ByteArray): Pair<String, Long>? {
        if (body.size > 40) return null
        val s = String(body, Charsets.US_ASCII)
        val v = s.substringBefore('|', missingDelimiterValue = "\u0000")
        val at = s.substringAfter('|', "").toLongOrNull() ?: return null
        if (at <= 0 || !(v.isEmpty() || org.cmchat.app.chat.TeamClock.decode(v) != null)) return null
        return v to at
    }


    private fun nicknameFrom(body: ByteArray): String? {
        if (body.isEmpty() || body.size > 96) return null
        return cleanName(String(body, Charsets.UTF_8))
    }


    internal fun cleanName(raw: String): String? =
        raw.filter { !it.isISOControl() && Character.getType(it) != Character.FORMAT.toInt() }
            .trim().take(24).ifEmpty { null }


    private fun addressFrom(peer: CmIdData, body: ByteArray): CmIdData? {
        val newCmId = runCatching { String(body) }.getOrNull() ?: return null
        val decoded = CmId.decode(newCmId) ?: return null
        return decoded.takeIf { it.identityPubKeyHex.equals(peer.identityPubKeyHex, ignoreCase = true) }
    }


    private fun onAddressUpdate(oldCmId: String, peer: CmIdData, body: ByteArray) {
        val decoded = addressFrom(peer, body) ?: return
        val newCmId = String(body)
        if (newCmId == oldCmId) return
        synchronized(relinkLock) {



            contacts[newCmId] = decoded
            names[oldCmId]?.let { names[newCmId] = it }
            if (oldCmId in pending) pending.add(newCmId)
            addressConfirmed.remove(oldCmId)?.let { addressConfirmed[newCmId] = it }
            ChatStore.rekey(oldCmId, newCmId)
            relinked.remove(newCmId)
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


    private fun onDecoyAlert(fromCmId: String, peer: CmIdData, replay: HeldInbox.Record?) {
        dropHeldFrom(peer.identityPubKeyHex)
        val chatCmId = inChat(fromCmId) { id -> ChatStore.addDecoyNotice(id, at = replay?.atMs ?: System.currentTimeMillis()); id }
        ConnDiag.inc("decoy alert received")
        if (replay == null && !chatOnScreen(chatCmId)) notify(Notice.MESSAGE)
    }


    internal enum class Notice { MESSAGE, FRIEND_REQUEST, BUZZ }


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


    private fun chatOnScreen(chatCmId: String): Boolean =
        activeChatCmId == chatCmId && org.cmchat.app.LifecycleController.inForeground


    private fun onBuzz(fromCmId: String) {
        val chatCmId = currentId(fromCmId)
        if (!org.cmchat.app.buzz.BuzzPolicy.accept(chatCmId)) return


        org.cmchat.app.buzz.BuzzPolicy.requestShake(chatCmId)
        inChat(chatCmId) { id -> if (activeChatCmId != id) ChatStore.markBuzzed(id) }


        notify(Notice.BUZZ)
    }




    private fun sendSecureTo(cmId: String, type: FrameType, payload: ByteArray) {
        val peer = contacts[currentId(cmId)] ?: throw IOException("not a friend any more")
        sendSecure(peer, type, payload)
    }


    private fun sendSecure(peer: CmIdData, type: FrameType, payload: ByteArray) {
        val ch = channel ?: throw IOException("engine not ready")
        withConnection(peer) { s ->

            s.soTimeout = HANDSHAKE_READ_TIMEOUT_MS
            requireStored(SecureWire.send(
                ch, s.getInputStream(), s.getOutputStream(), peer.identityPubKeyHex, type, payload,
                onStage = { ConnDiag.out(it) },
                onVersionMismatch = { versionMismatch.value = true },
            ))
        }
    }


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


    private fun requireStored(ack: Ack) = when (ack) {
        Ack.OK, Ack.HAVE -> Unit
        Ack.RETRY -> throw SecureWire.HandshakeFailed("their phone asked to retry later")
        Ack.REJECTED -> throw Outbox.GiveUp("their phone refused it")
    }


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


            val why = if (e is SecureWire.HandshakeFailed || e is Outbox.GiveUp) e.message
                else Transport.failureReason(e)
            ConnDiag.out("FAILED: $why")
            throw e
        }
        ConnDiag.out("CONNECTED — delivered, their phone confirmed (${System.currentTimeMillis() - t0}ms)")
    }


    private fun torDial(peer: CmIdData): Socket {


        if (TorService.status.value !is TorStatus.Online) {
            ConnDiag.out("FAILED: Tor offline")
            throw IOException("Tor offline")
        }


        runCatching { Thread.sleep((30..260).random().toLong()) }
        return Transport.connectThroughTorRetry(
            TorService.socksPort(), peer.onion.removeSuffix(".onion"), 80,
            onStage = { ConnDiag.out(it) },
        )
    }


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
