package org.cmchat.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.cmchat.app.transport.Outbox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong


class OutboxTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    private fun waitUntil(ms: Long = 5_000, what: String = "condition", cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!cond()) {
            if (System.currentTimeMillis() > end) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }


    private class Flaky(val failFirst: Int) {
        val attempts = AtomicInteger()
        fun deliver(sent: MutableList<String>, label: String) {
            if (attempts.incrementAndGet() <= failFirst) throw IOException("unreachable")
            sent += label
        }
    }

    @Test
    fun delivers_in_order_per_friend() {
        val ob = Outbox(scope, backoffMs = listOf(20L))
        val sent = CopyOnWriteArrayList<String>()
        val done = CopyOnWriteArrayList<String>()
        (1..5).forEach { i ->
            ob.enqueue(Outbox.Item("bob", deliver = { sent += "m$i" }, onDelivered = { done += "m$i" }))
        }
        waitUntil(what = "5 deliveries") { done.size == 5 }
        assertEquals(listOf("m1", "m2", "m3", "m4", "m5"), sent)
        assertEquals(0, ob.size())
    }

    @Test
    fun a_failed_send_is_retried_quietly_until_it_lands() {
        val ob = Outbox(scope, backoffMs = listOf(20L, 40L))
        val sent = CopyOnWriteArrayList<String>()
        val f = Flaky(failFirst = 3)
        ob.enqueue(Outbox.Item("bob", deliver = { f.deliver(sent, "hello") }))
        waitUntil(what = "delivery after 3 failures") { sent.isNotEmpty() }
        assertEquals(4, f.attempts.get())
        assertEquals(listOf("hello"), sent)
    }

    @Test
    fun a_kick_retries_that_friend_at_once_instead_of_waiting_out_the_backoff() {
        val ob = Outbox(scope, backoffMs = listOf(60_000L))
        val sent = CopyOnWriteArrayList<String>()
        val f = Flaky(failFirst = 1)
        ob.enqueue(Outbox.Item("bob", deliver = { f.deliver(sent, "hi") }))
        waitUntil(what = "first failed attempt") { f.attempts.get() == 1 }
        Thread.sleep(50)
        ob.kick("someone-else")
        Thread.sleep(200)
        assertTrue(sent.isEmpty())
        ob.kick("bob")
        waitUntil(1_000, "delivery right after the kick") { sent == listOf("hi") }
    }

    @Test
    fun kick_all_wakes_every_friend() {
        val ob = Outbox(scope, backoffMs = listOf(60_000L))
        val sent = CopyOnWriteArrayList<String>()
        val a = Flaky(1); val b = Flaky(1)
        ob.enqueue(Outbox.Item("ann", deliver = { a.deliver(sent, "to-ann") }))
        ob.enqueue(Outbox.Item("bob", deliver = { b.deliver(sent, "to-bob") }))
        waitUntil(what = "both failed once") { a.attempts.get() == 1 && b.attempts.get() == 1 }
        Thread.sleep(50)
        ob.kickAll()
        waitUntil(1_000, "both delivered") { sent.toSet() == setOf("to-ann", "to-bob") }
    }

    @Test
    fun a_kick_during_a_slow_attempt_is_not_lost() {
        val ob = Outbox(scope, backoffMs = listOf(60_000L))
        val sent = CopyOnWriteArrayList<String>()
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        val attempts = AtomicInteger()
        ob.enqueue(Outbox.Item("bob", deliver = {
            if (attempts.incrementAndGet() == 1) {
                inFlight.countDown(); release.await(5, TimeUnit.SECONDS)
                throw IOException("timed out")
            }
            sent += "hi"
        }))
        assertTrue(inFlight.await(5, TimeUnit.SECONDS))
        ob.kick("bob")
        release.countDown()
        waitUntil(1_000, "immediate retry") { sent == listOf("hi") }
    }

    @Test
    fun a_stuck_friend_never_blocks_another() {
        val ob = Outbox(scope, backoffMs = listOf(60_000L))
        val sent = CopyOnWriteArrayList<String>()
        ob.enqueue(Outbox.Item("ann", deliver = { throw IOException("offline") }))
        ob.enqueue(Outbox.Item("bob", deliver = { sent += "to-bob" }))
        waitUntil(what = "bob delivered") { sent == listOf("to-bob") }
    }

    @Test
    fun an_item_no_longer_wanted_is_dropped_and_never_sent() {
        val ob = Outbox(scope, backoffMs = listOf(60_000L))
        val sent = CopyOnWriteArrayList<String>()
        val wanted = java.util.concurrent.atomic.AtomicBoolean(true)
        val first = AtomicInteger()
        ob.enqueue(Outbox.Item("bob",
            deliver = { if (first.incrementAndGet() == 1) throw IOException("offline"); sent += "erased-msg" },
            stillWanted = { wanted.get() }))
        ob.enqueue(Outbox.Item("bob", deliver = { sent += "erase" }))
        waitUntil(what = "first failure") { first.get() == 1 }
        wanted.set(false)
        ob.kick("bob")
        waitUntil(1_000, "erase sent") { sent.isNotEmpty() }
        Thread.sleep(100)
        assertEquals(listOf("erase"), sent)
        assertEquals(1, first.get())
    }

    @Test
    fun a_newer_item_with_the_same_replace_key_replaces_the_queued_one() {
        val ob = Outbox(scope, backoffMs = listOf(60_000L))
        val sent = CopyOnWriteArrayList<String>()
        val gate = AtomicInteger()

        ob.enqueue(Outbox.Item("bob", deliver = { if (gate.incrementAndGet() == 1) throw IOException("x"); sent += "msg" }))
        waitUntil(what = "head failed") { gate.get() == 1 }
        ob.enqueue(Outbox.Item("bob", replaceKey = "clock", deliver = { sent += "clock-1" }))
        ob.enqueue(Outbox.Item("bob", replaceKey = "clock", deliver = { sent += "clock-2" }))
        assertEquals(2, ob.size())
        ob.kick("bob")
        waitUntil(1_000, "drained") { ob.size() == 0 }
        assertEquals(listOf("msg", "clock-2"), sent)
    }

    @Test
    fun clear_drops_everything_and_nothing_is_sent_afterwards() {
        val ob = Outbox(scope, backoffMs = listOf(60_000L))
        val attempts = AtomicInteger()
        repeat(3) { ob.enqueue(Outbox.Item("bob", deliver = { attempts.incrementAndGet(); throw IOException("offline") })) }
        waitUntil(what = "first attempt") { attempts.get() == 1 }
        ob.clear()
        assertEquals(0, ob.size())
        ob.kickAll(); ob.kick("bob")
        Thread.sleep(300)
        assertEquals(1, attempts.get())
    }

    @Test
    fun after_a_wipe_a_new_item_is_sent_exactly_once() {


        val ob = Outbox(scope, backoffMs = listOf(60_000L))
        val sent = CopyOnWriteArrayList<String>()
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        ob.enqueue(Outbox.Item("bob", deliver = { inFlight.countDown(); release.await(5, TimeUnit.SECONDS); sent += "old" }))
        assertTrue(inFlight.await(5, TimeUnit.SECONDS))
        ob.clear()
        ob.enqueue(Outbox.Item("bob", deliver = { sent += "new" }))
        waitUntil(what = "new sent") { "new" in sent }
        release.countDown()
        Thread.sleep(300)
        assertEquals(1, sent.count { it == "new" })
    }

    @Test
    fun the_queue_is_bounded_and_old_items_expire() {
        val clock = AtomicLong(1_000_000L)
        val ob = Outbox(scope, backoffMs = listOf(60_000L), now = { clock.get() })
        val attempts = AtomicInteger()
        repeat(Outbox.MAX_PER_PEER + 5) {
            ob.enqueue(Outbox.Item("bob", deliver = { attempts.incrementAndGet(); throw IOException("offline") }))
        }
        assertEquals(Outbox.MAX_PER_PEER, ob.size())
        waitUntil(what = "first attempt") { attempts.get() >= 1 }
        clock.addAndGet(Outbox.MAX_AGE_MS + 1)
        ob.kick("bob")
        waitUntil(1_000, "expired items dropped") { ob.size() == 0 }
    }
}
