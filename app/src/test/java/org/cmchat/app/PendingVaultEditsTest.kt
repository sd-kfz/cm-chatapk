package org.cmchat.app

import org.cmchat.app.vault.ContactRec
import org.cmchat.app.vault.PendingTermination
import org.cmchat.app.vault.PendingVaultEdits
import org.cmchat.app.vault.VaultData
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What friends change while my app is LOCKED waits in RAM and lands in ONE
 * vault save after unlock: acceptance, new address, Team Clock, "they removed
 * me", "my terminate arrived", coarse last seen — and stale data is dropped.
 */
class PendingVaultEditsTest {

    private val hour = PendingVaultEdits.HOUR_MS
    private fun c(id: String, cmId: String, pending: Boolean = false, seen: Long? = null) =
        ContactRec(id = id, name = id, colorArgb = 0, faceId = "f", cmId = cmId, pending = pending, lastSeenAt = seen)

    @Before @After fun reset() = PendingVaultEdits.clear()

    @Test
    fun everything_buffered_lands_in_one_save() {
        val now = 1_700_000_000_000L
        val d = VaultData(
            contacts = listOf(c("bob", "B-old", pending = true), c("carol", "C"), c("dave", "D")),
            terminations = listOf(PendingTermination("E", now - hour), PendingTermination("F", now - 8 * 24 * hour)),
        )
        val moved = mapOf("B-old" to "B-new")
        PendingVaultEdits.relinked("B-old", "B-new")
        PendingVaultEdits.confirmed("B-old")                 // accepted, then moved
        PendingVaultEdits.teamClockSet("C", "UTC+02:07", now - 60_000L)
        PendingVaultEdits.removedByFriend("D")               // Dave terminated me
        PendingVaultEdits.terminationDelivered("E")
        PendingVaultEdits.seen("C", now - 5 * 60_000L)
        assertFalse(PendingVaultEdits.isEmpty())

        val out = PendingVaultEdits.drainInto(d, { moved[it] ?: it }, now)
        val bob = out.contacts.single { it.id == "bob" }
        assertEquals("B-new", bob.cmId)
        assertFalse("acceptance applied across the move", bob.pending)
        val carol = out.contacts.single { it.id == "carol" }
        assertEquals("UTC+02:07", carol.teamHour)
        assertEquals("last seen kept only to the hour", (now - 5 * 60_000L) / hour * hour, carol.lastSeenAt)
        assertTrue("Dave removed me, so he's gone", out.contacts.none { it.id == "dave" })
        assertTrue("delivered + too-old terminations dropped", out.terminations.isEmpty())
        assertTrue("drained", PendingVaultEdits.isEmpty())
    }

    @Test
    fun last_seen_older_than_a_day_is_forgotten() {
        val now = 1_700_000_000_000L
        val d = VaultData(contacts = listOf(c("bob", "B", seen = now - 25 * hour)))
        PendingVaultEdits.teamClockSet("B", null, now)     // any pending change triggers a pass
        val out = PendingVaultEdits.drainInto(d, { it }, now)
        assertNull(out.contacts.single().lastSeenAt)
        assertNull(out.contacts.single().teamHour)
    }

    /** J2: the Team Clock is newest-wins, and MY change stays "unsynced" until it reaches them. */
    @Test
    fun team_clock_newest_wins_and_my_change_waits_for_their_confirmation() {
        val now = 1_700_000_000_000L
        val mine = c("bob", "B").copy(teamHour = "UTC+01:00", teamHourAt = now, teamHourSynced = false)
        // An OLDER change of theirs (crossed with mine) loses…
        PendingVaultEdits.teamClockSet("B", "UTC+05:00", now - 1)
        var out = PendingVaultEdits.drainInto(VaultData(contacts = listOf(mine)), { it }, now)
        assertEquals("UTC+01:00", out.contacts.single().teamHour)
        assertFalse("mine still has to reach them", out.contacts.single().teamHourSynced)
        // …their confirmation of MY change marks it synced…
        PendingVaultEdits.teamClockSynced("B", now)
        out = PendingVaultEdits.drainInto(out, { it }, now)
        assertTrue(out.contacts.single().teamHourSynced)
        // …and a NEWER change of theirs wins.
        PendingVaultEdits.teamClockSet("B", "", now + 5)
        out = PendingVaultEdits.drainInto(out, { it }, now)
        assertNull("turned off by them, later", out.contacts.single().teamHour)
        assertEquals(now + 5, out.contacts.single().teamHourAt)
    }

    /** I1: their own nickname lands in the vault; my label for them is untouched. */
    @Test
    fun their_own_nickname_is_kept_next_to_my_label() {
        val now = 1_700_000_000_000L
        PendingVaultEdits.theirName("B", "Robert")
        val out = PendingVaultEdits.drainInto(VaultData(contacts = listOf(c("bob", "B"))), { it }, now)
        assertEquals("Robert", out.contacts.single().theirName)
        assertEquals("my label stays", "bob", out.contacts.single().name)
    }
}
