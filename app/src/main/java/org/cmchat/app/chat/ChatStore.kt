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
    /** A NEW message is waiting (blue dot on the friend's row) — set by every
     * incoming message, cleared when the chat is viewed while Online. */
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

    fun addMine(chatId: String, text: String, timer: SelfTimer, file: ChatFile? = null): ChatMessage {
        val m = ChatMessage(newId(), mine = true, text = text, state = MsgState.SENDING, selfTimer = timer, file = file)
        update(chatId) { it.copy(messages = it.messages + m) }
        return m
    }

    /** A RECEIVED file's bytes exist only here: zero them the moment it leaves. */
    private fun wipeReceivedFiles(gone: Collection<ChatMessage>) =
        gone.forEach { if (!it.mine) it.file?.wipe() }

    /**
     * [at] = when it arrived (a message held while locked is added later, with
     * its real time); [closedMiss] = it arrived while the app was closed.
     */
    fun addTheirs(chatId: String, id: String, text: String, timer: SelfTimer, missed: Boolean = false,
                  at: Long? = null, closedMiss: Boolean = false, file: ChatFile? = null) {
        // A missed (invisible) message isn't "seen" yet, so its self-timer
        // doesn't start until the user goes Online and views it.
        val now = System.currentTimeMillis()
        val m = ChatMessage(id, mine = false, text = text, state = MsgState.SENT,
            selfTimer = timer, createdAt = at ?: now, seenAt = if (missed) null else now, missed = missed,
            closedMiss = closedMiss, file = file)
        // Every new message is unread until the chat is viewed while Online.
        update(chatId) { it.copy(messages = it.messages + m, unread = true) }
        touchPeer(chatId)
    }

    /** Clear the blue unread dot (chat viewed while Online). */
    fun markRead(chatId: String) = update(chatId) { it.copy(unread = false) }

    /**
     * Going Online: messages held while Invisible are DELIVERED — they're no
     * longer "Missed" (that label clears), they show in the chat as normal new
     * messages (still unread: blue dot until viewed), and their self-timers start.
     */
    fun deliverMissed(now: Long = System.currentTimeMillis()) {
        _threads.update { m -> m.mapValues { (_, t) ->
            if (t.messages.none { it.missed }) t
            else t.copy(unread = true, messages = t.messages.map {
                if (it.missed) it.copy(missed = false, seenAt = it.seenAt ?: now) else it
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
    fun burnViewOnce(chatId: String) {
        val burn = thread(chatId).messages.filter { !it.mine && it.selfTimer == SelfTimer.VIEW_ONCE && it.seenAt != null }
        if (burn.isEmpty()) return
        update(chatId) { t -> t.copy(messages = t.messages.filterNot { m -> burn.any { it === m } }) }
        wipeReceivedFiles(burn)
    }

    /** Erase the conversation. The Team Clock and "last seen" are not chat
     * content, so they stay. */
    fun erase(chatId: String) {
        val gone = thread(chatId).messages
        update(chatId) { ChatThread(teamHour = it.teamHour, peerLastSeen = it.peerLastSeen) }
        wipeReceivedFiles(gone)
    }

    /** The friend is gone (deleted / terminated): drop everything about them. */
    fun forget(chatId: String) {
        val gone = thread(chatId).messages
        _threads.update { it - chatId }
        wipeReceivedFiles(gone)
    }

    fun touchPeer(chatId: String, at: Long = System.currentTimeMillis()) =
        update(chatId) { it.copy(peerLastSeen = maxOf(at, it.peerLastSeen ?: 0L)) }

    /**
     * I'm Online with this chat on screen: everything in it is now SEEN — the
     * blue dot and every "Missed Message" mark clear (and seen-based timers run).
     */
    fun markSeen(chatId: String, now: Long = System.currentTimeMillis()) = update(chatId) { t ->
        if (!t.unread && t.messages.none { it.missed }) t
        else t.copy(unread = false, messages = t.messages.map {
            if (it.missed) it.copy(missed = false, seenAt = it.seenAt ?: now) else it
        })
    }

    /** A plain grey system notice (centred, no bubble). Not chat content. */
    fun addSystemLine(chatId: String, text: String, at: Long = System.currentTimeMillis()) = update(chatId) {
        it.copy(messages = it.messages + ChatMessage(newId(), mine = false, text = text,
            state = MsgState.SENT, createdAt = at, system = true))
    }

    /** A Buzz arrived from this friend (blue dot until the chat is opened). */
    fun markBuzzed(chatId: String) = update(chatId) { it.copy(buzzed = true) }

    /** Opening the conversation clears the one-time Buzz marker. */
    fun clearBuzzed(chatId: String) = update(chatId) { if (it.buzzed) it.copy(buzzed = false) else it }

    /**
     * Add a small alert line (italic-bold) where the next message would be. It
     * is never self-destructed. Marks the chat unread (blue dot).
     */
    fun addAlert(chatId: String, text: String, at: Long = System.currentTimeMillis()) = update(chatId) {
        it.copy(messages = it.messages + alertLine(text, at), unread = true)
    }

    private fun alertLine(text: String, at: Long) = ChatMessage(newId(), mine = false, text = text,
        state = MsgState.SENT, createdAt = at, system = true, alert = true)

    /** Text of the friend-side decoy notice. */
    const val DECOY_NOTICE = "Decoy chat triggered — chat erased."

    /**
     * A friend's decoy was triggered — their BURN signal: our conversation is
     * wiped on this phone right away; only the notice line remains, and it goes
     * too once the user leaves the chat ([leaveChat]).
     */
    fun addDecoyNotice(chatId: String, at: Long = System.currentTimeMillis()) {
        val gone = thread(chatId).messages
        update(chatId) {
            // ONE atomic change: the chat is gone and the line never shows without
            // its "erase on leave" flag.
            ChatThread(teamHour = it.teamHour, peerLastSeen = it.peerLastSeen,
                messages = listOf(alertLine(DECOY_NOTICE, at)), unread = true, decoyErase = true)
        }
        wipeReceivedFiles(gone)
    }

    /** Leaving a chat: burn seen view-once messages; erase it if a decoy notice was
     * shown; messages that arrived while the app was closed lose their "Missed"
     * mark once they've been seen (Online). */
    fun leaveChat(chatId: String) {
        burnViewOnce(chatId)
        if (thread(chatId).decoyErase) { erase(chatId); return }
        update(chatId) { t ->
            if (t.messages.none { it.closedMiss && it.seenAt != null }) t
            else t.copy(messages = t.messages.map { if (it.closedMiss && it.seenAt != null) it.copy(closedMiss = false) else it })
        }
    }

    /** Seed/set the Team clock without a system message (used when loading it). */
    fun setTeamHourValue(chatId: String, value: String?) = update(chatId) { it.copy(teamHour = value) }

    /** A Team Clock change (from the friend, or mine): set it + a grey system line. */
    fun setTeamHour(chatId: String, value: String?, byName: String) = update(chatId) {
        val what = TeamClock.decode(value)?.let { "set the Team Clock to ${TeamClock.time12(System.currentTimeMillis(), it)}" }
            ?: "turned the Team Clock off"
        it.copy(
            teamHour = value,
            messages = it.messages + ChatMessage(
                newId(), mine = false, text = "$byName $what",
                state = MsgState.SENT, system = true,
            ),
        )
    }

    /** Drop self-timer-expired messages across all threads. (Runs every second:
     * nothing changes — and nothing is redrawn — unless something expired.) */
    fun purgeExpired(now: Long = System.currentTimeMillis()) {
        val expired = _threads.value.values.flatMap { t ->
            t.messages.filter { SelfTimerRules.isExpired(it.seenAt, it.selfTimer, now) }
        }
        if (expired.isEmpty()) return
        _threads.update { m ->
            if (m.values.none { t -> t.messages.any { SelfTimerRules.isExpired(it.seenAt, it.selfTimer, now) } }) m
            else m.mapValues { (_, t) ->
                t.copy(messages = t.messages.filterNot { SelfTimerRules.isExpired(it.seenAt, it.selfTimer, now) })
            }
        }
        wipeReceivedFiles(expired)
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
        val gone = _threads.value.values.flatMap { it.messages }
        _threads.value = emptyMap()
        wipeReceivedFiles(gone)
    }
}
