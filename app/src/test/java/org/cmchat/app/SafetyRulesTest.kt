package org.cmchat.app

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.cmchat.app.buzz.BuzzFrequency
import org.cmchat.app.buzz.BuzzPolicy
import org.cmchat.app.chat.ChatStore
import org.cmchat.app.chat.SelfTimer
import org.cmchat.app.crypto.CmId
import org.cmchat.app.crypto.CryptoManager
import org.cmchat.app.settings.AppSettings
import org.cmchat.app.tools.ToolsState
import org.cmchat.app.tor.ServerController
import org.cmchat.app.tor.ServerStatus
import org.cmchat.app.transport.MessageService
import org.cmchat.app.vault.VaultSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test


class SafetyRulesTest {

    @Before @After
    fun reset() {
        AppSettings.restoreFrom(VaultSettings())
        ChatStore.clearAll()
        ServerController.stoppedByUser.value = false
        ServerController.stop()
    }



    @Test
    fun saved_choices_round_trip_through_the_vault_settings() {
        val saved = VaultSettings(defaultSelfTimer = "5m", buzzFrequency = "H24",
            decoyEnabled = true, decoyName = "Bank", decoyAtTop = false,
            toolCalc = true, toolNotes = false, toolFlash = true)
        AppSettings.restoreFrom(saved)
        assertEquals(SelfTimer.M5, AppSettings.generalTimer.value)
        assertEquals(BuzzFrequency.H24, BuzzPolicy.frequency.value)
        assertTrue(AppSettings.decoyEnabled.value)
        assertEquals("Bank", AppSettings.decoyName.value)
        assertFalse(AppSettings.decoyAtTop.value)
        assertTrue(ToolsState.calcEnabled.value && !ToolsState.notesEnabled.value && ToolsState.flashlightEnabled.value)

        assertEquals(saved, AppSettings.applyTo(VaultSettings()))

        AppSettings.restoreFrom(VaultSettings(defaultSelfTimer = "view-once"))
        assertEquals(SelfTimer.OFF, AppSettings.generalTimer.value)
    }

    @Test
    fun any_change_to_a_saved_choice_is_announced_for_saving() = runBlocking {
        val seen = async { withTimeout(5_000) { AppSettings.savedChoices.take(2).toList() } }
        delay(100)
        AppSettings.decoyEnabled.value = true
        assertEquals(2, seen.await().size)
    }



    @Test
    fun the_engine_forgets_its_keys_and_friends_on_wipe() {
        val crypto = CryptoManager(LazySodiumJava(SodiumJava()))
        val (pub, sec) = crypto.newIdentityKeypair()
        val (fpub, _) = crypto.newIdentityKeypair()
        val me = CmId.encode("a".repeat(56) + ".onion", pub)
        val friend = CmId.encode("b".repeat(56) + ".onion", fpub)
        MessageService.configure(crypto, "Alice", pub, sec, me, listOf(friend))
        assertEquals(listOf(friend), MessageService.contactIds())
        MessageService.zeroKeys()
        assertTrue("friend table gone", MessageService.contactIds().isEmpty())
        val other = CmId.encode("c".repeat(56) + ".onion", crypto.newIdentityKeypair().first)
        assertEquals("no identity left to act with", MessageService.KnockResult.NOT_READY, MessageService.sendKnock(other))
    }



    @Test
    fun a_stopped_server_is_not_republished_by_saves_or_tor_coming_back() {
        ServerController.userStop()
        assertTrue(ServerController.stoppedByUser.value)

        ServerController.start("Alice", null, null) {}
        ServerController.pauseForTorRestart()
        Thread.sleep(300)
        assertEquals(ServerStatus.Off, ServerController.status.value)
        assertFalse("Restart can't sneak it back", ServerController.restart("Alice", null, null) {})

        ServerController.userStart("Alice", null, null) {}
        assertFalse(ServerController.stoppedByUser.value)
        val end = System.currentTimeMillis() + 5_000
        while (ServerController.status.value == ServerStatus.Off && System.currentTimeMillis() < end) Thread.sleep(20)
        assertNotEquals(ServerStatus.Off, ServerController.status.value)
    }



    @Test
    fun invisible_is_the_startup_state() {
        AppSettings.invisibleMode.value = false
        AppSettings.startupPresence()
        assertTrue(AppSettings.invisibleMode.value)
    }

    @Test
    fun every_new_message_is_unread_until_viewed_online() {
        ChatStore.addTheirs("bob", "1", "hi", SelfTimer.OFF)
        assertTrue("a new message lights the blue dot", ChatStore.thread("bob").unread)
        ChatStore.markSeen("bob")
        assertFalse(ChatStore.thread("bob").unread)
    }

    @Test
    fun going_online_delivers_missed_messages_and_clears_the_missed_label() {
        ChatStore.addTheirs("bob", "1", "while invisible", SelfTimer.S30, missed = true)
        val held = ChatStore.thread("bob").messages.single()
        assertTrue(held.missed)
        assertNull("its timer hasn't started", held.seenAt)
        ChatStore.deliverMissed()
        val t = ChatStore.thread("bob")
        assertTrue("no Missed Message any more", t.messages.none { it.missed })
        assertTrue("still new until viewed", t.unread)
        assertTrue("delivered: its timer runs", t.messages.single().seenAt != null)
    }
}
