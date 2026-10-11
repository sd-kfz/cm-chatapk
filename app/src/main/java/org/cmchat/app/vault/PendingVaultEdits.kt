package org.cmchat.app.vault

import java.util.concurrent.ConcurrentHashMap


object PendingVaultEdits {
    private val lock = Any()
    private val confirms = HashSet<String>()
    private val relinks = HashMap<String, String>()
    private val teamClock = HashMap<String, Pair<String, Long>>()
    private val teamClockSynced = HashMap<String, Long>()
    private val theirNames = HashMap<String, String>()
    private val namesConfirmed = HashMap<String, String>()
    private val removedBy = HashSet<String>()
    private val terminationsDone = HashSet<String>()
    private val lastSeen = HashMap<String, Long>()
    private val addrConfirmed = HashMap<String, String>()

    const val HOUR_MS = 60 * 60_000L
    const val LAST_SEEN_KEEP_MS = 24 * HOUR_MS
    const val TERMINATION_GIVE_UP_MS = 7 * 24 * HOUR_MS

    fun confirmed(cmId: String) = synchronized(lock) { confirms += cmId; Unit }
    fun relinked(old: String, new: String) = synchronized(lock) { relinks[old] = new }

    fun teamClockSet(cmId: String, value: String?, atMs: Long) = synchronized(lock) {
        val cur = teamClock[cmId]
        if (cur == null || atMs > cur.second) teamClock[cmId] = (value ?: "") to atMs
    }
    fun teamClockSynced(cmId: String, atMs: Long) = synchronized(lock) {
        teamClockSynced[cmId] = maxOf(atMs, teamClockSynced[cmId] ?: 0L)
    }
    fun theirName(cmId: String, name: String) = synchronized(lock) { theirNames[cmId] = name }
    fun nameConfirmed(cmId: String, name: String) = synchronized(lock) { namesConfirmed[cmId] = name }
    fun removedByFriend(cmId: String) = synchronized(lock) { removedBy += cmId; Unit }
    fun terminationDelivered(cmId: String) = synchronized(lock) { terminationsDone += cmId; Unit }
    fun seen(cmId: String, atMs: Long) = synchronized(lock) {
        lastSeen[cmId] = maxOf(atMs, lastSeen[cmId] ?: 0L)
    }
    fun addressConfirmed(friendCmId: String, myCmId: String) = synchronized(lock) { addrConfirmed[friendCmId] = myCmId }

    fun isEmpty(): Boolean = synchronized(lock) {
        confirms.isEmpty() && relinks.isEmpty() && teamClock.isEmpty() && removedBy.isEmpty() &&
            terminationsDone.isEmpty() && lastSeen.isEmpty() && addrConfirmed.isEmpty() &&
            teamClockSynced.isEmpty() && theirNames.isEmpty() && namesConfirmed.isEmpty()
    }


    fun clear() = synchronized(lock) {
        confirms.clear(); relinks.clear(); teamClock.clear(); removedBy.clear()
        terminationsDone.clear(); lastSeen.clear(); addrConfirmed.clear()
        teamClockSynced.clear(); theirNames.clear(); namesConfirmed.clear()
    }


    fun drainInto(d: VaultData, resolve: (String) -> String, nowMs: Long): VaultData = synchronized(lock) {
        fun matches(id: String?, set: Collection<String>) =
            id != null && set.any { it == id || resolve(it) == id }
        var contacts = d.contacts.map { c0 ->
            var c = c0

            c.cmId?.let { id -> resolve(id).takeIf { it != id } }?.let { c = c.copy(cmId = it) }
            val id = c.cmId
            if (matches(id, confirms)) c = c.copy(pending = false)

            id?.let { i -> teamClock.entries.firstOrNull { it.key == i || resolve(it.key) == i }?.value }
                ?.let { (v, at) -> if (at > c.teamHourAt) c = c.copy(teamHour = v.ifEmpty { null }, teamHourAt = at, teamHourSynced = true) }

            id?.let { i -> teamClockSynced.entries.firstOrNull { it.key == i || resolve(it.key) == i }?.value }
                ?.let { at -> if (at == c.teamHourAt) c = c.copy(teamHourSynced = true) }
            id?.let { i -> theirNames.entries.firstOrNull { it.key == i || resolve(it.key) == i }?.value }
                ?.let { c = c.copy(theirName = it) }
            id?.let { i -> namesConfirmed.entries.firstOrNull { it.key == i || resolve(it.key) == i }?.value }
                ?.let { c = c.copy(nameConfirmed = it) }
            id?.let { i -> addrConfirmed.entries.firstOrNull { it.key == i || resolve(it.key) == i }?.value }
                ?.let { c = c.copy(addrConfirmed = it) }
            id?.let { i -> lastSeen.entries.filter { it.key == i || resolve(it.key) == i }.maxOfOrNull { it.value } }
                ?.let { at ->
                    val hour = at / HOUR_MS * HOUR_MS
                    if (hour > (c.lastSeenAt ?: 0L)) c = c.copy(lastSeenAt = hour)
                }
            c.lastSeenAt?.let { if (nowMs - it > LAST_SEEN_KEEP_MS) c = c.copy(lastSeenAt = null) }
            c
        }
        contacts = contacts.filterNot { matches(it.cmId, removedBy) }
        val terminations = d.terminations.filterNot {
            it.cmId in terminationsDone || nowMs - it.sinceMs > TERMINATION_GIVE_UP_MS
        }
        confirms.clear(); relinks.clear(); teamClock.clear(); removedBy.clear()
        terminationsDone.clear(); lastSeen.clear(); addrConfirmed.clear()
        teamClockSynced.clear(); theirNames.clear(); namesConfirmed.clear()
        d.copy(contacts = contacts, terminations = terminations)
    }
}
