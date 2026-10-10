package org.cmchat.app

import org.cmchat.app.chat.ChatStore
import org.cmchat.app.chat.MsgState
import org.cmchat.app.chat.SelfTimer
import org.cmchat.app.chat.TeamClock
import org.cmchat.app.transport.MessageService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** v1.2: Team Clock (pure logic), decoy alert-not-destroy, and the Buzz marker. */
class TeamClockDecoyBuzzTest {

    private val chat = "friend-1"

    @Before fun reset() = ChatStore.clearAll()

    // ---- Team Clock ------------------------------------------------------------

    @Test
    fun team_clock_encodes_and_decodes_every_valid_offset() {
        var v = TeamClock.MIN_OFFSET
        while (v <= TeamClock.MAX_OFFSET) {
            assertEquals(v, TeamClock.decode(TeamClock.encode(v)))
            v += TeamClock.STEP
        }
        assertEquals("UTC+05:45", TeamClock.encode(5 * 60 + 45))
        assertEquals("UTC-03:30", TeamClock.encode(-(3 * 60 + 30)))
        assertEquals("UTC+00:00", TeamClock.encode(0))
    }

    @Test
    fun team_clock_rejects_anything_not_exactly_canonical() {
        for (bad in listOf(null, "", "UTC", "utc+02:00", " UTC+02:00", "UTC+02:00 ", "UTC+2",
            "UTC+15:00", "UTC-13:00", "UTC+02:60", "GMT+02:00", "UTC+02:00;rm -rf /", "Team at noon")) {
            assertNull("must reject: $bad", TeamClock.decode(bad))
        }
    }

    @Test
    fun setting_the_clock_like_an_alarm_shows_exactly_that_time() {
        val t = 1_700_000_000_000L + 13 * 60_000L          // any instant
        for ((h, m) in listOf(0 to 0, 4 to 30, 12 to 0, 16 to 7, 23 to 59)) {
            val off = TeamClock.offsetFor(h, m, t)
            assertTrue("offset in range", TeamClock.isValid(off))
            assertEquals(h to m, TeamClock.hourMinuteAt(t, off))
            assertEquals(off, TeamClock.decode(TeamClock.encode(off)))   // survives the wire
        }
        assertEquals("4:07 PM", org.cmchat.app.chat.formatHm12(16, 7))
        assertEquals("12:00 AM", org.cmchat.app.chat.formatHm12(0, 0))
        assertEquals("12:30 PM", org.cmchat.app.chat.formatHm12(12, 30))
    }

    @Test
    fun team_time_is_pure_arithmetic_and_wraps_midnight() {
        val t = 1_700_000_000_000L          // 2023-11-14 22:13:20 UTC
        assertEquals("22:13", TeamClock.timeAt(t, 0))
        assertEquals("00:13", TeamClock.timeAt(t, 120))      // +2h wraps past midnight
        assertEquals("17:13", TeamClock.timeAt(t, -300))     // -5h
        assertEquals("03:58", TeamClock.timeAt(t, 345))      // +5:45
        assertEquals("UTC+5:45", TeamClock.label(345))
        assertEquals("UTC−3", TeamClock.label(-180))
        assertEquals("UTC", TeamClock.label(0))
    }

    @Test
    fun setting_the_team_clock_leaves_a_line_in_the_chat() {
        val before = TeamClock.time12(System.currentTimeMillis(), 120)
        ChatStore.setTeamHour(chat, "UTC+02:00", "Alice")
        val after = TeamClock.time12(System.currentTimeMillis(), 120)
        val t = ChatStore.thread(chat)
        assertEquals("UTC+02:00", t.teamHour)
        // No time zones on screen: the line shows the team time itself.
        assertTrue(t.messages.last().text in setOf(
            "Alice set the Team Clock to $before", "Alice set the Team Clock to $after"))
        assertFalse(t.messages.last().text.contains("UTC"))
        ChatStore.setTeamHour(chat, null, "Alice")
        assertNull(ChatStore.thread(chat).teamHour)
        assertEquals("Alice turned the Team Clock off", ChatStore.thread(chat).messages.last().text)
    }

    // ---- Decoy: alert, don't destroy -------------------------------------------

    @Test
    fun a_decoy_alert_keeps_the_friends_history_and_adds_a_red_line() {
        ChatStore.addTheirs(chat, "m1", "earlier message", SelfTimer.OFF)
        ChatStore.addMine(chat, "my reply", SelfTimer.OFF).also { ChatStore.setState(chat, it.id, MsgState.SENT) }
        ChatStore.addAlert(chat, "Decoy chat tripped.", at = 1_000L)
        val msgs = ChatStore.thread(chat).messages
        assertEquals("history is untouched", 3, msgs.size)
        assertEquals("earlier message", msgs[0].text)
        assertTrue(msgs.last().alert && msgs.last().system)
        assertEquals("Decoy chat tripped.", msgs.last().text)
        assertEquals(1_000L, msgs.last().createdAt)
        assertTrue("alert marks the chat unread", ChatStore.thread(chat).unread)
        // Alerts never self-destruct.
        ChatStore.purgeExpired(Long.MAX_VALUE / 2)
        assertTrue(ChatStore.thread(chat).messages.any { it.alert })
    }

    @Test
    fun tripping_the_decoy_wipes_MY_side_from_ram() {
        ChatStore.addTheirs(chat, "m1", "secret", SelfTimer.OFF)
        ChatStore.addTheirs("friend-2", "m2", "also secret", SelfTimer.OFF)
        MessageService.tripDecoy()          // engine not configured: local wipe only
        assertTrue(ChatStore.threads.value.isEmpty())
    }

    // ---- Buzz marker -------------------------------------------------------------

    @Test
    fun a_buzz_leaves_a_blue_marker_until_the_chat_is_opened() {
        assertFalse(ChatStore.thread(chat).buzzed)
        ChatStore.markBuzzed(chat)
        assertTrue(ChatStore.thread(chat).buzzed)
        assertFalse("separate from the orange unread dot", ChatStore.thread(chat).unread)
        ChatStore.clearBuzzed(chat)
        assertFalse(ChatStore.thread(chat).buzzed)
    }

    @Test
    fun opening_the_conversation_rearms_once_only_buzzes() {
        val p = org.cmchat.app.buzz.BuzzPolicy
        p.clear()
        p.frequency.value = org.cmchat.app.buzz.BuzzFrequency.ONCE
        assertTrue(p.accept(chat, 0))
        assertFalse("second buzz refused in Once-only mode", p.accept(chat, 1))
        p.onOpenedConversation(chat)
        assertTrue("opening the chat clears the one-time buzz", p.accept(chat, 2))
        p.frequency.value = org.cmchat.app.buzz.BuzzFrequency.H1
        p.clear()
    }
}
