package com.vocab.idea.study

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Checks whether a file the user picked can actually be imported.
 *
 * The plugin reads one JSON object per line (NDJSON). A plain JSON array — which is what most
 * word lists on the web look like — would otherwise import as zero words and look like a
 * silent no-op, so it is rejected up front with a message that says what to do instead.
 */
object DictionaryFormat {

    /** Enough lines to tell the formats apart without reading a large file. */
    private const val SAMPLE_LINES = 20

    /** How much of the file to look at when deciding whether it is JSON or NDJSON. */
    private const val WHOLE_FILE_SNIFF_CHARS = 65_536

    sealed interface Verdict {
        /** The file starts with at least one usable NDJSON entry. */
        data class Ok(val sampleEntries: Int) : Verdict

        data class Unusable(val reason: String) : Verdict
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun inspect(file: File): Verdict {
        val sample = sampleLines(file)
        if (sample.isEmpty()) return Verdict.Unusable("文件是空的，或者没有可读内容。")

        // A single JSON value wrapping the whole file is a word list, not a line-per-word file.
        // A one-line file is ambiguous: it parses as one object, but it may simply be a very
        // small NDJSON book whose only line happens to be an entry.
        when (val whole = readWholeFileAsJson(file)) {
            is JsonArray -> return Verdict.Unusable(
                "这个文件是一个 JSON 数组，本插件需要每行一个单词（NDJSON）。" +
                    "可以用 jq 转换：jq -c '.[]' 你的文件.json > 新文件.json",
            )
            is JsonObject -> if (!looksLikeEntry(sample.first())) {
                return Verdict.Unusable(
                    "这个文件是一个完整的 JSON 对象，不是每行一个单词（NDJSON）。",
                )
            }
            else -> Unit
        }

        val usable = sample.count { looksLikeEntry(it) }
        return if (usable > 0) {
            Verdict.Ok(usable)
        } else {
            Verdict.Unusable(
                "没有解析出任何单词。需要 kajweb/dict 格式的词库：每行一个 JSON，" +
                    "并且包含 headWord 字段。",
            )
        }
    }

    /**
     * Parses the head of the file as one JSON value, or null when it is not a single value —
     * which is exactly the case for a valid NDJSON file.
     */
    private fun readWholeFileAsJson(file: File): kotlinx.serialization.json.JsonElement? = runCatching {
        val head = file.inputStream().buffered().use { stream ->
            val buffer = CharArray(WHOLE_FILE_SNIFF_CHARS)
            val read = stream.reader(Charsets.UTF_8).read(buffer)
            if (read <= 0) "" else String(buffer, 0, read)
        }
        json.parseToJsonElement(head.trim())
    }.getOrNull()

    /** True when a line is a JSON object carrying a spelling, i.e. an importable entry. */
    fun looksLikeEntry(line: String): Boolean = runCatching {
        val obj = json.parseToJsonElement(line) as? JsonObject ?: return false
        // JsonNull.content reports the literal text "null", so it must be excluded explicitly:
        // reading it as a string would accept a headWord of null.
        val raw = obj["headWord"]
        if (raw == null || raw is JsonNull) return false
        val spelling = (raw as? JsonPrimitive)?.content
        !spelling.isNullOrBlank()
    }.getOrDefault(false)

    /** First non-blank lines, capped so a huge file is not read into memory. */
    private fun sampleLines(file: File): List<String> =
        file.useLines { lines ->
            lines.filter { it.isNotBlank() }.take(SAMPLE_LINES).toList()
        }
}
