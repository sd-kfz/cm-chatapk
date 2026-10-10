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
                            inbox.put(SecureWire.receive(ch, it.getInputStream(), it.getOutputStream(), friends))
                        }
                    }
                }
            }
        }

        fun down() {
            published.remove(onion)
            runCatching { server?.close() }
            server = null
        }

        private fun toAlice(block: (Socket) -> Unit) = Socket().use { s ->
            s.connect(InetSocketAddress(loop, aliceServer.localPort), 2_000)
            s.soTimeout = 5_000
            block(s)
        }

        /** One forward-secret frame to Alice (the real app). */
        fun send(type: FrameType, payload: ByteArray) = toAlice { s ->
            SecureWire.send(ch, s.getInputStream(), s.getOutputStream(), aPub, type, payload)
        }

        fun text(id: String, text: String) = send(FrameType.MSG,
            Messages.json.encodeToString(TextPayload.serializer(), TextPayload(id, text, "off")).toByteArray())

        /** "Add friend" from this phone: an anonymous sealed knock to Alice
         * ([withdraw] = "I cancelled my request"). */
        fun knock(withdraw: Boolean = false) {
            val body = Messages.json.encodeToString(KnockPayload.serializer(),
                KnockPayload(name, cmId, withdraw)).toByteArray()
            val sealed = ch.sealKnock(body, aPub)
            toAlice { s -> Transport.writeFrame(s.getOutputStream(), sealed) }
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

    private fun waitUntil(what: String, ms: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what\n${ConnDiag.dump()}")
            Thread.sleep(20)
        }
    }

    private fun textOf(m: SecureWire.Received.Message) =
        Messages.json.decodeFromString(TextPayload.serializer(), String(m.body)).text

    private fun configureAlice(
        friends: List<String> = emptyList(),
        pending: List<String> = emptyList(),
        terminations: List<String> = emptyList(),
    ) = MessageService.configure(crypto, "Alice", aPub, aSec, aliceId, friends,
        pendingCmIds = pending, pendingTerminations = terminations)

    @Before
    fun setUp() {
        MessageService.zeroKeys()
        ChatStore.clearAll()
        ConnDiag.clear()
        AppSettings.invisibleMode.value = false
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
        ServerController.onIncoming = null
        runCatching { aliceServer.close() }
        phones.forEach { it.down() }
        ChatStore.clearAll()
        AppSettings.invisibleMode.value = false
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

        // 3) Bob taps Accept: he adds Alice and sends KNOCK_ACCEPT (forward-secret).
        bob.friends[aliceId] = aPub
        bob.send(FrameType.KNOCK_ACCEPT,
            Messages.json.encodeToString(KnockPayload.serializer(), KnockPayload("Bob", bob.cmId)).toByteArray())
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

    @Test
    fun a_friends_decoy_shows_the_notice_and_erases_when_i_leave_the_chat() {
        val bob = Phone("Bob", 'b').also { it.up() }
        bob.friends[aliceId] = aPub
        configureAlice(friends = listOf(bob.cmId))
        bob.text("b-1", "earlier message")
        waitUntil("message in") { ChatStore.thread(bob.cmId).messages.size == 1 }

        bob.send(FrameType.DECOY_ALERT, ByteArray(0))
        waitUntil("notice shown") {
            ChatStore.thread(bob.cmId).messages.any { it.alert && it.text == ChatStore.DECOY_NOTICE }
        }
        assertEquals("not destroyed instantly", 2, ChatStore.thread(bob.cmId).messages.size)
        ChatStore.leaveChat(bob.cmId)                 // leave the chat…
        assertTrue("…and it's gone", ChatStore.thread(bob.cmId).messages.isEmpty())
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
        bob.send(FrameType.KNOCK_ACCEPT,
            Messages.json.encodeToString(KnockPayload.serializer(), KnockPayload("Bob", bob.cmId)).toByteArray())
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
}
