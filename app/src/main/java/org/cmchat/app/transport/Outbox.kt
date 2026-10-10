package org.cmchat.app.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * RAM-only outgoing queue with SILENT background retry.
 *
 * There is no "Offline / Retry" button any more: showing whether a send failed
 * would tell the sender whether the friend is online. Instead every outgoing
 * frame (message, knock, accept, erase, decoy alert, address / Team Clock
 * update) goes in here and is retried quietly with backoff until it's delivered
 * — the screen never changes either way.
 *
 *  - One worker per friend, strictly in order (a later message never overtakes
 *    an earlier one; while a friend is unreachable their queue just waits).
 *  - [Item.stillWanted] is checked before every attempt, so anything wiped from
 *    the chat (Erase, decoy, Exit, Cerberus…) is dropped and never sent.
 *  - A newer item with the same [Item.replaceKey] replaces a queued older one
 *    (e.g. only the latest Team Clock or address update is sent).
 *  - Bounded: at most [MAX_PER_PEER] items per friend, each kept at most
 *    [MAX_AGE_MS]. Nothing is ever written to disk; [clear] drops everything.
 */
class Outbox(
    private val scope: CoroutineScope,
    private val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** Waits after the 1st, 2nd, … failure; the last value repeats. */
        val DEFAULT_BACKOFF_MS = listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L)
        const val MAX_PER_PEER = 200
        const val MAX_AGE_MS = 24 * 60 * 60_000L
    }

    class Item(
        /** Per-friend FIFO key (the friend's cmId). */
        val peer: String,
        /** Send it. Return normally = delivered (their phone confirmed it is
         * stored); throw = not yet (retried later); throw [GiveUp] = their phone
         * refused it for good (dropped, never retried). */
        val deliver: () -> Unit,
        val onDelivered: () -> Unit = {},
        val stillWanted: () -> Boolean = { true },
        val replaceKey: String? = null,
        /** A short, content-free label for the Connection log ("message", "knock"…). */
        val label: String = "frame",
        /** Carries no chat content (address update, acceptance, terminate,
         * knock): kept when the app is closed, so the friendship keeps working. */
        val keepOnClose: Boolean = false,
        /** What this item carries, for [has] (e.g. which address an update sends). */
        val tag: Any? = null,
    ) {
        internal var createdAt = 0L
    }

    /** Thrown by [Item.deliver]: their phone REFUSED it (not "try later"). */
    class GiveUp(message: String) : Exception(message)

    private val lock = Any()
    private val queues = HashMap<String, ArrayDeque<Item>>()
    private val workers = HashMap<String, Job>()
    /** Per-friend "try again now" signal (bumped by [kick] / [kickAll]). */
    private val pokes = HashMap<String, MutableStateFlow<Long>>()

    fun enqueue(item: Item) {
        item.createdAt = now()
        synchronized(lock) {
            val q = queues.getOrPut(item.peer) { ArrayDeque() }
            item.replaceKey?.let { k -> q.removeAll { it.replaceKey == k } }
            while (q.size >= MAX_PER_PEER) q.removeFirst()
            q.addLast(item)
            if (workers[item.peer]?.isActive != true) {
                val poke = pokes.getOrPut(item.peer) { MutableStateFlow(0L) }
                workers[item.peer] = scope.launch { drain(item.peer, poke) }
            }
        }
    }

    /** Retry [peer]'s queue now (an authenticated frame just arrived from them). */
    fun kick(peer: String) { synchronized(lock) { pokes[peer] }?.update { it + 1 } }

    /** Retry every queue now (e.g. Tor just came back online). */
    fun kickAll() { synchronized(lock) { pokes.values.toList() }.forEach { p -> p.update { it + 1 } } }

    /** Drop everything (wipe paths). In-flight sends finish but report nothing. */
    fun clear() {
        synchronized(lock) {
            queues.clear()
            workers.values.forEach { it.cancel() }
            workers.clear()
            pokes.clear()
        }
    }

    /**
     * The app was closed: drop every queued item that carries chat content,
     * keep the ones the friendship itself depends on ([Item.keepOnClose]).
     */
    fun retainOnly(keep: (Item) -> Boolean) {
        synchronized(lock) {
            for (peer in queues.keys.toList()) {
                val q = queues[peer] ?: continue
                q.removeAll { !keep(it) }
                if (q.isEmpty()) {
                    queues.remove(peer)
                    workers.remove(peer)?.cancel()
                    pokes.remove(peer)
                }
            }
        }
    }

    /** Drop everything queued for ONE friend (deleted / cancelled). */
    fun clearPeer(peer: String) {
        synchronized(lock) {
            queues.remove(peer)
            workers.remove(peer)?.cancel()
            pokes.remove(peer)
        }
    }

    fun size(): Int = synchronized(lock) { queues.values.sumOf { it.size } }

    /** Is something matching [match] still queued for [peer]? */
    fun has(peer: String, match: (Item) -> Boolean): Boolean =
        synchronized(lock) { queues[peer]?.any(match) == true }

    private suspend fun drain(peer: String, poke: MutableStateFlow<Long>) {
        val me = currentCoroutineContext()[Job]
        var failures = 0
        while (true) {
            val head = synchronized(lock) {
                // Superseded (cleared, maybe with a fresh worker started since):
                // stop quietly — never two workers sending one friend's queue.
                if (workers[peer] !== me) return
                val q = queues[peer]
                if (q.isNullOrEmpty()) {
                    queues.remove(peer); workers.remove(peer); pokes.remove(peer); return
                }
                q.first()
            }
            if (!head.stillWanted()) { drop(peer, head); continue }
            if (now() - head.createdAt > MAX_AGE_MS) {
                org.cmchat.app.diag.ConnDiag.out("${head.label}: given up — not delivered within 24 h")
                drop(peer, head); continue
            }
            // Read BEFORE the attempt: a kick that lands while a (slow, Tor) attempt
            // is in flight still triggers an immediate retry if that attempt fails.
            val seen = poke.value
            val ok = try {
                head.deliver(); true
            } catch (e: CancellationException) {
                throw e
            } catch (e: GiveUp) {
                currentCoroutineContext().ensureActive()
                org.cmchat.app.diag.ConnDiag.out("${head.label}: refused by their phone (${e.message}) — not retried")
                drop(peer, head); failures = 0; continue
            } catch (_: Exception) {
                false
            }
            currentCoroutineContext().ensureActive()   // cleared meanwhile → report nothing
            if (ok) {
                drop(peer, head)
                runCatching { head.onDelivered() }
                failures = 0
                continue
            }
            val wait = backoffMs[minOf(failures, backoffMs.size - 1)]
            failures++
            org.cmchat.app.diag.ConnDiag.out(
                "${head.label}: not delivered yet (try $failures) — next try in ${wait / 1000}s or when they reappear")
            withTimeoutOrNull(wait) { poke.first { it != seen } }   // backoff, or until kicked
        }
    }

    private fun drop(peer: String, item: Item) = synchronized(lock) { queues[peer]?.remove(item) }
}
