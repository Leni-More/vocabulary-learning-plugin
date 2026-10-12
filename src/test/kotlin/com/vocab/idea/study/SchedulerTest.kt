package com.vocab.idea.study

import com.vocab.idea.storage.Db
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class SchedulerTest {

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

    // ---------- interval table ----------

    @Test
    fun `the shortest interval is one day and there is no same-day tier`() {
        assertEquals(1L, INTERVAL_DAYS.first())
        assertTrue(INTERVAL_DAYS.all { it >= 1 }, "every interval must be at least one day")
        assertTrue(INTERVAL_DAYS.zipWithNext().all { (a, b) -> a < b }, "intervals must increase")
    }

    @Test
    fun `intervals map to the documented day counts`() {
        assertEquals(1L * 86_400_000, intervalMs(0))
        assertEquals(2L * 86_400_000, intervalMs(1))
        assertEquals(7L * 86_400_000, intervalMs(3))
        assertEquals(180L * 86_400_000, intervalMs(INTERVAL_DAYS.size - 1))
    }

    // ---------- first impression decides the entry stage ----------

    @Test
    fun `a word known at first sight enters at seven days`() {
        assertEquals(3, entryStage(Answer.KNOW))
        assertEquals(7L, INTERVAL_DAYS[entryStage(Answer.KNOW)])

        val (stage, passes) = Scheduler.advance(
            stage = 0, relearnLeft = 0, answer = Answer.KNOW, firstEncounter = true
        )
        assertEquals(3, stage)
        assertEquals(0, passes)
    }

    @Test
    fun `a word vague at first sight enters at two days and needs two passes`() {
        assertEquals(2L, INTERVAL_DAYS[entryStage(Answer.VAGUE)])
        val (stage, passes) = Scheduler.advance(
            stage = 0, relearnLeft = 0, answer = Answer.VAGUE, firstEncounter = true
        )
        assertEquals(1, stage)
        assertEquals(RELEARN_PASSES_VAGUE, passes)
    }

    @Test
    fun `a word forgotten at first sight enters at one day and needs four passes`() {
        assertEquals(1L, INTERVAL_DAYS[entryStage(Answer.FORGET)])
        val (stage, passes) = Scheduler.advance(
            stage = 0, relearnLeft = 0, answer = Answer.FORGET, firstEncounter = true
        )
        assertEquals(0, stage)
        assertEquals(RELEARN_PASSES_FORGET, passes)
    }

    // ---------- relearn passes ----------

    @Test
    fun `vague asks for two more passes and forget for four`() {
        assertEquals(2, Scheduler.relearnTarget(Answer.VAGUE))
        assertEquals(4, Scheduler.relearnTarget(Answer.FORGET))
        assertEquals(0, Scheduler.relearnTarget(Answer.KNOW))
    }

    @Test
    fun `a mature card is not re-entered it climbs one stage and does not pass the top`() {
        val max = INTERVAL_DAYS.size - 1
        assertEquals(6 to 0, Scheduler.advance(5, 0, Answer.KNOW, firstEncounter = false))
        assertEquals(max to 0, Scheduler.advance(max, 0, Answer.KNOW, firstEncounter = false))
    }

    @Test
    fun `failing a mature card applies the rule for that answer`() {
        // Vague steps back two stages: 30 days -> 7 days (stage 5 -> 3).
        assertEquals(3 to 2, Scheduler.advance(5, 0, Answer.VAGUE, firstEncounter = false))
        assertEquals(6 to 2, Scheduler.advance(8, 0, Answer.VAGUE, firstEncounter = false))
        // Forget resets the card outright to the 1-day stage, wherever it was.
        assertEquals(0 to 4, Scheduler.advance(5, 0, Answer.FORGET, firstEncounter = false))
        assertEquals(0 to 4, Scheduler.advance(6, 0, Answer.FORGET, firstEncounter = false))
        assertEquals(0 to 4, Scheduler.advance(8, 0, Answer.FORGET, firstEncounter = false))
        assertEquals(0 to 4, Scheduler.advance(1, 0, Answer.FORGET, firstEncounter = false))
    }

    @Test
    fun `a failure never drops below two days for vague or one day for forget`() {
        // Vague steps back two stages but stops at the 2-day stage.
        assertEquals(1 to 2, Scheduler.advance(3, 0, Answer.VAGUE, firstEncounter = false))
        assertEquals(1 to 2, Scheduler.advance(2, 0, Answer.VAGUE, firstEncounter = false))
        assertEquals(1 to 2, Scheduler.advance(1, 0, Answer.VAGUE, firstEncounter = false))
        // A card already below a floor must not be pushed back up by it.
        assertEquals(0 to 2, Scheduler.advance(0, 0, Answer.VAGUE, firstEncounter = false))
        assertEquals(0 to 4, Scheduler.advance(0, 0, Answer.FORGET, firstEncounter = false))
        assertEquals(0, Scheduler.advance(-9, 0, Answer.FORGET, firstEncounter = false).first)
    }

    @Test
    fun `each know during relearning consumes one pass without moving the stage`() {
        assertEquals(5 to 2, Scheduler.advance(5, 3, Answer.KNOW, firstEncounter = false))
        assertEquals(5 to 1, Scheduler.advance(5, 2, Answer.KNOW, firstEncounter = false))
        assertEquals(5 to 0, Scheduler.advance(5, 1, Answer.KNOW, firstEncounter = false))
    }

    @Test
    fun `failing again mid-relearn restarts the count at the full number`() {
        assertEquals(5 to RELEARN_PASSES_FORGET, Scheduler.advance(5, 2, Answer.FORGET, firstEncounter = false))
        assertEquals(5 to RELEARN_PASSES_VAGUE, Scheduler.advance(5, 2, Answer.VAGUE, firstEncounter = false))
        assertEquals(1 to RELEARN_PASSES_FORGET, Scheduler.advance(1, 1, Answer.FORGET, firstEncounter = false))
    }

    @Test
    fun `out of range stages are clamped instead of throwing`() {
        val max = INTERVAL_DAYS.size - 1
        assertEquals(0, Scheduler.advance(-5, 0, Answer.FORGET, firstEncounter = false).first)
        assertEquals(max, Scheduler.advance(99, 0, Answer.KNOW, firstEncounter = false).first)
        // Above the top, a failure works from the clamped top stage.
        assertEquals(max - 2, Scheduler.advance(99, 0, Answer.VAGUE, firstEncounter = false).first)
        assertEquals(0, Scheduler.advance(99, 0, Answer.FORGET, firstEncounter = false).first)
    }

    // ---------- persistence ----------

    @Test
    fun `a word known at first sight is scheduled seven days out`() {
        Scheduler.addWord("w1", "sensible", "明智的", null)
        val start = System.currentTimeMillis()

        val outcome = Scheduler.applyAnswer(Scheduler.due().single(), Answer.KNOW, now = start)
        assertFalse(outcome.requeue, "a known word does not come back this session")
        assertEquals(3, outcome.card.stage)
        assertEquals(start + 7 * 86_400_000L, nextReviewAt("w1"))
    }

    @Test
    fun `a word forgotten at first sight is requeued and stays due until the passes are done`() {
        Scheduler.addWord("w2", "obscure", null, null)
        val start = System.currentTimeMillis()

        val failed = Scheduler.applyAnswer(Scheduler.due().single(), Answer.FORGET, now = start)
        assertTrue(failed.requeue)
        assertEquals(0, failed.card.stage)
        assertEquals(RELEARN_PASSES_FORGET, failed.card.relearnLeft)
        assertEquals(start, nextReviewAt("w2"))
        assertEquals(RELEARN_PASSES_FORGET, relearnLeftInDb("w2"))

        var card = failed.card
        repeat(3) { card = Scheduler.applyAnswer(card, Answer.KNOW, now = start).card }
        assertEquals(1, card.relearnLeft)
        assertEquals(start, nextReviewAt("w2"))

        card = Scheduler.applyAnswer(card, Answer.KNOW, now = start).card
        assertEquals(0, card.relearnLeft)
        assertEquals(0, card.stage)
        assertEquals(start + 86_400_000L, nextReviewAt("w2"))
    }

    @Test
    fun `relearn progress survives closing and reopening the session`() {
        Scheduler.addWord("w3", "subtle", null, null)
        var card = Scheduler.applyAnswer(Scheduler.due().single(), Answer.VAGUE).card
        card = Scheduler.applyAnswer(card, Answer.KNOW).card
        assertEquals(1, card.relearnLeft)

        val reloaded = Scheduler.due().single()
        assertEquals(1, reloaded.relearnLeft, "unfinished relearn progress must persist")
        val finished = Scheduler.applyAnswer(reloaded, Answer.KNOW).card
        assertEquals(0, finished.relearnLeft)
        assertEquals(1, finished.stage)
    }

    @Test
    fun `a card that never failed keeps taking ordinary steps`() {
        Scheduler.addWord("w4", "plain", null, null)
        var card = Scheduler.due().single()
        repeat(4) {
            val outcome = Scheduler.applyAnswer(card, Answer.KNOW)
            assertFalse(outcome.requeue)
            assertEquals(0, outcome.card.relearnLeft)
            card = outcome.card
        }
        assertEquals(6, card.stage)
    }

    @Test
    fun `a card settled on the one day stage is not mistaken for a new word`() {
        Scheduler.addWord("w7", "settled", null, null)
        var card = Scheduler.applyAnswer(Scheduler.due().single(), Answer.FORGET).card
        repeat(RELEARN_PASSES_FORGET) { card = Scheduler.applyAnswer(card, Answer.KNOW).card }
        assertEquals(0, card.stage)
        assertEquals(0, card.relearnLeft)

        val again = Scheduler.applyAnswer(card, Answer.FORGET)
        assertTrue(again.requeue)
        assertEquals(0, again.card.stage)
        assertEquals(RELEARN_PASSES_FORGET, again.card.relearnLeft)
    }

    // ---------- queue placement ----------

    @Test
    fun `a failed card is pushed back five positions when the queue is long enough`() {
        assertEquals(8, Scheduler.pushBackIndex(queueSize = 9, removedAt = 3))
        assertEquals(5, Scheduler.pushBackIndex(queueSize = 20, removedAt = 0))
    }

    @Test
    fun `a failed card goes to the end when fewer than five cards remain`() {
        assertEquals(2, Scheduler.pushBackIndex(queueSize = 2, removedAt = 3))
        assertEquals(0, Scheduler.pushBackIndex(queueSize = 0, removedAt = 0))
        for (size in 0..40) {
            for (at in 0..40) {
                val index = Scheduler.pushBackIndex(size, at)
                assertTrue(index in 0..size, "index $index outside 0..$size for size=$size at=$at")
            }
        }
    }

    @Test
    fun `a failed card is never dropped even when it was the last one`() {
        Scheduler.addWord("solo", "solo", null, null)
        val outcome = Scheduler.applyAnswer(Scheduler.due().single(), Answer.FORGET)
        assertTrue(outcome.requeue)
        assertEquals(0, Scheduler.pushBackIndex(queueSize = 0, removedAt = 0))
    }

    // ---------- queue contents ----------

    @Test
    fun `due only returns cards whose review time has arrived`() {
        Scheduler.addWord("past", "past", null, null)
        Scheduler.addWord("future", "future", null, null)
        val now = System.currentTimeMillis()
        setNextReview("past", now - 1_000)
        setNextReview("future", now + 86_400_000)

        assertEquals(listOf("past"), Scheduler.due(now).map { it.vocId })
    }

    @Test
    fun `due carries the word content needed to render a card`() {
        Scheduler.addWord("w5", "sensible", "明智的", "ˈsɛnsəbl")
        Db.conn().prepareStatement(
            "UPDATE words SET sentence = ?, sentence_cn = ?, remethod = ? WHERE voc_id = ?"
        ).use { ps ->
            ps.setString(1, "She seems very sensible.")
            ps.setString(2, "她看起来很明智。")
            ps.setString(3, "sense + ible")
            ps.setString(4, "w5")
            ps.executeUpdate()
        }

        val card = Scheduler.due().single()
        assertEquals("sensible", card.spelling)
        assertEquals("明智的", card.meaning)
        assertEquals("ˈsɛnsəbl", card.phonetic)
        assertEquals("She seems very sensible.", card.sentence)
        assertEquals("她看起来很明智。", card.sentenceCn)
        assertEquals("sense + ible", card.remethod)
    }

    @Test
    fun `a new word is recognised as a first encounter and a reviewed one is not`() {
        Scheduler.addWord("w8", "fresh", null, null)
        val fresh = Scheduler.due().single()
        assertTrue(isFirstEncounter(fresh), "never answered counts as first encounter")

        val answered = Scheduler.applyAnswer(fresh, Answer.VAGUE).card
        assertFalse(isFirstEncounter(answered), "answered cards are never first encounters")

        // A database written before last_result was recorded leaves it null on a reviewed word;
        // the stage still proves it has been reviewed, so it must not re-enter.
        val legacy = Scheduler.due(Long.MAX_VALUE - 1).single()
        assertFalse(
            isFirstEncounter(legacy.copy(lastResult = null, stage = 4)),
            "a word already past the entry stages is not new",
        )
        assertTrue(
            isFirstEncounter(legacy.copy(lastResult = null, stage = 0)),
            "a null last_result on stage 0 is a genuinely new word",
        )
    }

    @Test
    fun `concurrent answers and queue reloads do not fail or corrupt the stage`() {
        val ids = (1..60).map { "c$it" }
        ids.forEach { Scheduler.addWord(it, "word-$it", null, null) }

        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = (1..6).map { t ->
            Thread {
                try {
                    repeat(25) {
                        Scheduler.due()
                        if (t % 2 == 0) {
                            val card = Scheduler.due().firstOrNull()
                            if (card != null) Scheduler.applyAnswer(card, Answer.KNOW)
                        } else {
                            Scheduler.addWord("t$t-$it", "extra-$t-$it", null, null)
                        }
                    }
                } catch (e: Throwable) {
                    failures.add(e)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertTrue(failures.isEmpty(), "concurrent access failed: ${failures.firstOrNull()}")
        assertTrue(learningCount() >= ids.size)
    }

    private fun learningCount(): Int =
        Db.conn().createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM learning").use { rs -> rs.next(); rs.getInt(1) }
        }

    private fun relearnLeftInDb(vocId: String): Int =
        Db.conn().prepareStatement("SELECT relearn_left FROM learning WHERE voc_id = ?").use { ps ->
            ps.setString(1, vocId)
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    private fun nextReviewAt(vocId: String): Long =
        Db.conn().prepareStatement("SELECT next_review_at FROM learning WHERE voc_id = ?").use { ps ->
            ps.setString(1, vocId)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }

    private fun setNextReview(vocId: String, at: Long) {
        Db.conn().prepareStatement("UPDATE learning SET next_review_at = ? WHERE voc_id = ?").use { ps ->
            ps.setLong(1, at)
            ps.setString(2, vocId)
            ps.executeUpdate()
        }
    }

    private fun uniqueDbDir(): String =
        dir.resolve("db-" + java.util.UUID.randomUUID()).toFile().apply { mkdirs() }.absolutePath
}