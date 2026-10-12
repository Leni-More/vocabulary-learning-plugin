package com.vocab.idea.study

import com.intellij.openapi.diagnostic.Logger
import com.vocab.idea.storage.Db
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

data class BookWord(
    val vocId: String,
    val spelling: String,
    val meaning: String?,
)

/** Imports kajweb/dict style NDJSON word books and enqueues words into the study flow. */
object WordBook {

    private val json = Json { ignoreUnknownKeys = true }

    private val LOG = Logger.getInstance(WordBook::class.java)

    /**
     * Lines committed per transaction. Committing in chunks keeps the import fast (one fsync
     * per chunk instead of per word) while still capping how much work a failure can undo.
     */
    private const val CHUNK_SIZE = 500

    /** Minimum words between two progress callbacks. */
    private const val PROGRESS_EVERY = 5_000

    private val INSERT_WORD =
        """
        INSERT OR REPLACE INTO words(voc_id, spelling, phonetic, meaning, sentence, sentence_cn, remethod)
        VALUES (?,?,?,?,?,?,?)
        """.trimIndent()

    /**
     * Imports the dictionary in [jsonFile], reporting progress through [onProgress] as words are
     * committed. Returns the number of words written.
     */
    fun importNdjson(jsonFile: File, onProgress: (Int) -> Unit = {}): Int =
        jsonFile.inputStream().buffered().use { stream ->
            importNdjson(stream, jsonFile.nameWithoutExtension, onProgress)
        }

    /**
     * Imports NDJSON from [stream]. [bookName] becomes the prefix of every word id, so it must
     * stay stable: changing it would orphan the words already queued under the old prefix.
     *
     * Progress is reported at most every [PROGRESS_EVERY] words (and once at the end) because
     * each callback ends up on the EDT.
     */
    fun importNdjson(stream: java.io.InputStream, bookName: String, onProgress: (Int) -> Unit = {}): Int {
        val book = bookName
        var count = 0
        var unparsed = 0
        var lastReported = 0
        val batch = ArrayList<String>(CHUNK_SIZE)

        fun flush(lines: List<String>, finalChunk: Boolean) {
            if (lines.isEmpty()) return
            val result = writeChunk(book, lines)
            count += result.written
            unparsed += result.total - result.written
            if (finalChunk || count - lastReported >= PROGRESS_EVERY) {
                lastReported = count
                onProgress(count)
            }
        }

        stream.bufferedReader().useLines { lines ->
            for (line in lines) {
                if (line.isBlank()) continue
                batch.add(line)
                if (batch.size >= CHUNK_SIZE) {
                    flush(batch, false)
                    batch.clear()
                }
            }
        }
        flush(batch, true)
        LOG.warn("Imported $count words from $book; $unparsed lines could not be parsed")
        return count
    }

    private fun writeChunk(book: String, lines: List<String>): ChunkResult {
        var written = 0
        Db.transaction { conn ->
            conn.prepareStatement(INSERT_WORD).use { ps ->
                for (line in lines) {
                    // A malformed line is skipped rather than aborting the whole import.
                    val parsed = parseLine(book, line) ?: continue
                    ps.setString(1, parsed.vocId)
                    ps.setString(2, parsed.spelling)
                    ps.setString(3, parsed.phonetic)
                    ps.setString(4, parsed.meaning)
                    ps.setString(5, parsed.sentence)
                    ps.setString(6, parsed.sentenceCn)
                    ps.setString(7, parsed.remethod)
                    ps.addBatch()
                }
                ps.executeBatch().forEach { result ->
                    // SQLite reports SUCCESS_NO_INFO (-2) for batched rows; count those too.
                    if (result >= 0 || result == java.sql.Statement.SUCCESS_NO_INFO) written++
                }
            }
        }
        return ChunkResult(written, lines.size)
    }

    private class ChunkResult(val written: Int, val total: Int)

    private class ParsedWord(
        val vocId: String,
        val spelling: String,
        val phonetic: String?,
        val meaning: String?,
        val sentence: String?,
        val sentenceCn: String?,
        val remethod: String?,
    )

    /** Parses one NDJSON line, or null when the line is not a usable word entry. */
    private fun parseLine(book: String, line: String): ParsedWord? {
        return try {
            val root = json.parseToJsonElement(line).jsonObject
            val spelling = root["headWord"]?.jsonPrimitive?.content
            if (spelling.isNullOrBlank()) return null
            val wordContent = root["content"]?.jsonObject
                ?.get("word")?.jsonObject
                ?.get("content")?.jsonObject

            val sentence = wordContent?.get("sentence")?.jsonObject
                ?.get("sentences")?.jsonArray
                ?.firstOrNull()?.jsonObject

            ParsedWord(
                vocId = "local:$book:$spelling",
                spelling = spelling,
                phonetic = wordContent?.get("usphone")?.jsonPrimitive?.content,
                meaning = wordContent?.get("trans")?.jsonArray
                    ?.firstOrNull()?.jsonObject
                    ?.get("tranCn")?.jsonPrimitive?.content,
                sentence = sentence?.get("sContent")?.jsonPrimitive?.content,
                sentenceCn = sentence?.get("sCn")?.jsonPrimitive?.content,
                remethod = wordContent?.get("remMethod")?.jsonObject
                    ?.get("val")?.jsonPrimitive?.content,
            )
        } catch (e: Exception) {
            // Bad JSON on one line must not abort the whole import.
            null
        }
    }

    /** All words of a book that are not yet in the study queue (never learned). */
    fun remainingWords(book: String): List<BookWord> {
        Db.conn().prepareStatement(
            """
            SELECT w.voc_id, w.spelling, w.meaning
            FROM words w LEFT JOIN learning l ON l.voc_id = w.voc_id
            WHERE w.voc_id LIKE ? ESCAPE '\' AND l.voc_id IS NULL
            ORDER BY w.spelling
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, likePrefix("local:$book:"))
            ps.executeQuery().use { rs ->
                val list = mutableListOf<BookWord>()
                while (rs.next()) list += BookWord(rs.getString(1), rs.getString(2), rs.getString(3))
                return list
            }
        }
    }

    /**
     * Builds a LIKE prefix pattern, escaping the wildcards so a book name containing `_`
     * (e.g. IELTSluan_2) matches only itself. Uses the escape character declared in the query.
     */
    private fun likePrefix(value: String): String = buildString {
        for (c in value) {
            if (c == '\\' || c == '%' || c == '_') append('\\')
            append(c)
        }
        append('%')
    }

    /** Adds already-chosen words (ordered) to the study queue, due immediately. */
    fun addWords(vocIds: List<String>): Int {
        if (vocIds.isEmpty()) return 0
        var added = 0
        Db.transaction { conn ->
            val spellingPs = conn.prepareStatement("SELECT spelling FROM words WHERE voc_id = ?")
            spellingPs.use { lookup ->
                val insertWord = conn.prepareStatement(
                    "INSERT OR IGNORE INTO words(voc_id, spelling, meaning, phonetic) VALUES (?,?,?,?)"
                )
                insertWord.use { wordPs ->
                    val insertLearning = conn.prepareStatement(
                        "INSERT OR IGNORE INTO learning(voc_id, stage, next_review_at, added_at) VALUES (?,0,?,?)"
                    )
                    insertLearning.use { learningPs ->
                        val now = System.currentTimeMillis()
                        for (vocId in vocIds) {
                            lookup.setString(1, vocId)
                            val spelling = lookup.executeQuery().use { rs ->
                                if (rs.next()) rs.getString(1) else null
                            } ?: continue

                            wordPs.setString(1, vocId)
                            wordPs.setString(2, spelling)
                            wordPs.setString(3, null)
                            wordPs.setString(4, null)
                            wordPs.executeUpdate()

                            learningPs.setString(1, vocId)
                            learningPs.setLong(2, now)
                            learningPs.setLong(3, now)
                            // executeUpdate reports 1 only when the row was genuinely new,
                            // which replaces the old count-before/count-after pair of queries.
                            if (learningPs.executeUpdate() > 0) added++
                        }
                    }
                }
            }
        }
        return added
    }
}
