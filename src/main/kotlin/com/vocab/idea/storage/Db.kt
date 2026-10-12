package com.vocab.idea.storage

import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.ConcurrentHashMap

/**
 * SQLite access for the plugin.
 *
 * A JDBC [Connection] is not thread-safe, and this plugin touches the database from both the
 * EDT (answering a card) and pooled threads (importing a dictionary, reloading the queue).
 * Sharing one connection across those threads caused interleaved statements and "database is
 * locked" failures, so each thread gets its own connection instead. Every connection is kept
 * in [open] and closed together by [closeAll].
 */
object Db {

    private const val BUSY_TIMEOUT_MS = 5_000

    private val open: MutableMap<Thread, Connection> = ConcurrentHashMap()

    /** Connection belonging to the calling thread. */
    fun conn(): Connection {
        val thread = Thread.currentThread()
        open[thread]?.let { if (!it.isClosed) return it }
        val created = createConnection()
        // putIfAbsent/close-old guards against two threads racing to create this thread's
        // connection, which would otherwise leak whichever one lost the race.
        val previous = open.putIfAbsent(thread, created)
        if (previous == null) return created
        if (previous.isClosed) return created
        runCatching { created.close() }
        return previous
    }

    /**
     * Runs [work] inside a single transaction on the calling thread's connection.
     *
     * Either every statement in [work] is committed or none of it is, which is what keeps
     * multi-table writes (words + learning) from leaving orphaned rows behind.
     */
    fun <T> transaction(work: (Connection) -> T): T {
        val conn = conn()
        val previousAutoCommit = conn.autoCommit
        conn.autoCommit = false
        return try {
            val result = work(conn)
            conn.commit()
            result
        } catch (e: Throwable) {
            try {
                conn.rollback()
            } catch (rollbackFailure: Throwable) {
                e.addSuppressed(rollbackFailure)
            }
            throw e
        } finally {
            try {
                conn.autoCommit = previousAutoCommit
            } catch (restoreFailure: Throwable) {
                // A connection that cannot restore autocommit is unusable; drop it so the
                // next call on this thread builds a fresh one instead of silently reusing it.
                open.remove(Thread.currentThread())
                try {
                    conn.close()
                } catch (closeFailure: Throwable) {
                    restoreFailure.addSuppressed(closeFailure)
                }
            }
        }
    }

    /**
     * Closes every connection, leaving the object reusable: the next call to [conn] opens
     * fresh connections. Safe to call more than once.
     */
    fun closeAll() {
        open.values.forEach { runCatching { it.close() } }
        open.clear()
    }

    private fun createConnection(): Connection {
        val dir = System.getProperty("vocab.plugin.dir")
            ?: (System.getProperty("user.home") + "/.vocab-idea")
        java.io.File(dir).mkdirs()
        Class.forName("org.sqlite.JDBC")
        val conn = DriverManager.getConnection("jdbc:sqlite:$dir/vocab.db?busy_timeout=$BUSY_TIMEOUT_MS")
        conn.createStatement().use { st ->
            // WAL lets readers run while a write is in flight, and the busy timeout makes the
            // remaining write/write overlap wait instead of failing outright.
            st.executeUpdate("PRAGMA journal_mode = WAL")
            createSchema(st)
        }
        return conn
    }

    /**
     * Idempotent schema setup. Runs for every new connection, which is also how the index is
     * added to databases created before it existed.
     */
    private fun createSchema(st: java.sql.Statement) {
        st.executeUpdate(
            """
            CREATE TABLE IF NOT EXISTS words(
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
            CREATE TABLE IF NOT EXISTS learning(
                voc_id TEXT PRIMARY KEY REFERENCES words(voc_id),
                stage INTEGER NOT NULL DEFAULT 0,
                next_review_at INTEGER NOT NULL,
                last_result TEXT,
                added_at INTEGER NOT NULL,
                relearn_left INTEGER NOT NULL DEFAULT 0
            )""".trimIndent()
        )
        addColumnIfMissing(st, "learning", "relearn_left", "INTEGER NOT NULL DEFAULT 0")
        // due() filters on next_review_at and sorts by it; without this it is a full scan.
        st.executeUpdate(
            "CREATE INDEX IF NOT EXISTS idx_learning_next_review ON learning(next_review_at)"
        )
    }

    /**
     * Adds a column to a table created by an older version of the plugin.
     * `ALTER TABLE ... ADD COLUMN` has no `IF NOT EXISTS`, so the existing columns are checked
     * first; without this the upgrade would throw on every database that already has the table.
     */
    private fun addColumnIfMissing(
        st: java.sql.Statement,
        table: String,
        column: String,
        definition: String,
    ) {
        val existing = mutableSetOf<String>()
        st.executeQuery("PRAGMA table_info($table)").use { rs ->
            while (rs.next()) existing.add(rs.getString("name").lowercase())
        }
        if (column.lowercase() !in existing) {
            st.executeUpdate("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }
}
