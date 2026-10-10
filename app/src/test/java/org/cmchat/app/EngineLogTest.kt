package org.cmchat.app

import org.cmchat.app.chat.ChatStore
import org.cmchat.app.chat.EngineLog
import org.cmchat.app.transport.MessageService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Engine logs never become chat messages; ordinary messages are never touched. */
class EngineLogTest {

    @Before fun reset() = ChatStore.clearAll()

    @Test
    fun copied_diagnostics_and_connection_logs_are_recognised() {
        val diag = """
            1728550000123 I [tor] status=ON
            1728550001456 I [onion] published abcd…wxyz.onion
            1728550002789 W [life] closed -> fully offline
        """.trimIndent()
        val conn = """
            14:02:11.042 OUT resolve abcd…wxyz.onion
            14:02:11.977 OUT SOCKS5 connect … ok
            14:02:12.311 SYS Add friend: knock queued → abcd…wxyz
        """.trimIndent()
        val screen = "[tor] status=ON\n[onion] ADD_ONION reply keys=[ServiceID]\n[bridges] transport up"
        assertTrue(EngineLog.looksLikeLog(diag))
        assertTrue(EngineLog.looksLikeLog(conn))
        assertTrue(EngineLog.looksLikeLog(screen))
        // One complete timestamped log line on its own counts too.
        assertTrue(EngineLog.looksLikeLog("1728550000123 E [crash] previous run crashed"))
        // A log pasted in the middle of a sentence or two is still mostly log.
        assertTrue(EngineLog.looksLikeLog("look:\n$conn"))
    }

    @Test
    fun ordinary_messages_are_never_treated_as_logs() {
        for (ok in listOf(
            "hi", "[tor] is slow today", "my SOCKS proxy is broken",
            "meet at 14:02:11.042?", "OUT of coffee\nIN the shop",
            "[onion] lol\nsee you", "1728550000123 is a big number",
            "line one\n[tor] status=ON\nline three\nline four",
        )) assertFalse("must not block: $ok", EngineLog.looksLikeLog(ok))
    }

    @Test
    fun a_log_is_never_sent_into_a_conversation() {
        MessageService.sendText("friend-x", "1728550000123 I [tor] status=ON\n1728550000124 I [tor] x", org.cmchat.app.chat.SelfTimer.OFF)
        assertTrue(ChatStore.thread("friend-x").messages.isEmpty())
        MessageService.sendText("friend-x", "hello", org.cmchat.app.chat.SelfTimer.OFF)
        assertTrue(ChatStore.thread("friend-x").messages.single().text == "hello")
    }
}
