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
    /** Orange unread dot: something arrived while invisible, not yet viewed. */
    val unread: Boolean = false,
    /** Blue Buzz dot: a buzz arrived; cleared when the conversation is opened. */
    val buzzed: Boolean = false,
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
            t.copy(messages = t.messages.mapNotNull { m ->
                when {
                    m.id != msgId -> m
                    // View-once (single message): the sender's own copy is removed
                    // once it's on its way — best-effort "removed on sender's side
                    // after send". There's nothing to show afterwards.
                    state == MsgState.SENT && m.selfTimer == SelfTimer.VIEW_ONCE -> null
                    else -> m.copy(state = state)
                }
            })
        }
    }

    /**
     * View-once burn: drop every INCOMING view-once message that has already been
     * seen. Called when leaving a chat, so a single-view message the recipient has
     * now read is gone and never shown again. (Outgoing view-once copies are
     * removed on send; see [setState].)
     */
    fun burnViewOnce(chatId: String) = update(chatId) { t ->
        t.copy(messages = t.messages.filterNot {
            !it.mine && it.selfTimer == SelfTimer.VIEW_ONCE && it.seenAt != null
        })
    }

    fun erase(chatId: String) = update(chatId) { ChatThread(teamHour = it.teamHour) }

    fun touchPeer(chatId: String) = update(chatId) { it.copy(peerLastSeen = System.currentTimeMillis()) }

    /** A Buzz arrived from this friend (blue dot until the chat is opened). */
    fun markBuzzed(chatId: String) = update(chatId) { it.copy(buzzed = true) }

    /** Opening the conversation clears the one-time Buzz marker. */
    fun clearBuzzed(chatId: String) = update(chatId) { if (it.buzzed) it.copy(buzzed = false) else it }

    /**
     * Add a small RED timestamped system line, e.g. "Decoy chat tripped." It is
     * never self-destructed and deletes nothing — the friend's history stays
     * until they clear it themselves. Marks the chat unread (orange dot).
     */
    fun addAlert(chatId: String, text: String, at: Long = System.currentTimeMillis()) = update(chatId) {
        it.copy(
            messages = it.messages + ChatMessage(newId(), mine = false, text = text,
                state = MsgState.SENT, createdAt = at, system = true, alert = true),
            unread = true,
        )
    }

    /** Seed/set the Team clock without a system message (used when loading it). */
    fun setTeamHourValue(chatId: String, value: String?) = update(chatId) { it.copy(teamHour = value) }

    /** A Team Clock change (from the friend, or mine): set it + a grey system line. */
    fun setTeamHour(chatId: String, value: String?, byName: String) = update(chatId) {
        val what = TeamClock.decode(value)?.let { "set the Team Clock to ${TeamClock.label(it)}" }
            ?: "turned the Team Clock off"
        it.copy(
            teamHour = value,
            messages = it.messages + ChatMessage(
                newId(), mine = false, text = "$byName $what",
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
