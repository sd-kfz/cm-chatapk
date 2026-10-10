package org.cmchat.app.vault

import java.util.concurrent.ConcurrentHashMap

/**
 * Changes that friends make to MY vault — they accepted me, moved address,
 * set our Team Clock, removed me (terminate), got my terminate, were last seen —
 * collected here and written in ONE vault save. While the app is locked there is
 * no PIN in RAM to save with, so they simply wait here (RAM only) until the next
 * unlock. Process-level, so a recreated screen doesn't lose them.
 */
object PendingVaultEdits {
    private val lock = Any()
    private val confirms = HashSet<String>()
    private val relinks = HashMap<String, String>()
    private val teamClock = HashMap<String, Pair<String, Long>>()   // cmId -> (value "" = off, setAt)
    private val teamClockSynced = HashMap<String, Long>()          // cmId -> my change (setAt) reached them
    private val theirNames = HashMap<String, String>()             // cmId -> their own nickname
    private val namesConfirmed = HashMap<String, String>()         // cmId -> my nickname they have
    private val removedBy = HashSet<String>()             // they terminated me
    private val terminationsDone = HashSet<String>()      // my terminate reached them
    private val lastSeen = HashMap<String, Long>()
    private val addrConfirmed = HashMap<String, String>()  // friend cmId -> my cmId they have

    const val HOUR_MS = 60 * 60_000L
    const val LAST_SEEN_KEEP_MS = 24 * HOUR_MS
    const val TERMINATION_GIVE_UP_MS = 7 * 24 * HOUR_MS

    fun confirmed(cmId: String) = synchronized(lock) { confirms += cmId; Unit }
    fun relinked(old: String, new: String) = synchronized(lock) { relinks[old] = new }
    /** A friend set our Team Clock at [atMs] (newest wins when drained). */
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

    /** Wipe paths: forget everything waiting. */
    fun clear() = synchronized(lock) {
        confirms.clear(); relinks.clear(); teamClock.clear(); removedBy.clear()
        terminationsDone.clear(); lastSeen.clear(); addrConfirmed.clear()
        teamClockSynced.clear(); theirNames.clear(); namesConfirmed.clear()
    }

    /**
     * Apply (and remove) everything waiting to [d]. [resolve] maps an old cmId to
     * the friend's current one. Also drops stale data: "last seen" older than
     * 24 h, and terminations still undelivered after 7 days.
     */
    fun drainInto(d: VaultData, resolve: (String) -> String, nowMs: Long): VaultData = synchronized(lock) {
        fun matches(id: String?, set: Collection<String>) =
            id != null && set.any { it == id || resolve(it) == id }
        var contacts = d.contacts.map { c0 ->
            var c = c0
            // Moved address (followed through any chain of moves).
            c.cmId?.let { id -> resolve(id).takeIf { it != id } }?.let { c = c.copy(cmId = it) }
            val id = c.cmId
            if (matches(id, confirms)) c = c.copy(pending = false)
            // Theirs, newest wins against what's stored (mine included).
            id?.let { i -> teamClock.entries.firstOrNull { it.key == i || resolve(it.key) == i }?.value }
                ?.let { (v, at) -> if (at > c.teamHourAt) c = c.copy(teamHour = v.ifEmpty { null }, teamHourAt = at, teamHourSynced = true) }
            // My change reached them (only if it's still the one stored).
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
