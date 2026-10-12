package com.vocab.idea.storage

import com.vocab.idea.study.Answer
import com.vocab.idea.study.Scheduler
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager

/**
 * A database created by an older build has no `relearn_left` column. Opening it must upgrade
 * the schema in place instead of throwing, and existing progress must keep working.
 */
class DbMigrationTest {

    @TempDir
    lateinit var dir: Path

    @AfterEach
    fun tearDown() {
        Db.closeAll()
    }

    @Test
    fun `a database without the relearn column is upgraded on open`() {
        val dbDir = dir.resolve("legacy").toFile().apply { mkdirs() }
        val url = "jdbc:sqlite:${dbDir.absolutePath}/vocab.db"
        Class.forName("org.sqlite.JDBC")

        // Build the pre-relearn schema by hand, exactly as the old createSchema did.
        DriverManager.getConnection(url).use { old ->
            old.createStatement().use { st ->
                st.executeUpdate(
                    """
                    CREATE TABLE words(
                        voc_id TEXT PRIMARY KEY,
                        spelling TEXT NOT NULL,
                        phonetic TEXT,
                        meaning TEXT,
                        sentence TEXT,
                        sentence_cn TEXT,
                        remethod TEXT
                    )""".trimIndent()
                )
                st.executeUpdate(
                    """
                    CREATE TABLE learning(
                        voc_id TEXT PRIMARY KEY REFERENCES words(voc_id),
                        stage INTEGER NOT NULL DEFAULT 0,
                        next_review_at INTEGER NOT NULL,
                        last_result TEXT,
                        added_at INTEGER NOT NULL
                    )""".trimIndent()
                )
            }
            old.prepareStatement("INSERT INTO words(voc_id, spelling) VALUES (?,?)").use { ps ->
                ps.setString(1, "legacy:1")
                ps.setString(2, "legacy")
                ps.executeUpdate()
            }
            old.prepareStatement(
                "INSERT INTO learning(voc_id, stage, next_review_at, added_at) VALUES (?,?,?,?)"
            ).use { ps ->
                ps.setString(1, "legacy:1")
                ps.setInt(2, 4)
                ps.setLong(3, 0L)
                ps.setLong(4, 0L)
                ps.executeUpdate()
            }
        }

        // Opening through Db must apply the migration rather than fail.
        System.setProperty("vocab.plugin.dir", dbDir.absolutePath)
        Db.closeAll()

        val card = Scheduler.due().single()
        assertEquals("legacy", card.spelling)
        assertEquals(4, card.stage)
        assertEquals(0, card.relearnLeft, "existing rows must default to no relearning")

        // The upgraded database must be fully usable, including the new column.
        val outcome = Scheduler.applyAnswer(card, Answer.VAGUE)
        assertTrue(outcome.requeue)
        assertEquals(2, outcome.card.relearnLeft)
        // Failing steps a mature card back two stages: 4 (15 days) -> 2 (4 days).
        assertEquals(2, outcome.card.stage)
    }

    @Test
    fun `opening the same database twice does not fail on the migration`() {
        val dbDir = dir.resolve("twice").toFile().apply { mkdirs() }
        System.setProperty("vocab.plugin.dir", dbDir.absolutePath)

        Db.closeAll()
        Scheduler.addWord("a", "alpha", null, null)
        Db.closeAll()

        // Second open re-runs createSchema, including the ALTER TABLE guard.
        Scheduler.addWord("b", "beta", null, null)
        assertEquals(2, Scheduler.due().size)
    }
}
