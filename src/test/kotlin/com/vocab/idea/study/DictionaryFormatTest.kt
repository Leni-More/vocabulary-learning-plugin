package com.vocab.idea.study

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * The user can now pick their own dictionary file, and most word lists found online are a
 * single JSON array rather than NDJSON. Importing such a file would silently add zero words,
 * so the format is checked up front and the verdict has to name the problem.
 */
class DictionaryFormatTest {

    @TempDir
    lateinit var dir: Path

    private fun file(name: String, content: String): File =
        dir.resolve(name).toFile().apply { writeText(content) }

    private fun ndjsonEntry(spelling: String) =
        """{"wordRank":1,"headWord":"$spelling","content":{"word":{"content":{"usphone":"x","trans":[{"tranCn":"释义"}]}}}}"""

    @Test
    fun `a proper ndjson dictionary is accepted`() {
        val f = file("good.json", listOf(ndjsonEntry("alpha"), ndjsonEntry("beta")).joinToString("\n") + "\n")
        val verdict = DictionaryFormat.inspect(f)
        assertTrue(verdict is DictionaryFormat.Verdict.Ok, "expected Ok but got $verdict")
        assertTrue((verdict as DictionaryFormat.Verdict.Ok).sampleEntries >= 2)
    }

    @Test
    fun `a file with blank lines between entries is still accepted`() {
        val f = file("blanks.json", "\n\n${ndjsonEntry("alpha")}\n\n\n${ndjsonEntry("beta")}\n\n")
        assertTrue(DictionaryFormat.inspect(f) is DictionaryFormat.Verdict.Ok)
    }

    @Test
    fun `a json array is rejected with advice on how to convert it`() {
        val f = file("array.json", """[{"headWord":"alpha"},{"headWord":"beta"}]""")
        val verdict = DictionaryFormat.inspect(f)
        assertTrue(verdict is DictionaryFormat.Verdict.Unusable, "a JSON array must be rejected")
        val reason = (verdict as DictionaryFormat.Verdict.Unusable).reason
        assertTrue(reason.contains("JSON 数组"), "reason should name the problem: $reason")
        assertTrue(reason.contains("jq"), "reason should suggest a fix: $reason")
    }

    @Test
    fun `a pretty printed array spanning many lines is rejected`() {
        val f = file(
            "pretty.json",
            """
            [
              {"headWord":"alpha"},
              {"headWord":"beta"}
            ]
            """.trimIndent(),
        )
        assertTrue(DictionaryFormat.inspect(f) is DictionaryFormat.Verdict.Unusable)
    }

    @Test
    fun `a single whole-file json object is rejected`() {
        val f = file("object.json", """{"words":[{"headWord":"alpha"}]}""")
        val verdict = DictionaryFormat.inspect(f)
        assertTrue(verdict is DictionaryFormat.Verdict.Unusable)
        assertTrue((verdict as DictionaryFormat.Verdict.Unusable).reason.contains("JSON 对象"))
    }

    @Test
    fun `a one-line dictionary is accepted rather than mistaken for a whole-file object`() {
        // A one-line file parses as a single JSON object, but it is still a valid NDJSON book:
        // judging it by the parse shape alone would reject a legitimate dictionary.
        val f = file("tiny.json", ndjsonEntry("alpha"))
        val verdict = DictionaryFormat.inspect(f)
        assertTrue(verdict is DictionaryFormat.Verdict.Ok, "expected Ok but got $verdict")
        assertEquals(1, (verdict as DictionaryFormat.Verdict.Ok).sampleEntries)
    }

    @Test
    fun `an empty file is rejected`() {
        val f = file("empty.json", "")
        val verdict = DictionaryFormat.inspect(f)
        assertTrue(verdict is DictionaryFormat.Verdict.Unusable)
        assertTrue((verdict as DictionaryFormat.Verdict.Unusable).reason.contains("空"))
    }

    @Test
    fun `a whitespace-only file is rejected`() {
        assertTrue(DictionaryFormat.inspect(file("spaces.json", "\n\n   \n\t\n")) is DictionaryFormat.Verdict.Unusable)
    }

    @Test
    fun `valid json lines without a headWord are rejected`() {
        val f = file("nohead.json", """{"word":"alpha"}""" + "\n" + """{"word":"beta"}""")
        val verdict = DictionaryFormat.inspect(f)
        assertTrue(verdict is DictionaryFormat.Verdict.Unusable)
        assertTrue((verdict as DictionaryFormat.Verdict.Unusable).reason.contains("headWord"))
    }

    @Test
    fun `garbage lines are rejected`() {
        assertTrue(DictionaryFormat.inspect(file("junk.json", "hello\nworld\n")) is DictionaryFormat.Verdict.Unusable)
    }

    @Test
    fun `entry detection needs a non-blank spelling`() {
        assertTrue(DictionaryFormat.looksLikeEntry("""{"headWord":"alpha"}"""))
        assertFalse(DictionaryFormat.looksLikeEntry("""{"headWord":""}"""))
        assertFalse(DictionaryFormat.looksLikeEntry("""{"headWord":null}"""))
        assertFalse(DictionaryFormat.looksLikeEntry("""{"other":"alpha"}"""))
        assertFalse(DictionaryFormat.looksLikeEntry("not json"))
        assertFalse(DictionaryFormat.looksLikeEntry(""))
    }

    @Test
    fun `a user dictionary is accepted under its own file name`() {
        // The book name is taken from the file name, so each downloaded dictionary becomes its
        // own book instead of mixing into another one.
        val mine = file("MyBook.json", (1..5).joinToString("\n") { ndjsonEntry("mine$it") })
        val verdict = DictionaryFormat.inspect(mine)
        assertTrue(verdict is DictionaryFormat.Verdict.Ok)
        assertEquals(5, (verdict as DictionaryFormat.Verdict.Ok).sampleEntries)
    }
}
