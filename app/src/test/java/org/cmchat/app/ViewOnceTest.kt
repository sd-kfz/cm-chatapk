package org.cmchat.app

import org.cmchat.app.chat.ChatStore
import org.cmchat.app.chat.MsgState
import org.cmchat.app.chat.SelfTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Proves true view-once (burn-after-first-view) at the store level:
 *  - the sender's own copy is removed the moment it's marked SENT;
 *  - a recipient's view-once copy that has been SEEN is burned on leaving the
 *    chat, while one not yet seen (arrived while Invisible) survives;
 *  - a normal (non view-once) message is never touched by either path.
 */
class ViewOnceTest {

    private val chat = "contact-1"

    @Before fun reset() = ChatStore.clearAll()

    private fun msgs() = ChatStore.thread(chat).messages

    @Test
    fun sender_copy_is_removed_once_sent() {
        val m = ChatStore.addMine(chat, "secret", SelfTimer.VIEW_ONCE)
        assertEquals(1, msgs().size)                 // visible while SENDING
        ChatStore.setState(chat, m.id, MsgState.SENT)
        assertTrue("view-once sender copy must be gone after send", msgs().isEmpty())
    }

    @Test
    fun sender_copy_stays_until_delivered() {
        ChatStore.addMine(chat, "secret", SelfTimer.VIEW_ONCE)
        // Still queued in the silent outbox (friend unreachable) -> still shown
        // as SENDING, never burned, and nothing says "offline".
        ChatStore.burnViewOnce(chat)
        assertEquals(1, msgs().size)
        assertEquals(MsgState.SENDING, msgs().single().state)
    }

    @Test
    fun recipient_seen_copy_burns_on_leaving_chat() {
        // Arrived while Online -> seen immediately.
        ChatStore.addTheirs(chat, "id-1", "hi", SelfTimer.VIEW_ONCE, missed = false)
        assertEquals(1, msgs().size)
        ChatStore.burnViewOnce(chat)
        assertTrue("seen view-once must burn on leaving", msgs().isEmpty())
    }

    @Test
    fun recipient_unseen_copy_survives_until_seen() {
        // Arrived while Invisible -> missed, not yet seen.
        ChatStore.addTheirs(chat, "id-1", "hi", SelfTimer.VIEW_ONCE, missed = true)
        ChatStore.burnViewOnce(chat)
        assertEquals("unseen view-once must NOT burn yet", 1, msgs().size)
        // Going Online delivers missed messages (seen); now it burns.
        ChatStore.deliverMissed()
        ChatStore.burnViewOnce(chat)
        assertTrue(msgs().isEmpty())
    }

    @Test
    fun normal_messages_are_never_burned() {
        val mine = ChatStore.addMine(chat, "keep", SelfTimer.OFF)
        ChatStore.setState(chat, mine.id, MsgState.SENT)
        ChatStore.addTheirs(chat, "id-2", "keep too", SelfTimer.S30, missed = false)
        ChatStore.burnViewOnce(chat)
        assertFalse(msgs().isEmpty())
        assertEquals(2, msgs().size)
    }
}
