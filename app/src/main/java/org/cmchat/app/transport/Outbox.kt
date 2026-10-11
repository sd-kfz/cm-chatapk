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


class Outbox(
    private val scope: CoroutineScope,
    private val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object {

        val DEFAULT_BACKOFF_MS = listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L)
        const val MAX_PER_PEER = 200
        const val MAX_AGE_MS = 24 * 60 * 60_000L
    }

    class Item(

        val peer: String,

        val deliver: () -> Unit,
        val onDelivered: () -> Unit = {},
        val stillWanted: () -> Boolean = { true },
        val replaceKey: String? = null,

        val label: String = "frame",

        val keepOnClose: Boolean = false,

        val tag: Any? = null,
    ) {
        internal var createdAt = 0L
    }


    class GiveUp(message: String) : Exception(message)

    private val lock = Any()
    private val queues = HashMap<String, ArrayDeque<Item>>()
    private val workers = HashMap<String, Job>()

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


    fun kick(peer: String) { synchronized(lock) { pokes[peer] }?.update { it + 1 } }


    fun kickAll() { synchronized(lock) { pokes.values.toList() }.forEach { p -> p.update { it + 1 } } }


    fun isEmpty(): Boolean = synchronized(lock) { queues.values.all { it.isEmpty() } }


    fun clear() {
        synchronized(lock) {
            queues.clear()
            workers.values.forEach { it.cancel() }
            workers.clear()
            pokes.clear()
        }
    }


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


    fun clearPeer(peer: String) {
        synchronized(lock) {
            queues.remove(peer)
            workers.remove(peer)?.cancel()
            pokes.remove(peer)
        }
    }

    fun size(): Int = synchronized(lock) { queues.values.sumOf { it.size } }


    fun has(peer: String, match: (Item) -> Boolean): Boolean =
        synchronized(lock) { queues[peer]?.any(match) == true }

    private suspend fun drain(peer: String, poke: MutableStateFlow<Long>) {
        val me = currentCoroutineContext()[Job]
        var failures = 0
        while (true) {
            val head = synchronized(lock) {


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
            currentCoroutineContext().ensureActive()
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
            withTimeoutOrNull(wait) { poke.first { it != seen } }
        }
    }

    private fun drop(peer: String, item: Item) = synchronized(lock) { queues[peer]?.remove(item) }
}
