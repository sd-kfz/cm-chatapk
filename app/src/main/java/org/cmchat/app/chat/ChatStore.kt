package org.cmchat.app.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    /** The friend's decoy was triggered: this chat is erased once I leave it. */
    val decoyErase: Boolean = false,
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

    // Writes come from the UI AND from network threads (incoming frames, the
    // outbox marking a message sent), so every write is an atomic compare-and-set
    // — two at once can never lose a message.
    private fun update(chatId: String, f: (ChatThread) -> ChatThread) {
        _threads.update { m -> m.toMutableMap().also { it[chatId] = f(it[chatId] ?: ChatThread()) } }
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
        _threads.update { m -> m.mapValues { (_, t) ->
            t.copy(messages = t.messages.map {
                if (it.missed && it.seenAt == null) it.copy(seenAt = now) else it
            })
        } }
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
     * Add a small alert line (italic-bold) where the next message would be. It
     * is never self-destructed. Marks the chat unread (orange dot).
     */
    fun addAlert(chatId: String, text: String, at: Long = System.currentTimeMillis()) = update(chatId) {
        it.copy(messages = it.messages + alertLine(text, at), unread = true)
    }

    private fun alertLine(text: String, at: Long) = ChatMessage(newId(), mine = false, text = text,
        state = MsgState.SENT, createdAt = at, system = true, alert = true)

    /** Text of the friend-side decoy notice. */
    const val DECOY_NOTICE = "Decoy chat triggered — chat erased."

    /**
     * A friend's decoy was triggered: their copy of this chat is NOT destroyed
     * instantly. The notice line is shown where the next message would be, and
     * the whole chat is erased once the user leaves it ([leaveChat]).
     */
    fun addDecoyNotice(chatId: String, at: Long = System.currentTimeMillis()) = update(chatId) {
        // ONE atomic change: the line never shows without its "erase on leave" flag.
        it.copy(messages = it.messages + alertLine(DECOY_NOTICE, at), unread = true, decoyErase = true)
    }

    /** Leaving a chat: burn seen view-once messages; erase it if a decoy notice was shown. */
    fun leaveChat(chatId: String) {
        burnViewOnce(chatId)
        if (thread(chatId).decoyErase) erase(chatId)
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
        _threads.update { m -> m.mapValues { (_, t) ->
            t.copy(messages = t.messages.filterNot {
                SelfTimerRules.isExpired(it.seenAt, it.selfTimer, now)
            })
        } }
    }

    /**
     * The friend moved to a new address (new cmId, same identity key — e.g.
     * after their decoy fired): move the conversation to the new key so it stays
     * on screen, merged with anything that already arrived under the new one.
     */
    fun rekey(oldId: String, newId: String) {
        if (oldId == newId) return
        _threads.update { m ->
            val old = m[oldId] ?: return@update m
            val cur = m[newId]
            val merged = if (cur == null) old else cur.copy(
                messages = old.messages + cur.messages,
                teamHour = cur.teamHour ?: old.teamHour,
                peerLastSeen = listOfNotNull(old.peerLastSeen, cur.peerLastSeen).maxOrNull(),
                unread = old.unread || cur.unread,
                buzzed = old.buzzed || cur.buzzed,
                decoyErase = old.decoyErase || cur.decoyErase,
            )
            m - oldId + (newId to merged)
        }
    }

    /** Full wipe of all RAM chat state (Cerberus / Kill / logout). */
    fun clearAll() {
        _threads.value = emptyMap()
    }
}
