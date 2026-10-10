package org.cmchat.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every language has every string, with the same placeholders as English —
 * so switching language can never show a raw key, crash on a missing
 * argument, or drop the name/number a sentence needs.
 */
class TranslationsTest {

    private val res = File("src/main/res")
    private val languages = listOf("ro", "fr", "de", "nl", "hu", "cs", "it", "es", "pl", "ru", "uk", "fi", "nb")

    private fun strings(dir: String): Map<String, String> {
        val text = File(res, "$dir/strings.xml").readText()
        return Regex("""<string name="([^"]+)"([^>]*)>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .filter { !it.groupValues[2].contains("translatable=\"false\"") }
            .associate { it.groupValues[1] to it.groupValues[3] }
    }

    private fun placeholders(s: String) = Regex("""%\d+\$[sd]""").findAll(s).map { it.value }.sorted().toList()

    @Test
    fun every_language_has_every_string_with_the_same_placeholders() {
        val en = strings("values")
        assertTrue("English strings found", en.size > 300)
        for (lang in languages) {
            val tr = strings("values-$lang")
            assertEquals("$lang: missing keys", emptySet<String>(), en.keys - tr.keys)
            assertEquals("$lang: keys English doesn't have", emptySet<String>(), tr.keys - en.keys)
            for ((key, value) in en) {
                val t = tr.getValue(key)
                assertTrue("$lang/$key is empty", t.isNotBlank())
                assertEquals("$lang/$key placeholders", placeholders(value), placeholders(t))
                // A lone ' or " ends an Android string early (it must be escaped).
                assertTrue("$lang/$key has an unescaped quote",
                    !Regex("""(?<!\\)['"]""").containsMatchIn(t))
            }
        }
    }

    @Test
    fun the_language_list_and_the_folders_agree() {
        // Norwegian is "no" in the picker and "nb" in res/ (see Tr.locale).
        val picker = org.cmchat.app.settings.Languages.list.map { it.first }.filter { it != "en" }
            .map { if (it == "no") "nb" else it }
        assertEquals(languages.sorted(), picker.sorted())
        for (lang in languages) assertTrue("values-$lang exists", File(res, "values-$lang/strings.xml").exists())
    }
}
