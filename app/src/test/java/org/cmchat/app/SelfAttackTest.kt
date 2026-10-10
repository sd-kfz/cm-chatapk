package org.cmchat.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.cmchat.app.chat.ChatStore
import org.cmchat.app.crypto.CmId
import org.cmchat.app.crypto.CryptoManager
import org.cmchat.app.diag.ConnDiag
import org.cmchat.app.media.MetadataScrubber
import org.cmchat.app.settings.AppSettings
import org.cmchat.app.tor.ServerController
import org.cmchat.app.transport.Ack
import org.cmchat.app.transport.FramePad
import org.cmchat.app.transport.FrameType
import org.cmchat.app.transport.HeldInbox
import org.cmchat.app.transport.InnerCodec
import org.cmchat.app.transport.KnockPayload
import org.cmchat.app.transport.MessageService
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * N2 — the app attacking itself, on the JVM. Part 1 throws random and mutated
 * bytes at every parser that sees data from outside (frames, padding, headers,
 * sealed knocks, file pieces, receipts, CMC-IDs, photos/videos, held records).
 * Part 2 floods the REAL [MessageService] over loopback sockets (garbage
 * connections, stranger knocks, a friend spamming, replayed bytes, every frame
 * type with junk inside) and then checks it still works and kept nothing it
 * shouldn't. Seeds are fixed, so any failure reproduces exactly.
 *
 * "Survives" means: no exception escapes, nothing hangs, nothing is stored or
 * shown that shouldn't be, and a real message still gets through afterwards.
 */
class SelfAttackTest {

    private val crypto = CryptoManager(LazySodiumJava(SodiumJava()))
    private val rnd = Random(0x5EC0_A77AL)
    private fun bytes(n: Int) = ByteArray(n).also { rnd.nextBytes(it) }
    private fun hexs(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    // ======================= Part 1: parsers =================================

    @Test
    fun frame_reader_rejects_bad_lengths_before_allocating() {
        fun read(b: ByteArray) = Transport.readFrame(ByteArrayInputStream(b))
        fun hdr(len: Int) = byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte())
        assertNull(read(hdr(Int.MAX_VALUE)))                        // 2 GB claim: refused, nothing allocated
        assertNull(read(hdr(-1)))
        assertNull(read(hdr(0)))
        assertNull(read(hdr(Transport.MAX_FRAME_BYTES + 1) + ByteArray(16)))
        assertNull(read(hdr(Transport.MAX_FRAME_BYTES) + ByteArray(100)))   // truncated body
        assertNull(read(ByteArray(3)))                                      // truncated header
        repeat(5_000) {
            val r = read(bytes(rnd.nextInt(80)))
            assertTrue(r == null || r.size <= Transport.MAX_FRAME_BYTES)
        }
        // A well-formed small frame still reads back exactly.
        val ok = bytes(100)
        assertTrue(ok.contentEquals(read(hdr(100) + ok)))
    }

    @Test
    fun padding_and_header_parsers_survive_garbage() {
        val codec = InnerCodec()
        repeat(5_000) {
            val b = bytes(rnd.nextInt(3_000))
            FramePad.unpad(b)
            codec.unwrap(b)
        }
        // Edge sizes.
        for (n in listOf(0, 1, 2, 3, 4, 17, 18, 19, 255, 256, 257)) { FramePad.unpad(ByteArray(n) { 0xFF.toByte() }); codec.unwrap(ByteArray(n)) }
    }

    @Test
    fun first_frame_dispatch_survives_garbage_and_forged_boxes() {
        val (aPub, aSec) = crypto.newIdentityKeypair()
        val (bPub, bSec) = crypto.newIdentityKeypair()
        val bobId = CmId.encode("b".repeat(56) + ".onion", bPub)
        val alice = SecureChannel(crypto, aPub, aSec, InnerCodec(), ReplayGuard())
        val contacts = mapOf(bobId to bPub)
        var knocks = 0
        // Raw garbage: never from a friend, never a knock.
        repeat(2_000) {
            val f = alice.onFirstFrame(bytes(rnd.nextInt(2_000)), contacts)
            assertTrue("garbage must be dropped: $f", f is SecureChannel.First.Drop)
        }
        // Sealed to Alice's public key (anyone with her CMC-ID can do this) with
        // junk inside: dropped, or at most an anonymous knock — never a handshake.
        repeat(1_000) {
            val inner = if (rnd.nextBoolean()) bytes(rnd.nextInt(500)) else FramePad.pad(bytes(rnd.nextInt(400)))
            val f = alice.onFirstFrame(crypto.sealedSeal(inner, aPub), contacts)
            assertFalse("a sealed box never opens a friend's session", f is SecureChannel.First.Handshake)
            if (f is SecureChannel.First.Knock) knocks++
        }
        // Boxed with Bob's real key but junk inside: dropped (a valid prekey
        // request needs the exact type + 16-byte challenge).
        repeat(1_000) {
            val inner = if (rnd.nextBoolean()) bytes(rnd.nextInt(500)) else FramePad.pad(bytes(rnd.nextInt(400)))
            val f = alice.onFirstFrame(crypto.boxSeal(inner, aPub, bSec), contacts)
            assertFalse("junk from a friend never starts a session", f is SecureChannel.First.Handshake)
        }
        assertTrue("random bytes essentially never form a knock header", knocks < 5)
    }

    @Test
    fun file_pieces_and_receipts_reject_garbage() {
        val (aPub, aSec) = crypto.newIdentityKeypair()
        val (bPub, _) = crypto.newIdentityKeypair()
        val ch = SecureChannel(crypto, aPub, aSec, InnerCodec(), ReplayGuard())
        val key = bytes(32); val id = bytes(16)
        repeat(2_000) {
            assertNull(ch.openFileChunk(key, id, rnd.nextInt(10), 10, bytes(rnd.nextInt(40_000))))
            assertNull(ch.openFileReceipt(bytes(rnd.nextInt(200)), id, bPub))
            assertNull(ch.openKnockReceipt(bytes(rnd.nextInt(200)), bytes(16), bPub))
        }
    }

    @Test
    fun cmid_decoder_survives_garbage_and_mutations() {
        val (pub, _) = crypto.newIdentityKeypair()
        val good = CmId.encode("q".repeat(56) + ".onion", pub)
        repeat(3_000) {
            val s = when (rnd.nextInt(3)) {
                0 -> String(CharArray(rnd.nextInt(200)) { (32 + rnd.nextInt(95)).toChar() })
                1 -> good.substring(0, rnd.nextInt(good.length))
                else -> good.toCharArray().also { c -> c[rnd.nextInt(c.size)] = (32 + rnd.nextInt(95)).toChar() }.concatToString()
            }
            val d = CmId.decode(s)
            // Whatever decodes must be internally consistent (re-encodes to a valid id).
            if (d != null) assertTrue(CmId.decode(CmId.encode(d.onion, d.identityPubKeyHex)) != null)
        }
        assertTrue(CmId.decode(good) != null)
    }

    @Test
    fun media_cleaner_survives_mutated_and_random_files() {
        val samples = listOf(sampleJpeg(), samplePng(), sampleGif(), sampleMp4())
        for (s in samples) assertTrue("a clean sample is accepted", MetadataScrubber.clean(s) != null)
        var cleaned = 0
        repeat(4_000) { i ->
            val m = mutate(samples[i % samples.size])
            val out = MetadataScrubber.clean(m)
            if (out != null) {
                cleaned++
                assertTrue("output never grows by more than the orientation tag", out.size <= m.size + 64)
            }
        }
        repeat(2_000) { MetadataScrubber.clean(bytes(rnd.nextInt(4_000))) }
        assertTrue("the fuzz did reach the cleaners", cleaned > 100)
    }

    @Test
    fun held_inbox_shreds_garbage_and_survives_forged_records() {
        val dir = Files.createTempDirectory("held-fuzz").toFile()
        try {
            val (me, mySec) = crypto.newIdentityKeypair()
            val h = HeldInbox(dir, crypto)
            // Random files, and records sealed to my key (anyone with my public
            // key can seal) holding junk: none may break reading.
            repeat(150) { i -> File(dir, "h%010d.held".format(i)).writeBytes(bytes(rnd.nextInt(600))) }
            repeat(150) { i -> File(dir, "h%010d.held".format(1_000 + i)).writeBytes(crypto.sealedSeal(bytes(rnd.nextInt(600)), me)) }
            File(dir, "h0000099999.held.tmp").writeBytes(bytes(50))
            val good = HeldInbox.Record(FrameType.MSG, null, "kept".toByteArray(), 1L, closed = false)
            assertTrue(h.put(good, me))
            val all = h.readAll(me, mySec)
            assertTrue("the real record survives", all.any { String(it.record.body) == "kept" })
            // Everything that wasn't a well-formed record is gone from flash.
            assertEquals(all.size, dir.listFiles()!!.count { it.name.endsWith(".held") })
            assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- sample files for the media fuzz -----------------------------------

    private val gps = "GPS+52.5200+013.4050".toByteArray()
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun seg(marker: Int, p: ByteArray) = b(0xFF, marker, (p.size + 2) shr 8, (p.size + 2) and 0xFF) + p

    private fun sampleJpeg(): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(b(0xFF, 0xD8)); o.write(seg(0xE0, "JFIF\u0000\u0001\u0001".toByteArray()))
        o.write(seg(0xE1, "Exif\u0000\u0000".toByteArray() + b('M'.code, 'M'.code, 0, 42, 0, 0, 0, 8) + gps))
        o.write(seg(0xDB, ByteArray(65) { 1 })); o.write(seg(0xC0, ByteArray(15) { 2 }))
        o.write(seg(0xDA, ByteArray(10) { 3 })); o.write(b(0x11, 0xFF, 0x00, 0x22, 0xFF, 0xD9))
        return o.toByteArray()
    }

    private fun crc(type: ByteArray, data: ByteArray): ByteArray {
        val c = java.util.zip.CRC32(); c.update(type); c.update(data)
        val v = c.value
        return b((v ushr 24).toInt() and 0xFF, (v ushr 16).toInt() and 0xFF, (v ushr 8).toInt() and 0xFF, v.toInt() and 0xFF)
    }
    private fun chunk(t: String, d: ByteArray): ByteArray {
        val type = t.toByteArray()
        return b(d.size ushr 24, (d.size ushr 16) and 0xFF, (d.size ushr 8) and 0xFF, d.size and 0xFF) + type + d + crc(type, d)
    }
    private fun samplePng() = b(0x89, 'P'.code, 'N'.code, 'G'.code, 0x0D, 0x0A, 0x1A, 0x0A) +
        chunk("IHDR", b(0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0)) + chunk("tEXt", "Location\u0000".toByteArray() + gps) +
        chunk("IDAT", ByteArray(12) { 7 }) + chunk("IEND", ByteArray(0))

    private fun sampleGif(): ByteArray {
        val o = ByteArrayOutputStream()
        o.write("GIF89a".toByteArray()); o.write(b(1, 0, 1, 0, 0, 0, 0))
        o.write(b(0x21, 0xFE, gps.size)); o.write(gps); o.write(0)                 // comment extension
        o.write(b(0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0)); o.write(b(2, 2, 0x4C, 0x01, 0))  // image
        o.write(0x3B)
        return o.toByteArray()
    }

    private fun box(t: String, d: ByteArray) =
        b((d.size + 8) ushr 24, ((d.size + 8) ushr 16) and 0xFF, ((d.size + 8) ushr 8) and 0xFF, (d.size + 8) and 0xFF) + t.toByteArray() + d
    private fun sampleMp4() = box("ftyp", "isom".toByteArray() + ByteArray(4)) +
        box("moov", box("mvhd", ByteArray(20)) + box("udta", box("xyz ", gps))) +
        box("mdat", ByteArray(32) { 9 })

    private fun mutate(src: ByteArray): ByteArray {
        var a = src.copyOf()
        repeat(1 + rnd.nextInt(4)) {
            when (rnd.nextInt(5)) {
                0 -> if (a.isNotEmpty()) a[rnd.nextInt(a.size)] = rnd.nextInt(256).toByte()               // flip a byte
                1 -> if (a.size > 1) a = a.copyOf(rnd.nextInt(a.size))                                   // truncate
                2 -> { val i = rnd.nextInt(a.size + 1); a = a.copyOfRange(0, i) + bytes(rnd.nextInt(40)) + a.copyOfRange(i, a.size) }
                3 -> if (a.size > 8) { val i = rnd.nextInt(a.size - 4); for (k in 0 until 4) a[i + k] = 0xFF.toByte() } // huge length
                else -> if (a.size > 8) { val i = rnd.nextInt(a.size - 4); for (k in 0 until 4) a[i + k] = 0 }       // zero length
            }
        }
        return a
    }

    // ======================= Part 2: floods on the real app ====================

    private val loop: InetAddress = InetAddress.getByName("127.0.0.1")
    private lateinit var aPub: String
    private lateinit var aSec: String
    private lateinit var aliceId: String
    private lateinit var aliceServer: ServerSocket
    private lateinit var realDialer: (org.cmchat.app.crypto.CmIdData) -> Socket
    private lateinit var heldDir: File
    private val escaped = CopyOnWriteArrayList<Throwable>()
    private var oldHandler: Thread.UncaughtExceptionHandler? = null

    /** A friend's phone: same wire code, own identity. */
    private inner class Friend(letter: Char) {
        val pub: String; val sec: String
        init { val (p, s) = crypto.newIdentityKeypair(); pub = p; sec = s }
        val cmId = CmId.encode(letter.toString().repeat(56) + ".onion", pub)
        val ch = SecureChannel(crypto, pub, sec, InnerCodec(), ReplayGuard())

        fun send(type: FrameType, payload: ByteArray, tap: OutputStream? = null): Ack? = runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(loop, aliceServer.localPort), 2_000)
                s.soTimeout = 5_000
                val out = if (tap == null) s.getOutputStream() else Tee(s.getOutputStream(), tap)
                SecureWire.send(ch, s.getInputStream(), out, aPub, type, payload)
            }
        }.getOrNull()

        fun text(id: String, t: String) = send(FrameType.MSG,
            Messages.json.encodeToString(TextPayload.serializer(), TextPayload(id, t, "off")).toByteArray())
    }

    /** Copies everything written to the socket (to replay it later). */
    private class Tee(out: OutputStream, private val tap: OutputStream) : FilterOutputStream(out) {
        override fun write(b: Int) { super.write(b); tap.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); tap.write(b, off, len) }
    }

    private fun raw(bytes: ByteArray, holdMs: Long = 0) = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(loop, aliceServer.localPort), 2_000)
            s.soTimeout = 1_000
            s.getOutputStream().write(bytes); s.getOutputStream().flush()
            if (holdMs > 0) Thread.sleep(holdMs)
            runCatching { s.getInputStream().read() }
        }
    }

    private fun waitUntil(what: String, ms: Long = 15_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out: $what\n${ConnDiag.dump()}")
            Thread.sleep(20)
        }
    }

    private fun messagesFrom(f: Friend) = ChatStore.thread(f.cmId).messages.filter { !it.system && !it.mine }

    private fun configureAlice(friends: List<Friend>, locked: Boolean = false) {
        MessageService.configure(crypto, "Alice", aPub, aSec, aliceId, friends.map { it.cmId },
            confirmedAddresses = friends.associate { it.cmId to aliceId },
            confirmedNames = friends.associate { it.cmId to "Alice" })
        if (!locked) {
            MessageService.openVault()
            waitUntil("vault open") { MessageService.vaultIsOpen() }
        }
    }

    @Before
    fun setUp() {
        MessageService.zeroKeys()
        ChatStore.clearAll()
        ConnDiag.clear()
        AppSettings.invisibleMode.value = false
        heldDir = Files.createTempDirectory("held").toFile()
        MessageService.heldDir = heldDir
        val (p, s) = crypto.newIdentityKeypair(); aPub = p; aSec = s
        aliceId = CmId.encode("a".repeat(56) + ".onion", aPub)
        aliceServer = ServerSocket(0, 200, loop)
        val srv = aliceServer
        thread(isDaemon = true, name = "alice-listener") {
            while (!srv.isClosed) {
                val sock = runCatching { srv.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    try {
                        sock.soTimeout = 3_000
                        ServerController.onIncoming?.invoke(sock)
                    } finally {
                        runCatching { sock.close() }
                    }
                }
            }
        }
        realDialer = MessageService.dialer
        MessageService.dialer = { throw ConnectException("no friends are listening in this test") }
        oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> escaped += e }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(oldHandler)
        MessageService.zeroKeys()
        MessageService.dialer = realDialer
        ServerController.onIncoming = null
        runCatching { aliceServer.close() }
        ChatStore.clearAll()
        heldDir.deleteRecursively()
        MessageService.heldDir = null
    }

    @Test
    fun garbage_connection_flood_then_a_real_message_still_arrives() {
        val bob = Friend('b')
        configureAlice(listOf(bob))
        val pool = Executors.newFixedThreadPool(32)
        repeat(400) { i ->
            pool.execute {
                when (i % 4) {
                    0 -> raw(bytes(rnd.nextInt(300)))                                     // noise
                    1 -> raw(byteArrayOf(0x7F, -1, -1, -1))                               // "2 GB frame"
                    2 -> raw(ByteArray(0), holdMs = 50)                                   // connect, say nothing
                    else -> raw(byteArrayOf(0, 0, 4, 0) + bytes(1_024))                   // well-sized junk frame
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        assertEquals(Ack.OK, bob.text("after-flood", "still here"))
        waitUntil("Bob's message shown") { messagesFrom(bob).any { it.text == "still here" } }
        assertEquals("the flood stored nothing", 1, ChatStore.threads.value.values.sumOf { t -> t.messages.size })
        assertTrue("the flood made no friend requests", MessageService.incomingKnocks.value.isEmpty())
        assertTrue("nothing escaped a handler: $escaped", escaped.isEmpty())
    }

    @Test
    fun stranger_knock_flood_is_rate_limited_and_capped() {
        val bob = Friend('b')
        configureAlice(listOf(bob))
        var ok = 0
        var other = 0
        repeat(40) { i ->
            val stranger = Friend(('c' + (i % 20)))
            val nonce = crypto.randomBytes(16)
            val body = Messages.json.encodeToString(KnockPayload.serializer(),
                KnockPayload("Stranger $i", stranger.cmId, nonce = hexs(nonce))).toByteArray()
            val sealed = stranger.ch.sealKnock(body, aPub)
            val ack = runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress(loop, aliceServer.localPort), 2_000)
                    s.soTimeout = 5_000
                    SecureWire.sendKnock(stranger.ch, s.getInputStream(), s.getOutputStream(), sealed, nonce, aPub)
                }
            }.getOrNull()
            if (ack == Ack.OK) ok++ else other++
        }
        // Malformed knocks (valid seal, junk inside): no request appears.
        repeat(30) { raw(run { val f = crypto.sealedSeal(FramePad.pad(bytes(rnd.nextInt(200))), aPub); byteArrayOf(0, 0, (f.size ushr 8).toByte(), f.size.toByte()) + f }) }
        val pending = MessageService.incomingKnocks.value
        assertTrue("burst of 6, then one per 20 s: $ok accepted", ok <= 7)
        assertTrue("the rest were told to retry later, not silently dropped", other >= 33)
        assertTrue("at most what was accepted is shown (${pending.size})", pending.size <= ok)
        assertTrue("names are capped in length", pending.all { it.displayName.length <= 24 })
        // A request name can't hide behind invisible or direction-flipping characters.
        assertEquals("evil", MessageService.cleanName("\u202Eevil\u200F\u0007 "))
        assertEquals("Ana", MessageService.cleanName("\u2066Ana\u2069"))
        assertNull(MessageService.cleanName("\u202E\u200B\n"))
        // A real friend is unaffected by the stranger flood.
        assertEquals(Ack.OK, bob.text("k1", "hello"))
        assertTrue("nothing escaped a handler: $escaped", escaped.isEmpty())
    }

    @Test
    fun a_spamming_friend_hits_the_limit_and_nothing_confirmed_is_lost() {
        val bob = Friend('b')
        configureAlice(listOf(bob))
        val acks = (0 until 60).map { bob.text("s$it", "spam $it") }
        val confirmed = acks.indices.filter { acks[it] == Ack.OK }
        assertTrue("the per-friend limit kicked in (${confirmed.size} of 60)", confirmed.size in 15..40)
        // Every message their phone was told "stored" is really there — and no other.
        waitUntil("confirmed messages shown") { messagesFrom(bob).size >= confirmed.size }
        assertEquals(confirmed.map { "spam $it" }.toSet(), messagesFrom(bob).map { it.text }.toSet())
        // After a short pause the friend can talk again.
        Thread.sleep(1_200)
        assertEquals(Ack.OK, bob.text("after", "calm now"))
        assertTrue("nothing escaped a handler: $escaped", escaped.isEmpty())
    }

    @Test
    fun replayed_connection_bytes_are_never_stored_twice() {
        val bob = Friend('b')
        configureAlice(listOf(bob))
        val tap = ByteArrayOutputStream()
        assertEquals(Ack.OK, bob.send(FrameType.MSG,
            Messages.json.encodeToString(TextPayload.serializer(), TextPayload("once", "only once", "off")).toByteArray(), tap))
        val recorded = tap.toByteArray()
        repeat(20) { raw(recorded) }
        Thread.sleep(300)
        assertEquals(1, messagesFrom(bob).count { it.text == "only once" })
        assertTrue("nothing escaped a handler: $escaped", escaped.isEmpty())
    }

    @Test
    fun junk_inside_every_frame_type_never_breaks_the_app() {
        val bob = Friend('b')
        configureAlice(listOf(bob))
        val types = listOf(FrameType.MSG, FrameType.KNOCK_ACCEPT, FrameType.ERASE_CHAT, FrameType.BUZZ,
            FrameType.ADDR_UPDATE, FrameType.COVER, FrameType.DECOY_ALERT, FrameType.TEAM_CLOCK,
            FrameType.NICKNAME, FrameType.FILE_OFFER)
        val junk = listOf(
            ByteArray(0),
            bytes(50),
            "{".toByteArray(),
            "[".repeat(5_000).toByteArray(),                                   // deep nesting
            "{\"id\":\"x\",\"text\":${"\"" + "A".repeat(50_000) + "\""},\"selfTimer\":\"zz\"}".toByteArray(),
            "{\"id\":\"f\",\"name\":\"../../x\",\"size\":-5,\"mime\":\"a\",\"key\":\"zz\",\"chunks\":-1}".toByteArray(),
            "{\"id\":\"f\",\"name\":\"a\",\"size\":9223372036854775807,\"mime\":\"a\",\"key\":\"00\",\"chunks\":2147483647}".toByteArray(),
            "UTC+99:99|-1".toByteArray(),
            String(CharArray(300) { (rnd.nextInt(0x2FFF) + 1).toChar() }).toByteArray(),
        )
        for (t in types) for (j in junk) {
            bob.send(t, j)                     // any answer (or none) is fine — it must not break anything
            Thread.sleep(210)                  // stay under the per-friend limit
        }
        assertEquals(Ack.OK, bob.text("final", "app still fine"))
        waitUntil("the real message after the junk") { messagesFrom(bob).any { it.text == "app still fine" } }
        assertTrue("no junk became a contact or request", MessageService.incomingKnocks.value.isEmpty())
        assertTrue("nothing escaped a handler: $escaped", escaped.isEmpty())
    }
}
