package org.cmchat.app.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/** One 1:1 conversation. Everything here is RAM-only and never persisted. */
data class ChatThread(
    val messages: List<ChatMessage> = emptyList(),
    val teamHour: String? = null,
    val peerLastSeen: Long? = null,
    val peerStatus: String? = null,
    val peerStatusColor: Long = 0,
    /** Orange unread dot: something arrived while invisible, not yet viewed. */
    val unread: Boolean = false,
)

/**
 * In-memory store of all conversations. Nothing is written to disk; the whole
 * map is dropped on a Cerberus/Kill wipe or process death. Keyed by contact id.
 */
object ChatStore {

    private val _threads = MutableStateFlow<Map<String, ChatThread>>(emptyMap())
    val threads: StateFlow<Map<String, ChatThread>> = _threads.asStateFlow()

    private val counter = AtomicLong(0)
    fun newId(): String = "${System.currentTimeMillis().toString(36)}-${counter.incrementAndGet()}"

    fun thread(chatId: String): ChatThread = _threads.value[chatId] ?: ChatThread()

    private fun update(chatId: String, f: (ChatThread) -> ChatThread) {
        _threads.value = _threads.value.toMutableMap().also { it[chatId] = f(it[chatId] ?: ChatThread()) }
    }

    fun addMine(chatId: String, text: String, timer: SelfTimer): ChatMessage {
        val m = ChatMessage(newId(), mine = true, text = text, state = MsgState.SENDING, selfTimer = timer)
        update(chatId) { it.copy(messages = it.messages + m) }
        return m
    }

    fun addTheirs(chatId: String, id: String, text: String, timer: SelfTimer, missed: Boolean = false) {
        // A missed (invisible) message isn't "seen" yet, so its self-timer
        // doesn't start until the user goes Online and views it.
        val m = ChatMessage(id, mine = false, text = text, state = MsgState.SENT,
            selfTimer = timer, seenAt = if (missed) null else System.currentTimeMillis(), missed = missed)
        update(chatId) { it.copy(messages = it.messages + m, unread = it.unread || missed) }
        touchPeer(chatId)
    }

    /** Clear the orange unread dot (chat viewed while Online). */
    fun markRead(chatId: String) = update(chatId) { it.copy(unread = false) }

    /** Going Online: start self-timers on missed messages now that they're seen. */
    fun markMissedSeen(now: Long = System.currentTimeMillis()) {
        _threads.value = _threads.value.mapValues { (_, t) ->
            t.copy(messages = t.messages.map {
                if (it.missed && it.seenAt == null) it.copy(seenAt = now) else it
            })
        }
    }

    fun setState(chatId: String, msgId: String, state: MsgState) {
        update(chatId) { t ->
            t.copy(messages = t.messages.map { if (it.id == msgId) it.copy(state = state) else it })
        }
    }

    fun erase(chatId: String) = update(chatId) { ChatThread(teamHour = it.teamHour) }

    fun touchPeer(chatId: String) = update(chatId) { it.copy(peerLastSeen = System.currentTimeMillis()) }

    fun setPeerStatus(chatId: String, word: String, colorArgb: Long) =
        update(chatId) { it.copy(peerStatus = word, peerStatusColor = colorArgb) }

    /** Seed/set the Team clock without a system message (used when loading it). */
    fun setTeamHourValue(chatId: String, value: String?) = update(chatId) { it.copy(teamHour = value) }

    fun setTeamHour(chatId: String, value: String, byName: String) = update(chatId) {
        it.copy(
            teamHour = value,
            messages = it.messages + ChatMessage(
                newId(), mine = false, text = "$byName modified Team Hour",
                state = MsgState.SENT, system = true,
            ),
        )
    }

    /** Drop self-timer-expired messages across all threads. */
    fun purgeExpired(now: Long = System.currentTimeMillis()) {
        _threads.value = _threads.value.mapValues { (_, t) ->
            t.copy(messages = t.messages.filterNot {
                SelfTimerRules.isExpired(it.seenAt, it.selfTimer, now)
            })
        }
    }

    /** Full wipe of all RAM chat state (Cerberus / Kill / logout). */
    fun clearAll() {
        _threads.value = emptyMap()
    }
}
