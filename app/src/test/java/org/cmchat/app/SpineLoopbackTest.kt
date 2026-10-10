package org.cmchat.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.cmchat.app.chat.ChatStore
import org.cmchat.app.chat.MsgState
import org.cmchat.app.chat.SelfTimer
import org.cmchat.app.crypto.CmId
import org.cmchat.app.crypto.CmIdData
import org.cmchat.app.crypto.CryptoManager
import org.cmchat.app.diag.ConnDiag
import org.cmchat.app.settings.AppSettings
import org.cmchat.app.tor.ServerController
import org.cmchat.app.transport.Ack
import org.cmchat.app.transport.FileOffer
import org.cmchat.app.transport.FileTransfer
import org.cmchat.app.transport.FrameType
import org.cmchat.app.transport.InnerCodec
import org.cmchat.app.transport.KnockPayload
import org.cmchat.app.transport.MessageService
import org.cmchat.app.transport.MessageService.KnockResult
import org.cmchat.app.transport.Messages
import org.cmchat.app.transport.ReplayGuard
import org.cmchat.app.transport.SecureChannel
import org.cmchat.app.transport.SecureWire
import org.cmchat.app.transport.TextPayload
import org.cmchat.app.transport.Transport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The add-friend → connect → send → receive SPINE, end to end, over real TCP
 * sockets on 127.0.0.1.
 *
 * "Alice" is the REAL [MessageService] — the exact object the app runs (knock,
 * pending friend, accept, silent outbox, incoming dispatch into [ChatStore]),
 * reached through the same [ServerController.onIncoming] hook the onion
 * listener calls. The other phone runs the same [SecureChannel] / [SecureWire]
 * code with its own keys. Only the Tor dial ([MessageService.dialer]) is
 * swapped for a direct local connect: everything after "a socket to the
 * friend's onion is open" is production code.
 *
 * Not covered here (needs two real phones): Tor itself — bootstrap, publishing
 * the onion, and circuits.
 */
class SpineLoopbackTest {

    private val crypto = CryptoManager(LazySodiumJava(SodiumJava()))
    private val loop: InetAddress = InetAddress.getByName("127.0.0.1")

    /** A well-formed v3 onion name (56 base32 letters). */
    private fun onionOf(c: Char) = c.toString().repeat(56) + ".onion"

    /** onion -> local port of whoever "publishes" it right now. */
    private val published = ConcurrentHashMap<String, Int>()
    private val failedDials = AtomicInteger()

    // ---- Alice = the real app object ---------------------------------------
    private lateinit var aPub: String
    private lateinit var aSec: String
    private lateinit var aliceId: String
    private lateinit var aliceServer: ServerSocket
    private lateinit var realDialer: (CmIdData) -> Socket
    private val confirmed = CopyOnWriteArrayList<String>()
    private val accepted = CopyOnWriteArrayList<MessageService.KnockRequest>()
    private val phones = CopyOnWriteArrayList<Phone>()

    /** The OTHER phone: same wire code, its own identity and "onion". */
    private inner class Phone(val name: String, letter: Char) {
        val pub: String
        val sec: String
        init { val (p, s) = crypto.newIdentityKeypair(); pub = p; sec = s; phones += this }
        @Volatile var onion = onionOf(letter)
        val cmId: String get() = CmId.encode(onion, pub)
        val ch = SecureChannel(crypto, pub, sec, InnerCodec(), ReplayGuard())
        /** Who this phone accepts frames from: cmId -> identity key. */
        val friends = ConcurrentHashMap<String, String>()
        val inbox = LinkedBlockingQueue<SecureWire.Received>()
        /** The receipt this phone answers with; null = it stores nothing, no receipt. */
        @Volatile var receipt: Ack? = Ack.OK
        /** Files this phone received whole: (offer, bytes). */
        val files = LinkedBlockingQueue<Pair<FileOffer, ByteArray>>()
        /** Its final receipt for a file; null = it never confirms. */
        @Volatile var fileReceipt: Ack? = Ack.OK
        @Volatile private var server: ServerSocket? = null

        fun up() {
            val srv = ServerSocket(0, 50, loop)
            server = srv
            published[onion] = srv.localPort
            thread(isDaemon = true, name = "$name-listener") {
                while (!srv.isClosed) {
                    val s = runCatching { srv.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) {
                        s.use {
                            it.soTimeout = 5_000
                            val r = SecureWire.receive(ch, it.getInputStream(), it.getOutputStream(), friends)
                            if (r is SecureWire.Received.Message && r.type == FrameType.FILE_OFFER) {
                                takeFile(r, it); return@use
                            }
                            val ack = receipt
                            if (ack != null) when (r) {
                                is SecureWire.Received.Message -> r.reply(ack)
                                is SecureWire.Received.Knock -> {
                                    val kp = Messages.json.decodeFromString(KnockPayload.serializer(), String(r.body))
                                    r.reply(hex(kp.nonce), CmId.decode(kp.cmId)!!.identityPubKeyHex, ack)
                                }
                                else -> {}
                            }
                            inbox.put(r)
                        }
                    }
                }
            }
        }

        /** Like the app: accept the offer, read every piece, confirm only then. */
        private fun takeFile(r: SecureWire.Received.Message, s: Socket) {
            val offer = Messages.json.decodeFromString(FileOffer.serializer(), String(r.body))
            r.reply(Ack.OK)
            val pieces = SecureWire.readFileChunks(ch, s.getInputStream(), hex(offer.key), hex(offer.id), offer.size,
                offer.chunks, System.currentTimeMillis() + 30_000) ?: return
            files.put(offer to pieces.fold(ByteArray(0)) { a, b -> a + b })
            fileReceipt?.let { Transport.writeFrame(s.getOutputStream(), ch.fileReceipt(hex(offer.id), it, aPub)) }
        }

        /** Send Alice a file; [chunkKey] ≠ the offer's key = a tampered transfer. */
        fun sendFile(name: String, data: ByteArray, id: ByteArray = crypto.randomBytes(16),
                     key: ByteArray = crypto.randomBytes(32), chunkKey: ByteArray = key,
                     claimedSize: Long = data.size.toLong()): Ack = toAlice { s ->
            val offer = FileOffer(id.hexs(), name, claimedSize, "application/octet-stream", key.hexs(),
                FileTransfer.chunkCount(claimedSize))
            SecureWire.sendFile(ch, s.getInputStream(), s.getOutputStream(), aPub,
                Messages.json.encodeToString(FileOffer.serializer(), offer).toByteArray(), id, chunkKey,
                FileTransfer.split(data))
        }

        fun down() {
            published.remove(onion)
            runCatching { server?.close() }
            server = null
        }

        private fun <T> toAlice(block: (Socket) -> T): T = Socket().use { s ->
            s.connect(InetSocketAddress(loop, aliceServer.localPort), 2_000)
            s.soTimeout = 5_000
            block(s)
        }

        /** One forward-secret frame to Alice (the real app); her receipt. */
        fun send(type: FrameType, payload: ByteArray): Ack = toAlice { s ->
            SecureWire.send(ch, s.getInputStream(), s.getOutputStream(), aPub, type, payload)
        }

        fun text(id: String, text: String) = send(FrameType.MSG,
            Messages.json.encodeToString(TextPayload.serializer(), TextPayload(id, text, "off")).toByteArray())

        /** "Add friend" from this phone: an anonymous sealed knock to Alice
         * ([withdraw] = "I cancelled my request"). Returns her receipt. */
        fun knock(withdraw: Boolean = false): Ack {
            val nonce = crypto.randomBytes(16)
            val body = Messages.json.encodeToString(KnockPayload.serializer(),
                KnockPayload(name, cmId, withdraw, nonce = nonce.joinToString("") { "%02x".format(it) })).toByteArray()
            val sealed = ch.sealKnock(body, aPub)
            return toAlice { s -> SecureWire.sendKnock(ch, s.getInputStream(), s.getOutputStream(), sealed, nonce, aPub) }
        }

        /** The next knock this phone received, decoded. */
        fun nextKnock(ms: Long = 15_000): KnockPayload {
            val r = next(ms)
            if (r !is SecureWire.Received.Knock) throw AssertionError("$name expected a knock, got $r")
            return Messages.json.decodeFromString(KnockPayload.serializer(), String(r.body))
        }

        fun next(ms: Long = 15_000): SecureWire.Received =
            inbox.poll(ms, TimeUnit.MILLISECONDS) ?: throw AssertionError("$name received nothing\n${ConnDiag.dump()}")

        fun nextFrame(ms: Long = 15_000): SecureWire.Received.Message {
            val r = next(ms)
            if (r !is SecureWire.Received.Message) throw AssertionError("$name expected a frame, got $r")
            return r
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    private fun ByteArray.hexs() = joinToString("") { "%02x".format(it) }

    private fun waitUntil(what: String, ms: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what\n${ConnDiag.dump()}")
            Thread.sleep(20)
        }
    }

    private fun textOf(m: SecureWire.Received.Message) =
        Messages.json.decodeFromString(TextPayload.serializer(), String(m.body)).text

    /** Alice's app after an unlock: configured, and (unless [locked]) the vault open. */
    private fun configureAlice(
        friends: List<String> = emptyList(),
        pending: List<String> = emptyList(),
        terminations: List<String> = emptyList(),
        confirmed: Map<String, String> = friends.associateWith { aliceId },
        locked: Boolean = false,
    ) {
        // As saved in her vault: her friends already have her address and nickname.
        MessageService.configure(crypto, "Alice", aPub, aSec, aliceId, friends,
            pendingCmIds = pending, pendingTerminations = terminations, confirmedAddresses = confirmed,
            confirmedNames = friends.associateWith { "Alice" })
        if (!locked) {
            MessageService.openVault()
            waitUntil("vault open") { MessageService.vaultIsOpen() }
        }
    }

    /** Alice swipes her app away: exactly what the app runs (vault locked, chats
     *  out of RAM, Buzz listener on). */
    private fun aliceClosesTheApp() = LifecycleController.closeToListener()

    private fun heldFiles() = heldDir.listFiles { f -> f.name.endsWith(".held") }?.size ?: 0

    private lateinit var heldDir: java.io.File

    @Before
    fun setUp() {
        MessageService.zeroKeys()
        ChatStore.clearAll()
        ConnDiag.clear()
        AppSettings.invisibleMode.value = false
        heldDir = java.nio.file.Files.createTempDirectory("held").toFile()
        MessageService.heldDir = heldDir
        val (p, s) = crypto.newIdentityKeypair(); aPub = p; aSec = s
        aliceId = CmId.encode(onionOf('a'), aPub)

        // Alice's listener, as ServerController.acceptLoop does it: one handler
        // per connection, a read timeout, and the socket closed afterwards.
        aliceServer = ServerSocket(0, 50, loop)
        val srv = aliceServer
        thread(isDaemon = true, name = "alice-listener") {
            while (!srv.isClosed) {
                val sock = runCatching { srv.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    try {
                        sock.soTimeout = 15_000
                        ServerController.onIncoming?.invoke(sock)
                    } finally {
                        runCatching { sock.close() }
                    }
                }
            }
        }

        realDialer = MessageService.dialer
        MessageService.dialer = { peer ->
            val port = published[peer.onion]
            if (port == null) {
                failedDials.incrementAndGet()
                throw ConnectException("unreachable")
            }
            Socket().apply { connect(InetSocketAddress(loop, port), 2_000) }
        }
        MessageService.onFriendConfirmed = { confirmed += it }
        MessageService.onContactAccepted = { accepted += it }
        MessageService.onFriendTerminated = { terminatedBy += it }
        MessageService.onTerminationDelivered = { terminationsDone += it }
        MessageService.onPeerSeen = { id, at -> seenEvents += id to at }
    }

    private val terminatedBy = CopyOnWriteArrayList<String>()
    private val terminationsDone = CopyOnWriteArrayList<String>()
    private val seenEvents = CopyOnWriteArrayList<Pair<String, Long>>()

    @After
    fun tearDown() {
        MessageService.zeroKeys()
        MessageService.dialer = realDialer
        MessageService.onFriendConfirmed = null
        MessageService.onContactAccepted = null
        MessageService.onFriendTerminated = null
        MessageService.onTerminationDelivered = null
        MessageService.onPeerSeen = null
        MessageService.onAddressConfirmed = null
        MessageService.onContactAddressUpdated = null
        MessageService.onTeamClockChanged = null
        ServerController.onIncoming = null
        runCatching { aliceServer.close() }
        phones.forEach { it.down() }
        ChatStore.clearAll()
        AppSettings.invisibleMode.value = false
        heldDir.deleteRecursively()
        MessageService.heldDir = null
    }

    /** THE SPINE: Add friend → they accept → messages both ways. */
    @Test
    fun add_friend_then_chat_both_ways() {
        val bob = Phone("Bob", 'b').also { it.up() }
        configureAlice()

        // 1) Alice adds Bob (scanned / pasted his CMC-ID, tapped "Add friend").
        assertEquals(KnockResult.QUEUED, MessageService.sendKnock(bob.cmId))
        assertTrue("Bob is listed as pending on Alice's side", MessageService.isPending(bob.cmId))

        // 2) Bob's phone gets the knock and can read who it is from.
        val k = bob.next() as SecureWire.Received.Knock
        val kp = Messages.json.decodeFromString(KnockPayload.serializer(), String(k.body))
        assertEquals("Alice", kp.displayName)
        assertEquals(aliceId, kp.cmId)

        // 3) Bob taps Accept: he adds Alice and sends KNOCK_ACCEPT (forward-secret),
        //    saying which address of hers he stored.
        bob.friends[aliceId] = aPub
        bob.send(FrameType.KNOCK_ACCEPT, Messages.json.encodeToString(KnockPayload.serializer(),
            KnockPayload("Bob", bob.cmId, yours = aliceId)).toByteArray())
        waitUntil("Alice sees Bob's acceptance") { bob.cmId in confirmed }
        assertFalse(MessageService.isPending(bob.cmId))

        // 4) Alice → Bob.
        MessageService.sendText(bob.cmId, "hello bob", SelfTimer.OFF)
        val m = bob.nextFrame()
        assertEquals(aliceId, m.cmId)
        assertEquals(FrameType.MSG, m.type)
        assertEquals("hello bob", textOf(m))
        waitUntil("Alice's message marked sent") {
            ChatStore.thread(bob.cmId).messages.singleOrNull()?.state == MsgState.SENT
        }

        // 5) Bob → Alice.
        bob.text("b-1", "hi alice")
        waitUntil("Bob's message in Alice's chat") {
            ChatStore.thread(bob.cmId).messages.any { !it.mine && it.text == "hi alice" }
        }

        // 6) Every step is in the Connection log — and nothing secret is.
        val log = ConnDiag.dump()
        listOf(
            "knock queued", "knock sent", "knock delivered", "they accepted",
            "prekey requested", "one-time prekey verified", "one-time prekey served",
            "dispatched KNOCK_ACCEPT", "dispatched MSG", "CONNECTED",
        ).forEach { assertTrue("Connection log is missing '$it'\n$log", log.contains(it)) }
        listOf(
            bob.onion.removeSuffix(".onion"), onionOf('a').removeSuffix(".onion"),
            aPub, aSec, bob.pub, bob.sec, bob.cmId, aliceId, "hello bob", "hi alice",
        ).forEach { assertFalse("Connection log leaks a key / address / message\n$log", log.contains(it)) }
    }

    /** First contact gets through even though every login starts Invisible. */
    @Test
    fun a_knock_punches_through_invisible_and_accepting_reaches_them() {
        val bob = Phone("Bob", 'b').also { it.up() }
        configureAlice()
        AppSettings.invisibleMode.value = true
        bob.friends[aliceId] = aPub   // Bob knocked, so Alice is pending on his side

        bob.knock()
        waitUntil("knock card on Alice's Friends screen") {
            MessageService.incomingKnocks.value.any { it.cmId == bob.cmId }
        }
        bob.knock()                   // knocking again is merged, never a second card
        waitUntil("duplicate knock handled") { ConnDiag.dump().contains("duplicate") }
        val req = MessageService.incomingKnocks.value.single()
        assertEquals("Bob", req.displayName)

        MessageService.acceptKnock(req)
        assertEquals(listOf(req), accepted)
        assertTrue(MessageService.incomingKnocks.value.isEmpty())
        val acc = bob.nextFrame()
        assertEquals(FrameType.KNOCK_ACCEPT, acc.type)
        assertEquals(aliceId, acc.cmId)

        // Still Invisible: Bob's message is kept as "missed" (orange dot) and he
        // learns nothing; it shows once Alice goes Online.
        bob.text("b-1", "are you there?")
        waitUntil("message held") { ChatStore.thread(bob.cmId).messages.isNotEmpty() }
        val t = ChatStore.thread(bob.cmId)
        assertTrue(t.unread)
        assertTrue(t.messages.single().missed)
    }

    /** My acceptance never reached them (say my app closed first) and they knock
     *  again: no duplicate card, the acceptance is simply sent again. */
    @Test
    fun a_knock_from_an_existing_friend_just_resends_the_acceptance() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub    // Bob still has Alice as pending on his side
        configureAlice(friends = listOf(bob.cmId))
        bob.knock()
        val acc = bob.nextFrame()
        assertEquals(FrameType.KNOCK_ACCEPT, acc.type)
        assertEquals(aliceId, acc.cmId)
        assertTrue("no duplicate knock card", MessageService.incomingKnocks.value.isEmpty())
    }

    /** B2 "stuck on Pending": my acceptance never reached him, and meanwhile his
     *  address changed. His new knock (from the new address) gets the acceptance
     *  THERE — no duplicate card, and no move on an unproven hint. */
    @Test
    fun a_friend_whose_address_changed_before_my_acceptance_arrived_still_gets_it() {
        val bob = Phone("Bob", 'b')
        bob.friends[aliceId] = aPub                 // Alice is still pending on his side
        val oldId = bob.cmId
        configureAlice(friends = listOf(oldId))     // she accepted him at his OLD address
        bob.onion = onionOf('f'); bob.up()          // the old address is dead
        assertEquals(Ack.OK, bob.knock())
        val acc = bob.nextFrame()
        assertEquals("the acceptance reached him at the new address", FrameType.KNOCK_ACCEPT, acc.type)
        assertTrue("no duplicate request card", MessageService.incomingKnocks.value.isEmpty())
        assertEquals("not moved on an unproven hint", oldId, MessageService.currentId(oldId))
    }

    /** His acceptance says which address of mine he stored: if it's an older
     *  one (mine changed while he hadn't accepted yet), my current one follows. */
    @Test
    fun if_they_accepted_my_old_address_my_current_one_follows() {
        val bob = Phone("Bob", 'b').also { it.up() }
        configureAlice()
        assertEquals(KnockResult.QUEUED, MessageService.sendKnock(bob.cmId))
        bob.nextKnock()
        val newMine = CmId.encode(onionOf('g'), aPub)
        MessageService.sendAddressUpdate(newMine)          // mine changed; he's still pending
        bob.friends[aliceId] = aPub
        bob.send(FrameType.KNOCK_ACCEPT, Messages.json.encodeToString(KnockPayload.serializer(),
            KnockPayload("Bob", bob.cmId, yours = aliceId)).toByteArray())   // he has the OLD one
        val up = bob.nextFrame()
        assertEquals(FrameType.ADDR_UPDATE, up.type)
        assertEquals(newMine, String(up.body))
    }

    @Test
    fun a_declined_knock_is_ignored_for_an_hour() {
        val carol = Phone("Carol", 'c').also { it.up() }
        configureAlice()
        carol.knock()
        waitUntil("knock card") { MessageService.incomingKnocks.value.isNotEmpty() }
        MessageService.declineKnock(MessageService.incomingKnocks.value.single())
        carol.knock()
        waitUntil("second knock ignored") { ConnDiag.dump().contains("declined recently") }
        assertTrue(MessageService.incomingKnocks.value.isEmpty())
    }

    @Test
    fun add_friend_refuses_garbage_myself_and_mashing() {
        val bob = Phone("Bob", 'b')
        assertEquals(KnockResult.NOT_READY, MessageService.sendKnock(bob.cmId))  // engine not set up
        configureAlice()
        assertEquals(KnockResult.INVALID, MessageService.sendKnock("not an id"))
        assertEquals(KnockResult.SELF, MessageService.sendKnock(aliceId))
        assertEquals(KnockResult.QUEUED, MessageService.sendKnock(bob.cmId))
        assertEquals(KnockResult.TOO_SOON, MessageService.sendKnock(bob.cmId))
    }

    /** No Retry button and no "offline" mark: it just waits and lands later. */
    @Test
    fun an_unreachable_friend_gets_the_message_silently_when_back() {
        val bob = Phone("Bob", 'b')   // not listening: unreachable
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))

        MessageService.sendText(bob.cmId, "you there?", SelfTimer.OFF)
        waitUntil("first attempt failed") { failedDials.get() >= 1 }
        Thread.sleep(200)
        val msg = ChatStore.thread(bob.cmId).messages.single()
        assertEquals("the chat shows nothing about online/offline", MsgState.SENDING, msg.state)

        // Bob comes back; a frame from him makes Alice retry at once (well before
        // the 15 s backoff would have).
        bob.up()
        bob.text("b-1", "back now")
        assertEquals("you there?", textOf(bob.nextFrame(ms = 5_000)))
        waitUntil("marked sent") { ChatStore.thread(bob.cmId).messages.first { it.mine }.state == MsgState.SENT }
    }

    @Test
    fun a_message_erased_before_delivery_is_never_sent() {
        val bob = Phone("Bob", 'b')
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))

        MessageService.sendText(bob.cmId, "oops", SelfTimer.OFF)
        waitUntil("first attempt failed") { failedDials.get() >= 1 }
        MessageService.sendErase(bob.cmId)            // the normal chat Erase
        assertTrue(ChatStore.thread(bob.cmId).messages.isEmpty())

        bob.up()
        bob.text("b-1", "hi")
        assertEquals("only the erase goes out", FrameType.ERASE_CHAT, bob.nextFrame(ms = 5_000).type)
        assertNull("the erased message is never sent", bob.inbox.poll(1_500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun decoy_wipes_my_side_at_once_and_alerts_the_friend() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        bob.text("b-1", "secret plans")
        waitUntil("message in") { ChatStore.thread(bob.cmId).messages.isNotEmpty() }

        MessageService.tripDecoy()
        assertTrue("my side is wiped at once", ChatStore.threads.value.values.all { it.messages.isEmpty() })
        assertEquals(FrameType.DECOY_ALERT, bob.nextFrame().type)
    }

    /** F1: a friend's decoy = their BURN signal: our chat is wiped here at once;
     *  only the notice remains, and it goes when I leave the chat. */
    @Test
    fun a_friends_burn_signal_wipes_our_chat_here_at_once() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        bob.text("b-1", "earlier message")
        waitUntil("message in") { ChatStore.thread(bob.cmId).messages.size == 1 }

        assertEquals(Ack.OK, bob.send(FrameType.DECOY_ALERT, ByteArray(0)))
        val t = ChatStore.thread(bob.cmId)
        assertEquals("burned: only the notice is left", listOf(ChatStore.DECOY_NOTICE), t.messages.map { it.text })
        ChatStore.leaveChat(bob.cmId)                 // leave the chat…
        assertTrue("…and the notice is gone too", ChatStore.thread(bob.cmId).messages.isEmpty())
    }

    /** F1: the burn signal goes to CONFIRMED friends only, and the log says how many. */
    @Test
    fun the_burn_signal_goes_to_confirmed_friends_only_and_is_logged_honestly() {
        val bob = Phone("Bob", 'b').also { it.up() }
        val carol = Phone("Carol", 'c').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId, carol.cmId), pending = listOf(carol.cmId))
        carol.nextKnock()                              // (the re-knock to pending Carol)
        assertEquals(1, MessageService.tripDecoy())
        assertEquals(FrameType.DECOY_ALERT, bob.nextFrame().type)
        assertNull("pending Carol gets nothing", carol.inbox.poll(800, TimeUnit.MILLISECONDS))
        assertTrue(ConnDiag.dump().contains("burn signal sent to 1 friend(s)"))
        waitUntil("delivery logged") { ConnDiag.dump().contains("burn signal delivered") }

        MessageService.zeroKeys(); ConnDiag.clear()
        configureAlice()                               // nobody confirmed
        assertEquals(0, MessageService.tripDecoy())
        assertTrue(ConnDiag.dump().contains("no confirmed friends to signal"))
    }

    // ---- I. nicknames ------------------------------------------------------------

    @Test
    fun their_own_nickname_comes_with_the_acceptance_and_with_a_change() {
        val names = CopyOnWriteArrayList<Pair<String, String>>()
        MessageService.onFriendName = { id, n -> names += id to n }
        try {
            val bob = Phone("Bob", 'b').also { it.up() }
            configureAlice()
            MessageService.sendKnock(bob.cmId); bob.nextKnock()
            bob.friends[aliceId] = aPub
            bob.send(FrameType.KNOCK_ACCEPT, Messages.json.encodeToString(KnockPayload.serializer(),
                KnockPayload("Robert", bob.cmId, yours = aliceId)).toByteArray())
            assertEquals(listOf(bob.cmId to "Robert"), names)
            // He renames himself later: the NICKNAME frame (control characters stripped).
            assertEquals(Ack.OK, bob.send(FrameType.NICKNAME, "Bobby\u0007".toByteArray()))
            assertEquals(bob.cmId to "Bobby", names.last())
        } finally {
            MessageService.onFriendName = null
        }
    }

    @Test
    fun my_new_nickname_goes_to_every_friend_until_each_one_has_it() {
        val got = CopyOnWriteArrayList<Pair<String, String>>()
        MessageService.onNameConfirmed = { id, n -> got += id to n }
        try {
            val bob = Phone("Bob", 'b').also { it.up() }
            bob.friends[aliceId] = aPub
            MessageService.configure(crypto, "Alice", aPub, aSec, aliceId, listOf(bob.cmId),
                confirmedAddresses = mapOf(bob.cmId to aliceId), confirmedNames = mapOf(bob.cmId to "Alice"))
            assertNull("he already has it", bob.inbox.poll(600, TimeUnit.MILLISECONDS))
            MessageService.setMyName("Ally")
            val f = bob.nextFrame()
            assertEquals(FrameType.NICKNAME, f.type)
            assertEquals("Ally", String(f.body))
            waitUntil("confirmed") { got.lastOrNull() == (bob.cmId to "Ally") }
        } finally {
            MessageService.onNameConfirmed = null
        }
    }

    // ---- J. Team Clock -----------------------------------------------------------

    private fun clock(v: String, at: Long) = "$v|$at".toByteArray()

    @Test
    fun the_team_clock_is_newest_wins_and_survives_a_locked_phone() {
        val changes = CopyOnWriteArrayList<Triple<String, String?, Long>>()
        MessageService.onTeamClockChanged = { id, v, at -> changes += Triple(id, v, at) }
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        assertEquals(Ack.OK, bob.send(FrameType.TEAM_CLOCK, clock("UTC+02:00", 2_000)))
        assertEquals(Ack.OK, bob.send(FrameType.TEAM_CLOCK, clock("UTC+05:00", 1_000)))   // older: ignored
        assertEquals(listOf(Triple(bob.cmId, "UTC+02:00" as String?, 2_000L)), changes)
        assertEquals("UTC+02:00", ChatStore.thread(bob.cmId).teamHour)
        assertEquals("garbage refused", Ack.REJECTED, bob.send(FrameType.TEAM_CLOCK, "UTC+99:99|5".toByteArray()))
        // A change while I'm locked is held — and applied at unlock (even after a killed app).
        MessageService.closeVault()
        assertEquals(Ack.OK, bob.send(FrameType.TEAM_CLOCK, clock("", 3_000)))
        assertEquals(1, heldFiles())
        MessageService.zeroKeys(); changes.clear()
        MessageService.configure(crypto, "Alice", aPub, aSec, aliceId, listOf(bob.cmId),
            confirmedAddresses = mapOf(bob.cmId to aliceId), confirmedNames = mapOf(bob.cmId to "Alice"),
            teamClockTimes = mapOf(bob.cmId to 2_000L))
        MessageService.openVault()
        waitUntil("vault open") { MessageService.vaultIsOpen() }
        assertEquals(listOf(Triple(bob.cmId, null as String?, 3_000L)), changes)
    }

    @Test
    fun my_team_clock_change_is_re_sent_after_a_restart_until_it_reaches_them() {
        val synced = CopyOnWriteArrayList<Pair<String, Long>>()
        MessageService.onTeamClockSynced = { id, at -> synced += id to at }
        try {
            val bob = Phone("Bob", 'b').also { it.up() }
            bob.friends[aliceId] = aPub
            // The vault says: my change of 4 000 never reached Bob.
            MessageService.configure(crypto, "Alice", aPub, aSec, aliceId, listOf(bob.cmId),
                confirmedAddresses = mapOf(bob.cmId to aliceId), confirmedNames = mapOf(bob.cmId to "Alice"),
                unsyncedTeamClocks = mapOf(bob.cmId to ("UTC+03:00" to 4_000L)))
            val f = bob.nextFrame()
            assertEquals(FrameType.TEAM_CLOCK, f.type)
            assertEquals("UTC+03:00|4000", String(f.body))
            waitUntil("synced") { synced == listOf(bob.cmId to 4_000L) }
        } finally {
            MessageService.onTeamClockSynced = null
        }
    }

    /** A decoy rotates the onion: the friend's open chat must keep working. */
    @Test
    fun a_friend_who_moves_address_stays_reachable_from_an_open_chat() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        val oldId = bob.cmId
        bob.text("b-1", "before")
        waitUntil("message in") { ChatStore.thread(oldId).messages.size == 1 }

        // Bob gets a new onion (same identity key) and tells Alice.
        bob.down(); bob.onion = onionOf('d'); bob.up()
        val newId = bob.cmId
        bob.send(FrameType.ADDR_UPDATE, newId.toByteArray())
        waitUntil("relinked") { MessageService.currentId(oldId) == newId }
        assertEquals("the conversation moved with him", 1, ChatStore.thread(newId).messages.size)

        // A chat screen still open on the OLD id keeps reaching him.
        MessageService.sendText(oldId, "after", SelfTimer.OFF)
        assertEquals("after", textOf(bob.nextFrame()))
        waitUntil("marked sent") { ChatStore.thread(newId).messages.any { it.mine && it.state == MsgState.SENT } }

        // Re-configuring from a vault that still lists the old ID keeps the new one.
        configureAlice(friends = listOf(oldId))
        MessageService.sendText(oldId, "still here", SelfTimer.OFF)
        assertEquals("still here", textOf(bob.nextFrame()))
    }

    // ---- pending: re-knock, cancel ------------------------------------------

    /** A pending add whose knock (or their acceptance) was lost to a closed app
     *  is re-knocked by itself on the next start — no stuck "Pending". */
    @Test
    fun a_pending_friend_is_re_knocked_on_the_next_start() {
        val bob = Phone("Bob", 'b').also { it.up() }
        configureAlice(friends = listOf(bob.cmId), pending = listOf(bob.cmId))   // app restarted
        val k = bob.nextKnock()
        assertEquals(aliceId, k.cmId)
        assertFalse(k.withdraw)
        // Bob had already accepted: his acceptance resolves the pending.
        bob.friends[aliceId] = aPub
        bob.send(FrameType.KNOCK_ACCEPT, Messages.json.encodeToString(KnockPayload.serializer(),
            KnockPayload("Bob", bob.cmId, yours = aliceId)).toByteArray())
        waitUntil("pending resolved") { !MessageService.isPending(bob.cmId) && bob.cmId in confirmed }
        // No knock storm: configuring again right away doesn't re-knock.
        configureAlice(friends = listOf(bob.cmId))
        assertNull(bob.inbox.poll(800, TimeUnit.MILLISECONDS))
    }

    @Test
    fun cancelling_a_pending_add_forgets_them_and_withdraws_the_card() {
        val bob = Phone("Bob", 'b').also { it.up() }
        configureAlice()
        assertEquals(KnockResult.QUEUED, MessageService.sendKnock(bob.cmId))
        assertFalse(bob.nextKnock().withdraw)

        assertTrue(MessageService.cancelPending(bob.cmId))
        assertFalse(MessageService.isPending(bob.cmId))
        val w = bob.nextKnock()
        assertTrue("Bob's phone is told to drop the request card", w.withdraw)
        assertEquals(aliceId, w.cmId)
        // Alice no longer accepts frames from Bob (he's not on her list).
        bob.friends[aliceId] = aPub
        runCatching { bob.text("b-1", "hello?") }
        Thread.sleep(300)
        assertTrue(ChatStore.thread(bob.cmId).messages.isEmpty())
    }

    @Test
    fun a_withdrawn_knock_removes_the_request_card() {
        val carol = Phone("Carol", 'c').also { it.up() }
        configureAlice()
        carol.knock()
        waitUntil("card shown") { MessageService.incomingKnocks.value.any { it.cmId == carol.cmId } }
        carol.knock(withdraw = true)
        waitUntil("card withdrawn") { MessageService.incomingKnocks.value.isEmpty() }
    }

    // ---- delete / terminate --------------------------------------------------

    @Test
    fun terminate_reaches_their_phone_and_removes_them_here() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        bob.text("b-1", "hi")
        waitUntil("chat exists") { ChatStore.thread(bob.cmId).messages.isNotEmpty() }

        assertTrue(MessageService.terminateFriend(bob.cmId))
        assertTrue("gone from my side at once", ChatStore.threads.value[bob.cmId] == null)
        val t = bob.nextFrame()
        assertEquals(FrameType.TERMINATE, t.type)
        assertEquals(aliceId, t.cmId)
        waitUntil("delivery recorded") { bob.cmId in terminationsDone }
    }

    @Test
    fun a_terminate_kept_from_last_run_is_delivered_on_start() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(terminations = listOf(bob.cmId))   // saved in the vault last run
        assertEquals(FrameType.TERMINATE, bob.nextFrame().type)
        waitUntil("delivery recorded") { bob.cmId in terminationsDone }
    }

    @Test
    fun when_a_friend_terminates_me_they_are_removed_here_too() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        bob.text("b-1", "bye")
        waitUntil("chat exists") { ChatStore.thread(bob.cmId).messages.isNotEmpty() }
        bob.send(FrameType.TERMINATE, ByteArray(0))
        waitUntil("removed here") { bob.cmId in terminatedBy }
        assertTrue(ChatStore.threads.value[bob.cmId] == null)
        // Nothing more from him gets in.
        runCatching { bob.text("b-2", "still there?") }
        Thread.sleep(300)
        assertTrue(ChatStore.threads.value[bob.cmId]?.messages.isNullOrEmpty())
    }

    @Test
    fun delete_friend_is_my_side_only() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        MessageService.deleteFriend(bob.cmId)
        // Nothing is sent to Bob.
        assertNull(bob.inbox.poll(800, TimeUnit.MILLISECONDS))
        // And his frames no longer get in.
        runCatching { bob.text("b-1", "hey") }
        Thread.sleep(300)
        assertTrue(ChatStore.threads.value[bob.cmId]?.messages.isNullOrEmpty())
    }

    // ---- last seen, missed ----------------------------------------------------

    @Test
    fun last_seen_comes_from_anything_they_send_and_survives_an_erase() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        bob.send(FrameType.BUZZ, ByteArray(0))            // not a message — still "seen"
        waitUntil("seen") { ChatStore.thread(bob.cmId).peerLastSeen != null }
        waitUntil("persist requested once") { seenEvents.count { it.first == bob.cmId } == 1 }
        bob.text("b-1", "hi")
        waitUntil("message in") { ChatStore.thread(bob.cmId).messages.isNotEmpty() }
        assertEquals("persisted at most every 30 min", 1, seenEvents.count { it.first == bob.cmId })
        // Wiping the conversation doesn't wipe "last seen recently".
        MessageService.sendErase(bob.cmId)
        assertTrue(ChatStore.thread(bob.cmId).messages.isEmpty())
        assertTrue(ChatStore.thread(bob.cmId).peerLastSeen != null)
    }

    @Test
    fun missed_messages_clear_once_seen_online() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        AppSettings.invisibleMode.value = true
        bob.text("b-1", "you there?")
        waitUntil("held as missed") { ChatStore.thread(bob.cmId).messages.any { it.missed } }
        assertTrue(ChatStore.thread(bob.cmId).unread)
        // Go Online and open the chat:
        AppSettings.invisibleMode.value = false
        ChatStore.markSeen(bob.cmId)
        val t = ChatStore.thread(bob.cmId)
        assertFalse(t.unread)
        assertTrue(t.messages.none { it.missed })
        assertTrue(t.messages.single().seenAt != null)
    }

    // ---- A. delivery reliability: held, never dropped -----------------------------

    /** A1: the app was swiped away (Buzz listener on) — a message is HELD, sealed,
     *  and after the next unlock it shows as "Missed Message". */
    @Test
    fun a_message_that_arrives_while_my_app_is_closed_is_held_and_shows_as_missed() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        aliceClosesTheApp()

        assertEquals("her phone says it's stored", Ack.OK, bob.text("b-1", "while you were away"))
        assertTrue("not in RAM — the chats left with the app", ChatStore.thread(bob.cmId).messages.isEmpty())
        assertEquals("held on flash", 1, heldFiles())
        assertFalse("sealed: no plaintext on disk", heldDir.walk().filter { it.isFile }
            .any { String(it.readBytes(), Charsets.ISO_8859_1).contains("while you were away") })

        // Reopen + unlock (every start is Invisible):
        MessageService.buzzOnlyMode = false
        configureAlice(friends = listOf(bob.cmId))
        val m = ChatStore.thread(bob.cmId).messages.single()
        assertEquals("while you were away", m.text)
        assertTrue(m.missed && m.closedMiss)
        assertEquals("shredded once delivered", 0, heldFiles())
        // Online: still "Missed Message" (it came while the app was closed)…
        AppSettings.invisibleMode.value = false
        ChatStore.deliverMissed()
        assertTrue(ChatStore.thread(bob.cmId).messages.single().closedMiss)
        // …until it has been seen and the chat left.
        ChatStore.markSeen(bob.cmId)
        ChatStore.leaveChat(bob.cmId)
        assertFalse(ChatStore.thread(bob.cmId).messages.single().closedMiss)
    }

    /** A1: the sender NEVER sees "delivered" for a frame the other phone didn't store. */
    @Test
    fun a_frame_my_friend_did_not_store_is_never_counted_as_delivered() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))

        bob.receipt = null                       // his phone opens it but stores nothing
        MessageService.sendText(bob.cmId, "did you get this?", SelfTimer.OFF)
        bob.nextFrame()                          // it reached his socket…
        waitUntil("no receipt logged") { ConnDiag.dump().contains("no receipt") }
        assertEquals("…but without a receipt it is NOT delivered", MsgState.SENDING,
            ChatStore.thread(bob.cmId).messages.single().state)

        bob.receipt = Ack.RETRY                  // "full — try later"
        bob.text("b-1", "ping")                  // a frame from him makes Alice retry now
        bob.nextFrame()
        waitUntil("retry asked") { ConnDiag.dump().contains("asked to retry later") }
        assertEquals(MsgState.SENDING, ChatStore.thread(bob.cmId).messages.first { it.mine }.state)

        bob.receipt = Ack.OK
        bob.text("b-2", "ping again")
        assertEquals("did you get this?", textOf(bob.nextFrame()))
        waitUntil("delivered only now") { ChatStore.thread(bob.cmId).messages.first { it.mine }.state == MsgState.SENT }
    }

    /** A receipt that got lost makes the sender send it again: shown once. */
    @Test
    fun a_message_resent_after_a_lost_receipt_shows_only_once() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        assertEquals(Ack.OK, bob.text("b-1", "once"))
        assertEquals("the re-send is confirmed…", Ack.OK, bob.text("b-1", "once"))
        assertEquals("…but shown once", 1, ChatStore.thread(bob.cmId).messages.size)
        // The same while locked: held once.
        MessageService.closeVault()
        bob.text("b-2", "held once"); bob.text("b-2", "held once")
        assertEquals(1, heldFiles())
        configureAlice(friends = listOf(bob.cmId))
        assertEquals(listOf("once", "held once"), ChatStore.thread(bob.cmId).messages.map { it.text })
    }

    /** Held = on flash: the OS killing the app before the next unlock loses nothing. */
    @Test
    fun held_messages_survive_the_app_being_killed_before_the_next_unlock() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        aliceClosesTheApp()
        bob.text("b-1", "first"); bob.text("b-2", "second")
        assertEquals(2, heldFiles())

        MessageService.zeroKeys(); ChatStore.clearAll()        // RAM gone, flash stays
        configureAlice(friends = listOf(bob.cmId))             // a fresh start + unlock
        assertEquals("in order", listOf("first", "second"), ChatStore.thread(bob.cmId).messages.map { it.text })
        assertEquals(0, heldFiles())
    }

    /** A friend's new address while I'm locked works AT ONCE, and still reaches the
     *  vault after a killed app (its held copy is replayed). */
    @Test
    fun a_friends_new_address_while_locked_works_at_once_and_survives_a_killed_app() {
        val moved = CopyOnWriteArrayList<Pair<String, String>>()
        MessageService.onContactAddressUpdated = { o, n -> moved += o to n }
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        val oldId = bob.cmId
        MessageService.closeVault()                            // minimised + re-locked

        bob.down(); bob.onion = onionOf('d'); bob.up()
        val newId = bob.cmId
        assertEquals(Ack.OK, bob.send(FrameType.ADDR_UPDATE, newId.toByteArray()))
        assertEquals("relinked at once", newId, MessageService.currentId(oldId))
        assertEquals(listOf(oldId to newId), moved)
        MessageService.sendText(oldId, "reaches the new address", SelfTimer.OFF)
        assertEquals("reaches the new address", textOf(bob.nextFrame()))

        // Killed before the next unlock: the vault still has the old address.
        MessageService.zeroKeys(); moved.clear()
        configureAlice(friends = listOf(oldId))
        assertEquals("the held update is applied again", listOf(oldId to newId), moved)
        assertEquals(newId, MessageService.currentId(oldId))
    }

    /** A2: my address goes to each friend until THEIR phone confirms it — also
     *  after my app is closed, and again for every new address. */
    @Test
    fun my_address_is_resent_until_each_friend_confirms_it() {
        val confirmedBy = CopyOnWriteArrayList<Pair<String, String>>()
        MessageService.onAddressConfirmed = { f, mine -> confirmedBy += f to mine }
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        bob.receipt = null                                     // his phone doesn't confirm yet
        configureAlice(friends = listOf(bob.cmId), confirmed = emptyMap())
        val f = bob.nextFrame()
        assertEquals(FrameType.ADDR_UPDATE, f.type)
        assertEquals(aliceId, String(f.body))
        assertTrue(confirmedBy.isEmpty())

        aliceClosesTheApp()                                    // not dropped with the chat content
        bob.receipt = Ack.OK
        bob.send(FrameType.BUZZ, ByteArray(0))                 // he shows up → retried at once
        assertEquals(FrameType.ADDR_UPDATE, bob.nextFrame().type)
        waitUntil("confirmed") { confirmedBy == listOf(bob.cmId to aliceId) }

        // Confirmed (and saved): the next start doesn't send it again…
        configureAlice(friends = listOf(bob.cmId), confirmed = mapOf(bob.cmId to aliceId))
        assertNull(bob.inbox.poll(800, TimeUnit.MILLISECONDS))
        // …but a NEW address of mine goes out until he confirms that one.
        val newMine = CmId.encode(onionOf('e'), aPub)
        MessageService.sendAddressUpdate(newMine)
        assertEquals(newMine, String(bob.nextFrame().body))
        waitUntil("new one confirmed") { confirmedBy.lastOrNull() == (bob.cmId to newMine) }
    }

    /** B1 + A1: a friend request reaches me while my app is closed (Invisible),
     *  notifies, and survives the app being killed before I look. */
    @Test
    fun a_friend_request_while_my_app_is_closed_gets_through_and_survives_a_restart() {
        val notices = CopyOnWriteArrayList<MessageService.Notice>()
        val before = MessageService.notify
        MessageService.notify = { notices += it }
        try {
            configureAlice()
            aliceClosesTheApp()
            val carol = Phone("Carol", 'c').also { it.up() }
            assertEquals("her phone stored it", Ack.OK, carol.knock())
            assertTrue(MessageService.incomingKnocks.value.any { it.cmId == carol.cmId })
            assertTrue("a friend request notifies even while Invisible",
                MessageService.Notice.FRIEND_REQUEST in notices)
            assertEquals(1, heldFiles())

            MessageService.zeroKeys()                          // killed before I looked
            configureAlice()
            assertEquals(listOf("Carol"), MessageService.incomingKnocks.value.map { it.displayName })
            assertEquals(0, heldFiles())
        } finally {
            MessageService.notify = before
        }
    }

    @Test
    fun a_request_withdrawn_while_locked_does_not_come_back_at_unlock() {
        configureAlice()
        MessageService.closeVault()
        val carol = Phone("Carol", 'c').also { it.up() }
        assertEquals(Ack.OK, carol.knock())
        assertEquals(1, heldFiles())
        assertEquals(Ack.OK, carol.knock(withdraw = true))
        assertEquals(0, heldFiles())
        configureAlice()
        assertTrue(MessageService.incomingKnocks.value.isEmpty())
    }

    /** A5: a friend's decoy alert notifies even while I'm Invisible — unlocked or
     *  locked; while locked, what they sent that's still held is shredded. */
    @Test
    fun a_friends_decoy_alert_notifies_even_while_i_am_invisible() {
        val notices = CopyOnWriteArrayList<MessageService.Notice>()
        val before = MessageService.notify
        MessageService.notify = { notices += it }
        try {
            val bob = Phone("Bob", 'b').also { it.up() }
            bob.friends[aliceId] = aPub
            configureAlice(friends = listOf(bob.cmId))
            AppSettings.invisibleMode.value = true
            assertEquals(Ack.OK, bob.send(FrameType.DECOY_ALERT, ByteArray(0)))
            assertTrue(MessageService.Notice.MESSAGE in notices)

            notices.clear()
            MessageService.closeVault()
            bob.text("b-1", "plans")
            assertEquals(1, heldFiles())
            assertFalse("a message doesn't notify while Invisible", MessageService.Notice.MESSAGE in notices)
            assertEquals(Ack.OK, bob.send(FrameType.DECOY_ALERT, ByteArray(0)))
            assertTrue("the decoy alert does", MessageService.Notice.MESSAGE in notices)
            assertEquals("his held message is shredded; only the notice waits", 1, heldFiles())
            configureAlice(friends = listOf(bob.cmId))
            val t = ChatStore.thread(bob.cmId)
            assertTrue(t.messages.none { it.text == "plans" })
            assertTrue(t.messages.any { it.alert && it.text == ChatStore.DECOY_NOTICE })
        } finally {
            MessageService.notify = before
        }
    }

    /** Can't hold it (storage full / not writable): the sender is told to retry —
     *  never "delivered". */
    @Test
    fun a_message_that_cannot_be_held_is_never_confirmed() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        val notADir = java.io.File.createTempFile("held", ".x")
        try {
            MessageService.heldDir = notADir                   // set before the engine starts
            configureAlice(friends = listOf(bob.cmId))
            MessageService.closeVault()
            assertEquals(Ack.RETRY, bob.text("b-1", "kept by Bob for later"))
        } finally {
            notADir.delete()
        }
    }

    @Test
    fun an_erase_while_locked_shreds_what_they_sent_that_was_held() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        MessageService.closeVault()
        bob.text("b-1", "oops")
        assertEquals(1, heldFiles())
        assertEquals(Ack.OK, bob.send(FrameType.ERASE_CHAT, ByteArray(0)))
        assertEquals(0, heldFiles())
        configureAlice(friends = listOf(bob.cmId))
        assertTrue(ChatStore.thread(bob.cmId).messages.isEmpty())
    }

    /** A6: Exit wipes every key — also when the app had been closed with the Buzz
     *  listener running (decision B: the listener keeps them until Exit). */
    @Test
    fun exit_wipes_every_key_even_with_the_buzz_listener_running() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        aliceClosesTheApp()
        assertTrue("the listener holds the keys", MessageService.keysInRam())
        LifecycleController.dropSessionKeys()                 // exactly what Exit runs
        assertFalse("no identity key, friend table or channel left", MessageService.keysInRam())
        assertTrue("and nothing gets in any more", runCatching { bob.text("b-1", "anyone?") }.isFailure)
        assertEquals(0, heldFiles())
    }

    // ---- E. files -------------------------------------------------------------------

    private fun fileIn(chatId: String) = ChatStore.thread(chatId).messages.firstOrNull { it.file != null }

    @Test
    fun a_file_arrives_whole_in_ram_and_counts_only_after_the_last_piece() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        val data = crypto.randomBytes(FileTransfer.CHUNK * 30 + 123)          // 31 pieces, the last short
        assertEquals("confirmed only once it's all there", Ack.OK, bob.sendFile("../../holiday.jpg", data))
        val m = fileIn(bob.cmId)!!
        assertEquals("a path in the name is stripped", "holiday.jpg", m.file!!.name)
        assertEquals(data.size.toLong(), m.file!!.size)
        val got = java.io.ByteArrayOutputStream().also { m.file!!.writeTo(it) }.toByteArray()
        assertTrue("byte for byte", got.contentEquals(data))
        assertEquals("nothing written anywhere", 0, heldFiles())
    }

    @Test
    fun a_file_over_100_mb_is_refused_before_a_single_byte_is_read() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        // The offer CLAIMS 100 MB + 1: refused on the offer alone — no piece is sent or read.
        assertEquals(Ack.REJECTED, bob.sendFile("big.zip", ByteArray(0), claimedSize = FileTransfer.MAX_BYTES + 1))
        assertTrue(ConnDiag.dump().contains("nothing read"))
        assertNull(fileIn(bob.cmId))
    }

    @Test
    fun a_tampered_piece_means_no_file_and_no_receipt() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        val e = runCatching { bob.sendFile("x.bin", crypto.randomBytes(100_000), chunkKey = crypto.randomBytes(32)) }
        assertTrue("never confirmed", e.isFailure)
        assertNull("nothing kept", fileIn(bob.cmId))
    }

    @Test
    fun a_file_while_locked_waits_for_the_unlock_and_a_resend_is_not_kept_twice() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        MessageService.closeVault()
        val data = crypto.randomBytes(5_000)
        val id = crypto.randomBytes(16)
        assertEquals("files are RAM-only, not held: try again after unlock", Ack.RETRY, bob.sendFile("a.txt", data, id))
        configureAlice(friends = listOf(bob.cmId))                            // unlocked
        assertEquals(Ack.OK, bob.sendFile("a.txt", data, id))
        assertEquals("its final receipt got lost; re-sent → 'already here'", Ack.OK, bob.sendFile("a.txt", data, id))
        assertEquals(1, ChatStore.thread(bob.cmId).messages.count { it.file != null })
    }

    @Test
    fun my_file_counts_as_sent_only_after_their_final_receipt() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        val data = crypto.randomBytes(FileTransfer.CHUNK * 3)
        bob.fileReceipt = null                                                // he reads it all but never confirms
        assertEquals(MessageService.FileResult.QUEUED,
            MessageService.sendFile(bob.cmId, "notes.pdf", "application/pdf", org.cmchat.app.media.Chunked.of(data), SelfTimer.OFF))
        val (offer, got) = bob.files.poll(15, TimeUnit.SECONDS) ?: throw AssertionError("no file\n${ConnDiag.dump()}")
        assertEquals("notes.pdf", offer.name)
        assertTrue(got.contentEquals(data))
        waitUntil("no final receipt logged") { ConnDiag.dump().contains("no receipt after the file") }
        assertEquals(MsgState.SENDING, ChatStore.thread(bob.cmId).messages.single { it.mine }.state)
        bob.fileReceipt = Ack.OK
        bob.text("b-1", "got it?")                                           // he shows up → retried
        bob.files.poll(15, TimeUnit.SECONDS) ?: throw AssertionError("no retry")
        waitUntil("sent only now") { ChatStore.thread(bob.cmId).messages.single { it.mine }.state == MsgState.SENT }
    }

    @Test
    fun while_invisible_a_message_waits_silently_but_a_buzz_still_notifies() {
        val notices = CopyOnWriteArrayList<MessageService.Notice>()
        val before = MessageService.notify
        MessageService.notify = { notices += it }
        try {
            val bob = Phone("Bob", 'b').also { it.up() }
            bob.friends[aliceId] = aPub
            configureAlice(friends = listOf(bob.cmId))
            AppSettings.invisibleMode.value = true
            bob.text("b-1", "you there?")
            waitUntil("held as missed") { ChatStore.thread(bob.cmId).messages.any { it.missed } }
            bob.send(FrameType.BUZZ, ByteArray(0))
            waitUntil("a Buzz notifies even while Invisible") { MessageService.Notice.BUZZ in notices }
            assertFalse("no message notification while Invisible", MessageService.Notice.MESSAGE in notices)
            // Online again: the next message notifies (its chat isn't on screen).
            AppSettings.invisibleMode.value = false
            bob.text("b-2", "now?")
            waitUntil("a message notifies once Online") { MessageService.Notice.MESSAGE in notices }
            assertTrue("and it's a new, unread message", ChatStore.thread(bob.cmId).unread)
        } finally {
            MessageService.notify = before
        }
    }
}
