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


class OpaqueInputTest {

    private val crypto = CryptoManager(LazySodiumJava(SodiumJava()))

    private fun hostile(sentinel: File): List<String> = listOf(
        "' OR '1'='1",
        "\"; DROP TABLE vault; --",
        "\$(touch ${sentinel.path})",
        "`touch ${sentinel.path}`",
        "; rm -rf / #",
        "../../../etc/passwd",
        "%s%n%x%08x",
        "\${jndi:ldap://x/a}",
        "<script>alert(1)</script>",
        "a\u0000b\nc\rd",
        "‮evil‬ ✓ 🔥",
    )

    @Test
    fun hostile_passcodes_are_only_ever_bytes_for_argon2id() {
        val work = Files.createTempDirectory("opaque").toFile()
        val sentinel = File("build/PWNED").also { it.delete() }
        for ((i, pass) in hostile(sentinel).withIndex()) {
            assertTrue("valid new passcode: ${i}", VaultManager.isValidNewPin(pass))
            val dir = File(work, "vault$i")
            val m = VaultManager(crypto, dir)
            m.createVault(pass, "x")

            assertTrue(m.unlock(pass) is UnlockResult.Success)


            for (near in listOf(pass.trim() + " ", pass.replace("'", "\\'"), pass.uppercase() + "x")) {
                if (near != pass && near != pass.reversed()) assertEquals(UnlockResult.WrongPin, m.unlock(near))
            }


            assertEquals(setOf("vault2.dat"), dir.list()!!.toSet())
            val onDisk = dir.listFiles()!!.joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
            assertFalse(onDisk.contains(pass))
        }

        assertFalse("a shell payload was executed!", sentinel.exists())
        work.deleteRecursively()
    }

    @Test
    fun hostile_nicknames_and_messages_round_trip_as_plain_text() {
        val sentinel = File("build/PWNED2").also { it.delete() }
        val json = Json { ignoreUnknownKeys = true }
        for (s in hostile(sentinel)) {

            val t = TextPayload("id", s, "off")
            assertEquals(t, Messages.json.decodeFromString(TextPayload.serializer(),
                Messages.json.encodeToString(TextPayload.serializer(), t)))
            val k = KnockPayload(s, "cmc1:X")
            assertEquals(k, Messages.json.decodeFromString(KnockPayload.serializer(),
                Messages.json.encodeToString(KnockPayload.serializer(), k)))

            val v = VaultData(contacts = listOf(ContactRec("1", s, 0, "f", cmId = "cmc1:Y")))
            val back = json.decodeFromString(VaultData.serializer(), json.encodeToString(VaultData.serializer(), v))
            assertEquals(s, back.contacts.single().name)
            assertEquals("cmc1:Y", back.contacts.single().cmId)
        }
        assertFalse(sentinel.exists())
    }

    @Test
    fun the_app_has_no_code_path_that_could_execute_input() {


        val src = File("src/main/java")
        assertTrue("run from the app module", src.isDirectory)
        val banned = listOf(
            ".exec(", "ProcessBuilder",
            "ScriptEngine", "evaluateJavascript", "WebView", "loadUrl(", "DexClassLoader", "Class.forName(",
        )
        val hits = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            val text = f.readText()
            banned.filter { text.contains(it) }.map { "${f.name}: $it" }
        }.toList()
        assertTrue("execution APIs found: $hits", hits.isEmpty())
    }
}
