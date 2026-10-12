package com.vocab.idea.study

import com.vocab.idea.storage.Db
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Users download the dictionary themselves and import it through 选择词典文件, so importing a
 * real-world file has to work end to end: several thousand lines, Chinese text, and the book
 * name taken from the file name.
 *
 * The dictionary is deliberately not shipped in the plugin jar, so there is no bundled-resource
 * test any more — see build.gradle.kts.
 */
class NdjsonImportTest {

    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun setUp() {
        System.setProperty(
            "vocab.plugin.dir",
            dir.resolve("db-" + java.util.UUID.randomUUID()).toFile().apply { mkdirs() }.absolutePath,
        )
        Db.closeAll()
    }

    @AfterEach
    fun tearDown() {
        Db.closeAll()
    }

    private fun entry(spelling: String, meaning: String = "释义 $spelling", phonetic: String = "fəˈnetɪk") =
        """{"wordRank":1,"headWord":"$spelling","content":{"word":{"wordHead":"$spelling",""" +
            """"content":{"usphone":"$phonetic","trans":[{"tranCn":"$meaning"}],""" +
            """"sentence":{"sentences":[{"sContent":"a sentence with $spelling","sCn":"例句 $spelling"}]},""" +
            """"remMethod":{"val":"助记 $spelling"}}}}}"""

    private fun book(name: String, vararg spellings: String): File =
        dir.resolve("$name.json").toFile().apply {
            writeText(spellings.joinToString("\n") { entry(it) } + "\n")
        }

    @Test
    fun `the plugin jar does not ship a dictionary`() {
        // The dictionary is a separate download; packaging it again would silently undo that.
        assertNull(
            javaClass.classLoader.getResource("IELTSluan_2.json"),
            "the dictionary must not be packaged inside the plugin",
        )
    }

    @Test
    fun `an imported file keeps every field a card shows`() {
        val file = book("Downloaded", "sensible")
        assertTrue(DictionaryFormat.inspect(file) is DictionaryFormat.Verdict.Ok)

        assertEquals(1, WordBook.importNdjson(file))

        val word = WordBook.remainingWords("Downloaded").single()
        assertEquals("sensible", word.spelling)
        assertEquals("释义 sensible", word.meaning)

        WordBook.addWords(listOf(word.vocId))
        val card = Scheduler.due().single()
        assertEquals("fəˈnetɪk", card.phonetic)
        assertEquals("a sentence with sensible", card.sentence)
        assertEquals("例句 sensible", card.sentenceCn)
        assertEquals("助记 sensible", card.remethod)
    }

    @Test
    fun `the book name comes from the file name`() {
        WordBook.importNdjson(book("CET6", "alpha", "beta"))
        // The picker looks words up by book name, so this is what makes the file selectable.
        assertEquals(listOf("alpha", "beta"), WordBook.remainingWords("CET6").map { it.spelling })
        assertTrue(WordBook.remainingWords("SomeOtherBook").isEmpty())
    }

    @Test
    fun `two downloaded dictionaries stay separate books`() {
        WordBook.importNdjson(book("BookOne", "alpha"))
        WordBook.importNdjson(book("BookTwo", "beta"))

        assertEquals(listOf("alpha"), WordBook.remainingWords("BookOne").map { it.spelling })
        assertEquals(listOf("beta"), WordBook.remainingWords("BookTwo").map { it.spelling })
    }

    @Test
    fun `a large download imports completely`() {
        // Multi-chunk, so a chunk-boundary bug would drop or duplicate words.
        val spellings = (1..1200).map { "w$it" }
        val file = book("Big", *spellings.toTypedArray())

        val written = WordBook.importNdjson(file)
        assertEquals(1200, written)
        assertEquals(1200, WordBook.remainingWords("Big").size)
    }

    @Test
    fun `a tsv or plain text download is rejected before importing`() {
        // A file that is not NDJSON at all must be caught up front, not imported as 0 words.
        val file = dir.resolve("words.json").toFile().apply { writeText("alpha\t释义\nbeta\t释义\n") }
        val verdict = DictionaryFormat.inspect(file)
        assertTrue(verdict is DictionaryFormat.Verdict.Unusable, "expected rejection, got $verdict")
    }

    @Test
    fun `a json array download is rejected with a conversion hint`() {
        val file = dir.resolve("array.json").toFile()
            .apply { writeText("""[{"headWord":"alpha"},{"headWord":"beta"}]""") }
        val verdict = DictionaryFormat.inspect(file)
        assertTrue(verdict is DictionaryFormat.Verdict.Unusable)
        assertTrue((verdict as DictionaryFormat.Verdict.Unusable).reason.contains("jq"))
    }
}
