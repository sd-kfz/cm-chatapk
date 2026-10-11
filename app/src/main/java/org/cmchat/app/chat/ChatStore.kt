package org.cmchat.app.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong


data class ChatThread(
    val messages: List<ChatMessage> = emptyList(),
    val teamHour: String? = null,
    val peerLastSeen: Long? = null,

    val unread: Boolean = false,

    val buzzed: Boolean = false,

    val decoyErase: Boolean = false,
)


object ChatStore {

    private val _threads = MutableStateFlow<Map<String, ChatThread>>(emptyMap())
    val threads: StateFlow<Map<String, ChatThread>> = _threads.asStateFlow()

    private val counter = AtomicLong(0)
    fun newId(): String = "${System.currentTimeMillis().toString(36)}-${counter.incrementAndGet()}"

    fun thread(chatId: String): ChatThread = _threads.value[chatId] ?: ChatThread()




    private fun update(chatId: String, f: (ChatThread) -> ChatThread) {
        _threads.update { m -> m.toMutableMap().also { it[chatId] = f(it[chatId] ?: ChatThread()) } }
    }

    fun addMine(chatId: String, text: String, timer: SelfTimer, file: ChatFile? = null): ChatMessage {
        val m = ChatMessage(newId(), mine = true, text = text, state = MsgState.SENDING, selfTimer = timer, file = file)
        update(chatId) { it.copy(messages = it.messages + m) }
        return m
    }


    private fun wipeReceivedFiles(gone: Collection<ChatMessage>) =
        gone.forEach { if (!it.mine) it.file?.wipe() }


    fun addTheirs(chatId: String, id: String, text: String, timer: SelfTimer, missed: Boolean = false,
                  at: Long? = null, closedMiss: Boolean = false, file: ChatFile? = null) {


        val now = System.currentTimeMillis()
        val m = ChatMessage(id, mine = false, text = text, state = MsgState.SENT,
            selfTimer = timer, createdAt = at ?: now, seenAt = if (missed) null else now, missed = missed,
            closedMiss = closedMiss, file = file)

        update(chatId) { it.copy(messages = it.messages + m, unread = true) }
        touchPeer(chatId)
    }


    fun markRead(chatId: String) = update(chatId) { it.copy(unread = false) }


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



                    state == MsgState.SENT && m.selfTimer == SelfTimer.VIEW_ONCE -> null
                    else -> m.copy(state = state)
                }
            })
        }
    }


    fun burnViewOnce(chatId: String) {
        val burn = thread(chatId).messages.filter { !it.mine && it.selfTimer == SelfTimer.VIEW_ONCE && it.seenAt != null }
        if (burn.isEmpty()) return
        update(chatId) { t -> t.copy(messages = t.messages.filterNot { m -> burn.any { it === m } }) }
        wipeReceivedFiles(burn)
    }


    fun erase(chatId: String) {
        val gone = thread(chatId).messages
        update(chatId) { ChatThread(teamHour = it.teamHour, peerLastSeen = it.peerLastSeen) }
        wipeReceivedFiles(gone)
    }


    fun forget(chatId: String) {
        val gone = thread(chatId).messages
        _threads.update { it - chatId }
        wipeReceivedFiles(gone)
    }

    fun touchPeer(chatId: String, at: Long = System.currentTimeMillis()) =
        update(chatId) { it.copy(peerLastSeen = maxOf(at, it.peerLastSeen ?: 0L)) }


    fun markSeen(chatId: String, now: Long = System.currentTimeMillis()) = update(chatId) { t ->
        if (!t.unread && t.messages.none { it.missed }) t
        else t.copy(unread = false, messages = t.messages.map {
            if (it.missed) it.copy(missed = false, seenAt = it.seenAt ?: now) else it
        })
    }


    fun addSystemLine(chatId: String, text: String, at: Long = System.currentTimeMillis()) = update(chatId) {
        it.copy(messages = it.messages + ChatMessage(newId(), mine = false, text = text,
            state = MsgState.SENT, createdAt = at, system = true))
    }


    fun markBuzzed(chatId: String) = update(chatId) { it.copy(buzzed = true) }


    fun clearBuzzed(chatId: String) = update(chatId) { if (it.buzzed) it.copy(buzzed = false) else it }


    fun addAlert(chatId: String, text: String, at: Long = System.currentTimeMillis()) = update(chatId) {
        it.copy(messages = it.messages + alertLine(text, at), unread = true)
    }

    private fun alertLine(text: String, at: Long, note: SystemNote? = null) = ChatMessage(newId(), mine = false,
        text = text, state = MsgState.SENT, createdAt = at, system = true, alert = true, note = note)


    const val DECOY_NOTICE = "Decoy chat triggered — chat erased."


    fun addDecoyNotice(chatId: String, at: Long = System.currentTimeMillis()) {
        val gone = thread(chatId).messages
        update(chatId) {


            ChatThread(teamHour = it.teamHour, peerLastSeen = it.peerLastSeen,
                messages = listOf(alertLine(DECOY_NOTICE, at, SystemNote.DecoyErased)), unread = true, decoyErase = true)
        }
        wipeReceivedFiles(gone)
    }


    fun leaveChat(chatId: String) {
        burnViewOnce(chatId)
        if (thread(chatId).decoyErase) { erase(chatId); return }
        update(chatId) { t ->
            if (t.messages.none { it.closedMiss && it.seenAt != null }) t
            else t.copy(messages = t.messages.map { if (it.closedMiss && it.seenAt != null) it.copy(closedMiss = false) else it })
        }
    }


    fun setTeamHourValue(chatId: String, value: String?) = update(chatId) { it.copy(teamHour = value) }


    fun setTeamHour(chatId: String, value: String?, byName: String?) = update(chatId) {
        val time = TeamClock.decode(value)?.let { off -> TeamClock.time12(System.currentTimeMillis(), off) }
        val what = time?.let { t -> "set the Team Clock to $t" } ?: "turned the Team Clock off"
        val who = byName?.ifEmpty { "Your friend" } ?: "You"
        it.copy(
            teamHour = value,
            messages = it.messages + ChatMessage(
                newId(), mine = false, text = "$who $what",
                state = MsgState.SENT, system = true, note = SystemNote.TeamClock(byName, time),
            ),
        )
    }


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


    fun clearAll() {
        val gone = _threads.value.values.flatMap { it.messages }
        _threads.value = emptyMap()
        wipeReceivedFiles(gone)
    }
}
