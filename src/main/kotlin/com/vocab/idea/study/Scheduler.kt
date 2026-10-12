package com.vocab.idea.study

import com.vocab.idea.storage.Db

/**
 * Review intervals in **days**, indexed by stage.
 *
 * There is no same-day tier any more: the shortest interval is one day. Which stage a word
 * lands on is decided by how it went the first time it was seen, and by how often it has been
 * failed since — never by the clock within a session, where the queue order decides instead.
 */
val INTERVAL_DAYS: List<Long> = listOf(
    1,      // stage 0 - entry for a word that was forgotten at first sight
    2,      // stage 1 - entry for a word that was vague at first sight
    4,      // stage 2
    7,      // stage 3 - entry for a word that was known at first sight
    15,     // stage 4
    30,     // stage 5
    60,     // stage 6
    90,     // stage 7
    180,    // stage 8
)

private const val DAY_MS = 86_400_000L

/** Interval in milliseconds for [stage], clamped to the table. */
fun intervalMs(stage: Int): Long = INTERVAL_DAYS[stage.coerceIn(0, INTERVAL_DAYS.size - 1)] * DAY_MS

/**
 * True when this card has never been answered, so its first impression sets its stage.
 *
 * Both signals are required. [DueCard.lastResult] alone is not enough: databases written by an
 * earlier build never recorded it, so a long-reviewed word could still have a null there.
 * A stage above 0 proves the card was reviewed, while a brand new word always sits at stage 0.
 * Stage alone is not enough either, because a card that graduated from a first-sight failure
 * also settles on stage 0 and must be demotable.
 */
fun isFirstEncounter(card: DueCard): Boolean = card.lastResult == null && card.stage == 0

/** Stage a first-seen word enters at, based on how that first answer went. */
fun entryStage(answer: Answer): Int = when (answer) {
    Answer.KNOW -> 3    // 7 days
    Answer.VAGUE -> 1   // 2 days
    Answer.FORGET -> 0  // 1 day
}

/**
 * Stage a failure sends the card to.
 *
 * Forgetting resets the card outright to the 1-day stage — the whole ladder is forfeited, not
 * just a step or two. Being unsure steps back two stages, and never below the 2-day stage a
 * first-sight vague word would have entered at.
 */
fun failureStage(current: Int, answer: Answer): Int = when (answer) {
    Answer.FORGET -> 0                              // reset to 1 day
    Answer.VAGUE -> (current - 2).coerceAtLeast(1)  // two steps back, floor 2 days
    Answer.KNOW -> current
}

/**
 * How many more times a card must be answered KNOW before it leaves the relearn queue.
 *
 * VAGUE puts a card back 5 positions with 2 required passes; FORGET does the same with 4,
 * because failing to recall at all needs more repetition than a shaky recall.
 */
const val RELEARN_PASSES_VAGUE = 2
const val RELEARN_PASSES_FORGET = 4

/** How far back a failed card is pushed, in queue positions. */
const val PUSH_BACK_POSITIONS = 5

enum class Answer { KNOW, VAGUE, FORGET }

/** Result of answering one card. */
data class AnswerOutcome(
    /** The card carrying the state that was actually persisted; use this one from now on. */
    val card: DueCard,
    /** True when the card must go back into the current session's queue. */
    val requeue: Boolean,
)

object Scheduler {

    /** Number of further KNOW answers [answer] demands before the card graduates. */
    fun relearnTarget(answer: Answer): Int = when (answer) {
        Answer.VAGUE -> RELEARN_PASSES_VAGUE
        Answer.FORGET -> RELEARN_PASSES_FORGET
        // KNOW is not a failure, so it never starts a relearn cycle.
        Answer.KNOW -> 0
    }

    fun due(now: Long = System.currentTimeMillis()): List<DueCard> {
        Db.conn().prepareStatement(
            """
            SELECT l.voc_id, w.spelling, w.meaning, l.stage,
                   w.phonetic, w.sentence, w.sentence_cn, w.remethod, l.relearn_left, l.last_result
            FROM learning l JOIN words w ON w.voc_id = l.voc_id
            WHERE l.next_review_at <= ?
            ORDER BY l.next_review_at ASC
            """.trimIndent()
        ).use { ps ->
            ps.setLong(1, now)
            ps.executeQuery().use { rs ->
                val list = mutableListOf<DueCard>()
                while (rs.next()) {
                    list += DueCard(
                        vocId = rs.getString(1),
                        spelling = rs.getString(2),
                        meaning = rs.getString(3),
                        stage = rs.getInt(4),
                        phonetic = rs.getString(5),
                        sentence = rs.getString(6),
                        sentenceCn = rs.getString(7),
                        remethod = rs.getString(8),
                        relearnLeft = rs.getInt(9),
                        lastResult = rs.getString(10),
                    )
                }
                return list
            }
        }
    }

    /** The stage and relearn count an answer produces. Pure, so the rules stay easy to test. */
    fun advance(
        stage: Int,
        relearnLeft: Int,
        answer: Answer,
        firstEncounter: Boolean,
    ): Pair<Int, Int> {
        val maxStage = INTERVAL_DAYS.size - 1
        val current = stage.coerceIn(0, maxStage)

        // A word seen for the first time is placed by its first impression instead of climbing
        // from the bottom of the ladder.
        if (firstEncounter) {
            val passes = if (answer == Answer.KNOW) 0 else relearnTarget(answer)
            return entryStage(answer) to passes
        }

        // Still working off a failure: keep the stage put and just consume a pass.
        if (relearnLeft > 0) {
            if (answer != Answer.KNOW) {
                // Failed again mid-relearn, so the count restarts for the new answer.
                return current to relearnTarget(answer)
            }
            val remaining = relearnLeft - 1
            // The last pass graduates the card without moving the stage: the demotion already
            // happened when it was failed, and it should not be handed back a stage for that.
            return if (remaining == 0) current to 0 else current to remaining
        }

        return when (answer) {
            // Recognised on the day it came up: one stage further out.
            Answer.KNOW -> (current + 1).coerceAtMost(maxStage) to 0
            // Not recognised: the failure stage for this answer. A card already below what the
            // rule allows is left where it is rather than being pushed back up.
            Answer.VAGUE, Answer.FORGET -> {
                val failed = failureStage(current, answer)
                failed.coerceAtMost(current) to relearnTarget(answer)
            }
        }
    }

    /**
     * Applies one answer and persists the resulting state.
     *
     * A failure steps the card back one stage instead of clearing its progress, and demands
     * more KNOW answers before it settles. A card that still has passes left stays due
     * immediately, so an unfinished relearn cycle can be continued in a later session rather
     * than being skipped until its long interval elapses.
     */
    fun applyAnswer(card: DueCard, answer: Answer, now: Long = System.currentTimeMillis()): AnswerOutcome {
        val firstEncounter = isFirstEncounter(card)
        val (nextStage, nextRelearnLeft) = advance(card.stage, card.relearnLeft, answer, firstEncounter)
        val requeue = answer != Answer.KNOW
        // While relearning is unfinished the card must remain available, so it is parked at
        // "now"; the stage interval only starts once the passes are used up.
        val delay = if (nextRelearnLeft > 0) 0L else intervalMs(nextStage)

        Db.conn().prepareStatement(
            "UPDATE learning SET stage = ?, next_review_at = ?, last_result = ?, relearn_left = ? WHERE voc_id = ?"
        ).use { ps ->
            ps.setInt(1, nextStage)
            ps.setLong(2, now + delay)
            ps.setString(3, answer.name)
            ps.setInt(4, nextRelearnLeft)
            ps.setString(5, card.vocId)
            ps.executeUpdate()
        }
        return AnswerOutcome(
            card.copy(stage = nextStage, relearnLeft = nextRelearnLeft, lastResult = answer.name),
            requeue,
        )
    }

    /**
     * Where a failed card re-enters [queue], given the position it was removed from.
     *
     * The card is pushed back [PUSH_BACK_POSITIONS] places, or appended to the end when fewer
     * cards than that remain. No clock time is consulted: inside a session the queue position
     * alone decides when a card comes back.
     */
    fun pushBackIndex(queueSize: Int, removedAt: Int): Int =
        (removedAt + PUSH_BACK_POSITIONS).coerceIn(0, queueSize.coerceAtLeast(0))

    fun addWord(vocId: String, spelling: String, meaning: String?, phonetic: String?) {
        val now = System.currentTimeMillis()
        // One transaction so a word can never exist without its learning row.
        Db.transaction { conn ->
            conn.prepareStatement(
                "INSERT OR IGNORE INTO words(voc_id, spelling, meaning, phonetic) VALUES (?,?,?,?)"
            ).use { ps ->
                ps.setString(1, vocId); ps.setString(2, spelling)
                ps.setString(3, meaning); ps.setString(4, phonetic)
                ps.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT OR IGNORE INTO learning(voc_id, stage, next_review_at, added_at, relearn_left) VALUES (?,0,?,?,0)"
            ).use { ps ->
                ps.setString(1, vocId); ps.setLong(2, now); ps.setLong(3, now)
                ps.executeUpdate()
            }
        }
    }
}

data class DueCard(
    val vocId: String,
    val spelling: String,
    val meaning: String?,
    val stage: Int,
    val phonetic: String? = null,
    val sentence: String? = null,
    val sentenceCn: String? = null,
    val remethod: String? = null,
    /** Remaining KNOW answers before this card leaves the relearn queue; 0 when not relearning. */
    val relearnLeft: Int = 0,
    /** Last answer recorded for this card, or null when it has never been answered. */
    val lastResult: String? = null,
)
