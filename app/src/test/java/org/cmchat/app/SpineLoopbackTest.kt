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

        /** "Add friend" from this phone: an anonymous sealed knock to Alice. */
        fun knock() {
            val body = Messages.json.encodeToString(KnockPayload.serializer(), KnockPayload(name, cmId)).toByteArray()
            val sealed = ch.sealKnock(body, aPub)
            toAlice { s -> Transport.writeFrame(s.getOutputStream(), sealed) }
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

    private fun configureAlice(friends: List<String> = emptyList()) =
        MessageService.configure(crypto, "Alice", aPub, aSec, aliceId, friends)

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
    }

    @After
    fun tearDown() {
        MessageService.zeroKeys()
        MessageService.dialer = realDialer
        MessageService.onFriendConfirmed = null
        MessageService.onContactAccepted = null
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
}
