package org.cmchat.app

import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava
import org.cmchat.app.crypto.CryptoManager
import org.cmchat.app.vault.ContactRec
import org.cmchat.app.vault.UnlockResult
import org.cmchat.app.vault.VaultData
import org.cmchat.app.vault.VaultSettings
import org.cmchat.app.vault.VaultManager
import org.cmchat.app.vault.VaultManager.Companion.Strength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files


class VaultTest {

    private fun newManager(): Pair<VaultManager, File> {
        val ls = LazySodiumJava(SodiumJava())
        val crypto = CryptoManager(ls)
        val dir = Files.createTempDirectory("cmvault").toFile()
        return VaultManager(crypto, dir) to dir
    }

    @Test
    fun vault_round_trip_and_wrong_pin() {
        val (m, _) = newManager()
        m.createVault(pin = "135790", faceName = "Wanderer")

        val ok = m.unlock("135790")
        assertTrue(ok is UnlockResult.Success)
        assertEquals("Wanderer", (ok as UnlockResult.Success).data.faces.single().name)


        assertEquals(UnlockResult.WrongPin, m.unlock("246801"))
    }

    @Test
    fun reverse_pin_is_detected_as_duress_and_wipes() {
        val (m, _) = newManager()
        m.createVault(pin = "135790", faceName = "Wanderer")

        assertEquals(UnlockResult.Duress, m.unlock("097531"))

        assertTrue(m.firstRunNeeded())
    }

    @Test
    fun palindrome_pin_rejected_others_accepted() {

        assertFalse(VaultManager.isValidNewPin("123"))
        assertTrue(VaultManager.isValidNewPin("1234"))
        assertTrue(VaultManager.isValidNewPin("12345"))
        assertFalse(VaultManager.isValidNewPin("1221"))
        assertFalse(VaultManager.isValidNewPin("123321"))
        assertFalse(VaultManager.isValidNewPin("ababa"))
        assertTrue(VaultManager.isValidNewPin("135790"))
        assertTrue(VaultManager.isValidNewPin("12a456"))
        assertTrue(VaultManager.isValidNewPin("p@ss#1"))
        assertTrue(VaultManager.isValidNewPin("a".repeat(55) + "b"))
        assertFalse(VaultManager.isValidNewPin("a".repeat(56) + "b"))
    }

    @Test
    fun strength_hint_encourages_eight_plus_but_never_blocks() {
        assertEquals(Strength.WEAK, VaultManager.strength("1234"))
        assertEquals(Strength.WEAK, VaultManager.strength("135790"))
        assertEquals(Strength.FAIR, VaultManager.strength("Ab1!xy"))
        assertEquals(Strength.FAIR, VaultManager.strength("13579024"))
        assertEquals(Strength.GOOD, VaultManager.strength("Abc12!xyz"))
        assertEquals(Strength.STRONG, VaultManager.strength("correct horse 9"))

        assertTrue(VaultManager.isValidNewPin("1234"))
    }

    @Test
    fun change_pin_reencrypts_and_old_pin_stops_working() {
        val (m, _) = newManager()
        m.createVault(pin = "135790", faceName = "Wanderer")


        assertFalse(m.changePin("000000", "246802"))
        assertTrue(m.unlock("135790") is UnlockResult.Success)


        assertTrue(m.changePin("135790", "246802"))
        assertEquals(UnlockResult.WrongPin, m.unlock("135790"))
        val ok = m.unlock("246802")
        assertTrue(ok is UnlockResult.Success)
        assertEquals("Wanderer", (ok as UnlockResult.Success).data.faces.single().name)


        assertFalse(m.changePin("246802", "12321"))
        assertTrue(m.unlock("246802") is UnlockResult.Success)
    }

    @Test
    fun crypto_secretbox_open_fails_on_tampered_blob() {
        val ls = LazySodiumJava(SodiumJava())
        val crypto = CryptoManager(ls)
        val salt = crypto.randomSalt()
        val key = crypto.deriveKey("135790", salt)
        val blob = crypto.seal("secret".toByteArray(), key)
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(crypto.open(tampered, key))
        assertEquals("secret", String(crypto.open(blob, key)!!))
    }



    private fun ok(r: UnlockResult): VaultData = (r as UnlockResult.Success).data

    @Test
    fun saves_reuse_the_unlock_key_and_never_run_argon2() {
        val (m, _) = newManager()
        val created = m.createVault(pin = "135790", faceName = "Wanderer")
        assertEquals("one Argon2id to create", 1, m.argon2Runs.get())

        var d = created
        repeat(10) { i ->
            d = d.copy(contacts = d.contacts + ContactRec("c$i", "Friend $i", 0, "f", cmId = "id$i"))
            assertTrue(m.queueSave(d))
            m.flush()
        }
        assertEquals("saves never derive", 1, m.argon2Runs.get())
        m.lock()
        assertFalse("locked: nothing can be queued", m.queueSave(d))

        val back = ok(m.unlock("135790"))
        assertEquals(2, m.argon2Runs.get())
        assertEquals(10, back.contacts.size)
        assertEquals("Friend 9", back.contacts.last().name)
    }

    @Test
    fun a_save_queued_just_before_lock_is_still_written_then_the_key_goes() {
        val (m, _) = newManager()
        val d = m.createVault(pin = "135790", faceName = "Wanderer")
        assertTrue(m.queueSave(d.copy(settings = d.settings.copy(decoyEnabled = true, decoyName = "Mum"))))
        m.lock()
        assertFalse(m.isUnlocked())
        m.flush()
        val back = ok(m.unlock("135790"))
        assertTrue(back.settings.decoyEnabled)
        assertEquals("Mum", back.settings.decoyName)
    }

    @Test
    fun the_decoys_late_save_lands_after_lock_and_unlock_waits_for_it() {
        val (m, _) = newManager()
        val d = m.createVault(pin = "135790", faceName = "Wanderer")
        val late = m.holdForLateSave()
        assertNotNull(late)
        m.lock()
        val moved = d.copy(faces = d.faces.map { it.copy(onionAddress = "new.onion", onionKey = "ED25519-V3:k") })
        late!!.save(moved)
        m.flush()
        assertEquals("new.onion", ok(m.unlock("135790")).faces.single().onionAddress)
    }



    @Test
    fun every_setting_survives_a_restart() {
        val (m, dir) = newManager()
        val d = m.createVault(pin = "135790", faceName = "Wanderer")
        val s = d.settings.copy(defaultSelfTimer = "5m", buzzFrequency = "ONCE", buzzWhenClosed = false,
            decoyEnabled = true, decoyName = "Bank", decoyAtTop = false, toolCalc = true, toolNotes = true,
            toolFlash = true, onboardingPage = 3, serverStopped = true)
        assertTrue(m.queueSave(d.copy(settings = s)))
        m.flush()

        val again = VaultManager(m.crypto, dir)
        assertEquals(s, ok(again.unlock("135790")).settings)
    }

    @Test
    fun older_vault_json_without_the_new_settings_reads_the_old_behaviour() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(VaultSettings.serializer(), """{"cerberusMinutes":30,"textSize":2}""")
        assertEquals(30, old.cerberusMinutes)
        assertEquals("general timer was never in effect: Off", "off", old.defaultSelfTimer)
        assertFalse(old.decoyEnabled)
        assertTrue(old.buzzWhenClosed)
        assertFalse(old.serverStopped)
        assertFalse(old.toolCalc || old.toolNotes || old.toolFlash)
    }



    @Test
    fun a_vault_from_an_older_build_opens_and_is_converted() {
        val (m, dir) = newManager()
        val salt = m.crypto.randomSalt()
        val key = m.crypto.deriveKey("135790", salt)
        val legacy = VaultData(contacts = listOf(ContactRec("c1", "Bob", 0, "f", cmId = "B")))
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        dir.mkdirs()
        java.io.File(dir, "salt.dat").writeBytes(salt)
        java.io.File(dir, "vault.dat").writeBytes(
            m.crypto.seal(json.encodeToString(VaultData.serializer(), legacy).toByteArray(), key))
        assertFalse(m.firstRunNeeded())
        val d = ok(m.unlock("135790"))
        assertEquals("Bob", d.contacts.single().name)
        assertTrue(m.queueSave(d)); m.flush()
        assertEquals("converted to the one-file vault", setOf("vault2.dat"), dir.list()!!.toSet())
        m.lock()
        assertEquals("Bob", ok(m.unlock("135790")).contacts.single().name)
        assertEquals(UnlockResult.WrongPin, m.unlock("246801"))
    }



    @Test
    fun change_pin_cut_off_at_any_step_never_bricks_the_vault() {
        for (step in 1..4) {
            val (m, dir) = newManager()
            val d = m.createVault(pin = "135790", faceName = "Wanderer")
            val withFriend = d.copy(contacts = listOf(ContactRec("c1", "Bob", 0, "f", cmId = "B")))
            m.queueSave(withFriend); m.flush()
            m.files.crashAfterStep = step
            assertFalse("step $step: the change reports failure", m.changePin("135790", "246802"))

            val next = VaultManager(m.crypto, dir)
            if (step < 4) {

                assertEquals("step $step", withFriend, ok(next.unlock("135790")))
                next.lock()
                assertEquals("step $step", UnlockResult.WrongPin, next.unlock("246802"))
            } else {


                assertEquals(withFriend, ok(next.unlock("135790")))
                next.lock()
                assertEquals(UnlockResult.WrongPin, next.unlock("246802"))
            }
            assertEquals("step $step: no leftovers once settled", setOf("vault2.dat"), dir.list()!!.toSet())
        }
    }

    @Test
    fun change_pin_cut_off_after_the_swap_confirms_with_the_new_pin() {
        val (m, dir) = newManager()
        val d = m.createVault(pin = "135790", faceName = "Wanderer")
        m.files.crashAfterStep = 4
        assertFalse(m.changePin("135790", "246802"))
        val next = VaultManager(m.crypto, dir)
        assertEquals(d, ok(next.unlock("246802")))
        assertEquals(setOf("vault2.dat"), dir.list()!!.toSet())
        next.lock()
        assertEquals(UnlockResult.WrongPin, next.unlock("135790"))
    }

    @Test
    fun a_completed_change_pin_leaves_no_old_copy_and_keeps_saving() {
        val (m, dir) = newManager()
        val d = m.createVault(pin = "135790", faceName = "Wanderer")
        assertTrue(m.changePin("135790", "246802"))
        assertEquals(setOf("vault2.dat"), dir.list()!!.toSet())

        val runs = m.argon2Runs.get()
        assertTrue(m.queueSave(d.copy(settings = d.settings.copy(textSize = 3)))); m.flush()
        assertEquals(runs, m.argon2Runs.get())
        m.lock()
        assertEquals(3, ok(m.unlock("246802")).settings.textSize)
        m.lock()
        assertEquals(UnlockResult.WrongPin, m.unlock("135790"))
    }

    @Test
    fun the_shredder_pin_also_wipes_a_kept_old_copy() {
        val (m, dir) = newManager()
        m.createVault(pin = "135790", faceName = "Wanderer")
        m.files.crashAfterStep = 4
        m.changePin("135790", "246802")
        val next = VaultManager(m.crypto, dir)
        assertEquals(UnlockResult.Duress, next.unlock("208642"))
        assertTrue(next.firstRunNeeded())
        assertTrue(dir.list()!!.isEmpty())
    }
}
