package com.vocab.idea.study

import com.vocab.idea.storage.Db
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class WordBookTest {

    @TempDir
    lateinit var dir: Path

    @BeforeEach
    fun setUp() {
        System.setProperty("vocab.plugin.dir", uniqueDbDir())
        Db.closeAll()
    }

    @AfterEach
    fun tearDown() {
        Db.closeAll()
    }

    private fun line(spelling: String, meaning: String = "释义 $spelling") =
        """{"wordRank":1,"headWord":"$spelling","content":{"word":{"wordHead":"$spelling",""" +
            """"content":{"usphone":"$spelling-phone","trans":[{"tranCn":"$meaning"}],""" +
            """"sentence":{"sentences":[{"sContent":"sentence for $spelling","sCn":"例句 $spelling"}]},""" +
            """"remMethod":{"val":"rem-$spelling"}}}}}"""

    private fun writeBook(name: String, vararg spellings: String): File {
        val f = dir.resolve("$name.json").toFile()
        f.writeText(spellings.joinToString("\n") { line(it) } + "\n")
        return f
    }

    @Test
    fun `import writes every field the card needs`() {
        val book = writeBook("BookA", "sensible", "obvious")

        val written = WordBook.importNdjson(book)
        assertEquals(2, written)

        val words = WordBook.remainingWords("BookA").associateBy { it.spelling }
        assertEquals(setOf("sensible", "obvious"), words.keys)
        assertEquals("释义 sensible", words.getValue("sensible").meaning)

        // Only words in the study queue can come back as cards.
        WordBook.addWords(listOf(words.getValue("sensible").vocId))
        val card = Scheduler.due().first { it.spelling == "sensible" }
        assertEquals("sensible-phone", card.phonetic)
        assertEquals("sentence for sensible", card.sentence)
        assertEquals("例句 sensible", card.sentenceCn)
        assertEquals("rem-sensible", card.remethod)
    }

    @Test
    fun `import is idempotent and does not duplicate words`() {
        val book = writeBook("BookB", "aware", "distinct")
        WordBook.importNdjson(book)
        WordBook.importNdjson(book)

        assertEquals(2, WordBook.remainingWords("BookB").size)
    }

    @Test
    fun `blank and malformed lines are skipped without aborting the import`() {
        val f = dir.resolve("BookC.json").toFile()
        f.writeText(
            listOf(
                line("alpha"),
                "",
                "   ",
                "{ not json at all",
                """{"noHeadWord":true}""",
                line("beta"),
            ).joinToString("\n") + "\n"
        )

        val written = WordBook.importNdjson(f)
        assertEquals(2, written)
        assertEquals(listOf("alpha", "beta"), WordBook.remainingWords("BookC").map { it.spelling })
    }

    @Test
    fun `import commits across chunk boundaries`() {
        // More than one 500-line chunk, so a chunk-boundary bug would drop or duplicate words.
        val spellings = (1..1200).map { "w$it" }
        val book = writeBook("BookD", *spellings.toTypedArray())

        val written = WordBook.importNdjson(book)
        assertEquals(1200, written)
        assertEquals(1200, WordBook.remainingWords("BookD").size)
    }

    @Test
    fun `import reports progress and ends on the total`() {
        val spellings = (1..1200).map { "p$it" }
        val book = writeBook("BookE", *spellings.toTypedArray())

        val reports = mutableListOf<Int>()
        WordBook.importNdjson(book) { reports.add(it) }

        assertTrue(reports.isNotEmpty(), "no progress callbacks")
        assertEquals(1200, reports.last())
        // Reports must be monotonic; a duplicated chunk would make them jump backwards.
        assertEquals(reports.sorted(), reports)
    }

    @Test
    fun `book names containing LIKE wildcards do not match other books`() {
        // '_' is a LIKE wildcard, so a prefix match would make IELTSluan_2 also match
        // IELTSluanX2 and show words belonging to a different book.
        WordBook.importNdjson(writeBook("IELTSluan_2", "one"))
        WordBook.importNdjson(writeBook("IELTSluanX2", "two"))
        WordBook.importNdjson(writeBook("IELTS%uan", "three"))

        assertEquals(listOf("one"), WordBook.remainingWords("IELTSluan_2").map { it.spelling })
        assertEquals(listOf("two"), WordBook.remainingWords("IELTSluanX2").map { it.spelling })
        assertEquals(listOf("three"), WordBook.remainingWords("IELTS%uan").map { it.spelling })
    }

    @Test
    fun `addWords enqueues chosen words once and reports how many were new`() {
        WordBook.importNdjson(writeBook("BookF", "sensible", "obvious", "aware"))
        val ids = WordBook.remainingWords("BookF").map { it.vocId }

        assertEquals(3, WordBook.addWords(ids))
        // Second call must report zero new words, not three again.
        assertEquals(0, WordBook.addWords(ids))
        assertEquals(0, WordBook.remainingWords("BookF").size)
        assertTrue(Scheduler.due().map { it.vocId }.containsAll(ids))
    }

    @Test
    fun `addWords ignores ids that are not in the words table`() {
        WordBook.importNdjson(writeBook("BookG", "sensible"))
        val known = WordBook.remainingWords("BookG").map { it.vocId }

        assertEquals(1, WordBook.addWords(listOf("does-not-exist") + known))
    }

    @Test
    fun `chosen words become due immediately`() {
        WordBook.importNdjson(writeBook("BookH", "sensible"))
        val id = WordBook.remainingWords("BookH").single().vocId
        assertEquals(1, WordBook.addWords(listOf(id)))
        assertEquals(listOf(id), Scheduler.due().map { it.vocId })
    }

    @Test
    fun `remainingWords only returns words that are not queued yet`() {
        WordBook.importNdjson(writeBook("BookI", "sensible", "obvious"))
        val queued = WordBook.remainingWords("BookI").first { it.spelling == "sensible" }.vocId
        WordBook.addWords(listOf(queued))

        assertEquals(listOf("obvious"), WordBook.remainingWords("BookI").map { it.spelling })
    }

    @Test
    fun `the review schedule index exists so due queries are not full scans`() {
        val indexes = Db.conn().createStatement().use { st ->
            st.executeQuery("PRAGMA index_list(learning)").use { rs ->
                val names = mutableListOf<String>()
                while (rs.next()) names.add(rs.getString(2))
                names
            }
        }
        assertTrue(
            indexes.any { it.contains("next_review") },
            "expected an index on learning(next_review_at), found: $indexes",
        )
    }

    @Test
    fun `a failed transaction leaves no words behind`() {
        // Multi-statement writes must be atomic: without a transaction the words row below
        // would be committed on its own and survive the failure.
        Db.conn().prepareStatement("INSERT INTO words(voc_id, spelling) VALUES (?,?)").use { ps ->
            ps.setString(1, "seed")
            ps.setString(2, "seed")
            ps.executeUpdate()
        }
        val before = countWords()

        // The second statement violates the primary key, so the transaction must roll back.
        runCatching {
            Db.transaction { conn ->
                conn.prepareStatement("INSERT INTO words(voc_id, spelling) VALUES (?,?)").use { ps ->
                    ps.setString(1, "orphan")
                    ps.setString(2, "orphan")
                    ps.executeUpdate()
                }
                conn.prepareStatement("INSERT INTO words(voc_id, spelling) VALUES (?,?)").use { ps ->
                    ps.setString(1, "seed")
                    ps.setString(2, "seed-again")
                    ps.executeUpdate()
                }
            }
        }

        assertEquals(before, countWords())
        assertFalse(wordExists("orphan"))
    }

    private fun countWords(): Int =
        Db.conn().createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM words").use { rs -> rs.next(); rs.getInt(1) }
        }

    private fun wordExists(vocId: String): Boolean =
        Db.conn().prepareStatement("SELECT 1 FROM words WHERE voc_id = ?").use { ps ->
            ps.setString(1, vocId)
            ps.executeQuery().use { rs -> rs.next() }
        }

    /**
     * A fresh database per test. @TempDir is created once per *class*, so reusing it would let
     * one test's rows leak into the next and make counts order-dependent.
     */
    private fun uniqueDbDir(): String =
        dir.resolve("db-" + java.util.UUID.randomUUID()).toFile().apply { mkdirs() }.absolutePath
}
