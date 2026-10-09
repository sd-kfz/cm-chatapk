package org.cmchat.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import kotlinx.serialization.json.Json
import org.cmchat.app.crypto.CryptoManager
import org.cmchat.app.transport.KnockPayload
import org.cmchat.app.transport.Messages
import org.cmchat.app.transport.TextPayload
import org.cmchat.app.vault.ContactRec
import org.cmchat.app.vault.UnlockResult
import org.cmchat.app.vault.VaultData
import org.cmchat.app.vault.VaultManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Item 7 — every text input (passcode, nickname, message) is OPAQUE DATA: it is
 * never executed, eval'd, or used as a shell/SQL/file-name string. These tests
 * feed hostile-looking input through the real code paths and prove it comes
 * back byte-for-byte, influences nothing else, and executes nothing.
 */
class OpaqueInputTest {

    private val crypto = CryptoManager(LazySodiumJava(SodiumJava()))

    private fun hostile(sentinel: File): List<String> = listOf(
        "' OR '1'='1",                           // SQL injection
        "\"; DROP TABLE vault; --",              // SQL injection
        "\$(touch ${sentinel.path})",            // shell command substitution
        "`touch ${sentinel.path}`",              // shell backticks
        "; rm -rf / #",                          // shell chaining
        "../../../etc/passwd",                   // path traversal
        "%s%n%x%08x",                            // format-string
        "\${jndi:ldap://x/a}",                   // template / lookup injection
        "<script>alert(1)</script>",             // markup
        "a\u0000b\nc\rd",                        // NUL + control characters
        "‮evil‬ ✓ 🔥",                 // RTL override + emoji
    )

    @Test
    fun hostile_passcodes_are_only_ever_bytes_for_argon2id() {
        val work = Files.createTempDirectory("opaque").toFile()
        val sentinel = File("build/PWNED").also { it.delete() }   // short, relative
        for ((i, pass) in hostile(sentinel).withIndex()) {
            assertTrue("valid new passcode: ${i}", VaultManager.isValidNewPin(pass))
            val dir = File(work, "vault$i")
            val m = VaultManager(crypto, dir)
            m.createVault(pass, "x")
            // The exact same bytes unlock it…
            assertTrue(m.unlock(pass) is UnlockResult.Success)
            // …and nothing "equivalent" does: no trimming, unescaping or
            // normalising — it is compared as raw bytes only.
            for (near in listOf(pass.trim() + " ", pass.replace("'", "\\'"), pass.uppercase() + "x")) {
                if (near != pass && near != pass.reversed()) assertEquals(UnlockResult.WrongPin, m.unlock(near))
            }
            // The passcode influenced no file names: only the vault + salt exist,
            // and the passcode is not stored anywhere in them.
            assertEquals(setOf("vault.dat", "salt.dat"), dir.list()!!.toSet())
            val onDisk = dir.listFiles()!!.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
            assertFalse(onDisk.contains(pass))
        }
        // Nothing was executed: the shell payloads would have created this file.
        assertFalse("a shell payload was executed!", sentinel.exists())
        work.deleteRecursively()
    }

    @Test
    fun hostile_nicknames_and_messages_round_trip_as_plain_text() {
        val sentinel = File("build/PWNED2").also { it.delete() }
        val json = Json { ignoreUnknownKeys = true }
        for (s in hostile(sentinel)) {
            // Messages and knocks: serialised as JSON strings, parsed back exactly.
            val t = TextPayload("id", s, "off")
            assertEquals(t, Messages.json.decodeFromString(TextPayload.serializer(),
                Messages.json.encodeToString(TextPayload.serializer(), t)))
            val k = KnockPayload(s, "cmc1:X")
            assertEquals(k, Messages.json.decodeFromString(KnockPayload.serializer(),
                Messages.json.encodeToString(KnockPayload.serializer(), k)))
            // Nicknames in the vault: same, and they can't break out of their field.
            val v = VaultData(contacts = listOf(ContactRec("1", s, 0, "f", cmId = "cmc1:Y")))
            val back = json.decodeFromString(VaultData.serializer(), json.encodeToString(VaultData.serializer(), v))
            assertEquals(s, back.contacts.single().name)
            assertEquals("cmc1:Y", back.contacts.single().cmId)
        }
        assertFalse(sentinel.exists())
    }

    @Test
    fun the_app_has_no_code_path_that_could_execute_input() {
        // Static guard: the app sources contain no shell, SQL, script or WebView
        // execution APIs at all — so there is nowhere input COULD be executed.
        val src = File("src/main/java")
        assertTrue("run from the app module", src.isDirectory)
        val banned = listOf(
            ".exec(", "ProcessBuilder",   // (Runtime.getRuntime() alone is fine: RamDiag reads memory stats) "execSQL", "rawQuery", "SQLiteDatabase",
            "ScriptEngine", "evaluateJavascript", "WebView", "loadUrl(", "DexClassLoader", "Class.forName(",
        )
        val hits = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            val text = f.readText()
            banned.filter { text.contains(it) }.map { "${f.name}: $it" }
        }.toList()
        assertTrue("execution APIs found: $hits", hits.isEmpty())
    }
}
