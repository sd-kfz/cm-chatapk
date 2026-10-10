package org.cmchat.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.coroutines.runBlocking
import org.cmchat.app.crypto.CryptoManager
import org.cmchat.app.tor.SoftReconnect
import org.cmchat.app.transport.FrameType
import org.cmchat.app.transport.HeldInbox
import org.cmchat.app.vault.OwnOnionStash
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The flash side of "held, never dropped" (A1), the sealed onion stash (A3)
 * and the soft-reconnect decision (A4).
 */
class HeldInboxTest {

    private val crypto = CryptoManager(LazySodiumJava(SodiumJava()))
    private val dir: File = Files.createTempDirectory("held").toFile()
    private val me = crypto.newIdentityKeypair()
    private val friend = crypto.newIdentityKeypair()

    @After
    fun tearDown() { dir.deleteRecursively() }

    private fun rec(text: String, closed: Boolean = false, from: String? = friend.first) =
        HeldInbox.Record(FrameType.MSG, from, text.toByteArray(), atMs = 1_700_000_000_000L, closed = closed)

    @Test
    fun records_come_back_in_arrival_order_exactly_as_held() {
        val h = HeldInbox(dir, crypto)
        assertTrue(h.put(rec("one", closed = true), me.first))
        assertTrue(h.put(rec("two"), me.first))
        assertTrue(h.put(HeldInbox.Record(FrameType.KNOCK, null, "card".toByteArray(), 5L, closed = false), me.first))
        val all = h.readAll(me.first, me.second)
        assertEquals(listOf("one", "two", "card"), all.map { String(it.record.body) })
        val first = all[0].record
        assertEquals(FrameType.MSG, first.type)
        assertTrue(friend.first.equals(first.fromPub, ignoreCase = true))
        assertEquals(1_700_000_000_000L, first.atMs)
        assertTrue(first.closed)
        assertNull("a knock has no sender key", all[2].record.fromPub)
        all.forEach { h.remove(it) }
        assertEquals(0, h.count())
    }

    @Test
    fun only_my_identity_key_opens_them_and_nothing_is_plaintext() {
        val h = HeldInbox(dir, crypto)
        h.put(rec("secret words"), me.first)
        assertFalse(dir.walk().filter { it.isFile }.any { String(it.readBytes(), Charsets.ISO_8859_1).contains("secret") })
        // Someone else's key (or a new identity after a wipe) can't read it — and
        // an unreadable record is shredded, never left behind.
        assertTrue(h.readAll(friend.first, friend.second).isEmpty())
        assertEquals(0, h.count())
    }

    @Test
    fun it_is_bounded_and_says_so_instead_of_pretending() {
        val h = HeldInbox(dir, crypto)
        repeat(HeldInbox.MAX_RECORDS) { assertTrue(h.put(rec("m$it"), me.first)) }
        assertFalse("full: the sender is told RETRY", h.put(rec("one too many"), me.first))
        assertEquals(HeldInbox.MAX_RECORDS, h.count())
    }

    @Test
    fun a_half_written_leftover_is_shredded_and_never_read() {
        val h = HeldInbox(dir, crypto)
        h.put(rec("whole"), me.first)
        File(dir, "h0000000099.tmp").writeBytes(ByteArray(64) { 7 })   // a crash mid-write
        assertEquals(listOf("whole"), h.readAll(me.first, me.second).map { String(it.record.body) })
        assertFalse(File(dir, "h0000000099.tmp").exists())
    }

    @Test
    fun one_friends_records_can_be_shredded_and_everything_can_be_wiped() {
        val h = HeldInbox(dir, crypto)
        val other = crypto.newIdentityKeypair()
        h.put(rec("from friend"), me.first)
        h.put(rec("from other", from = other.first), me.first)
        assertEquals(1, h.removeFrom(friend.first, me.first, me.second))
        assertEquals(listOf("from other"), h.readAll(me.first, me.second).map { String(it.record.body) })
        HeldInbox.shredAll(dir)
        assertEquals(0, h.count())
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    // ---- A3: a new onion key made while locked is kept, sealed -------------------

    @Test
    fun a_new_onion_key_made_while_locked_is_kept_sealed_for_the_next_unlock() {
        assertTrue(OwnOnionStash.put(dir, crypto, me.first, "ED25519-V3:secretkey", "abc.onion"))
        assertFalse(dir.walk().filter { it.isFile }.any { String(it.readBytes(), Charsets.ISO_8859_1).contains("secretkey") })
        assertNull("only my identity opens it", OwnOnionStash.read(dir, crypto, friend.first, friend.second))
        val got = OwnOnionStash.read(dir, crypto, me.first, me.second)!!
        assertEquals("ED25519-V3:secretkey", got.key)
        assertEquals("abc.onion", got.onion)
        OwnOnionStash.clear(dir)
        assertNull(OwnOnionStash.read(dir, crypto, me.first, me.second))
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    // ---- A4: soft reconnect ------------------------------------------------------

    private class FakeClock { var now = 0L }

    @Test
    fun a_network_change_rebuilds_circuits_without_a_tor_restart() = runBlocking {
        val clock = FakeClock()
        var kicks = 0
        var polls = 0
        val ok = SoftReconnect.run(
            kick = { kicks++ },
            circuitUp = { ++polls >= 5 },                       // back after a few seconds
            log = {}, sleep = { clock.now += it }, clock = { clock.now },
        )
        assertTrue(ok)
        assertEquals(1, kicks)
    }

    @Test
    fun no_circuit_within_the_wait_falls_back_to_a_full_restart() = runBlocking {
        val clock = FakeClock()
        val log = ArrayList<String>()
        val ok = SoftReconnect.run(
            kick = {}, circuitUp = { false }, log = { log += it },
            sleep = { clock.now += it }, clock = { clock.now },
        )
        assertFalse(ok)
        assertTrue(clock.now >= SoftReconnect.WAIT_MS)
        assertTrue(log.last().contains("full Tor restart"))
    }

    @Test
    fun tor_refusing_the_kick_falls_back_at_once() = runBlocking {
        val ok = SoftReconnect.run(kick = { throw IllegalStateException("552") }, circuitUp = { true }, log = {},
            sleep = {}, clock = { 0L })
        assertFalse(ok)
    }

    @Test
    fun a_record_holds_any_frame_body_byte_for_byte() {
        val h = HeldInbox(dir, crypto)
        val body = ByteArray(60_000) { (it * 31).toByte() }
        h.put(HeldInbox.Record(FrameType.TEAM_CLOCK, friend.first, body, 9L, closed = false), me.first)
        assertArrayEquals(body, h.readAll(me.first, me.second).single().record.body)
    }
}
