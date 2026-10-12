package com.vocab.idea.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.Color

class VocabPaletteTest {

    @Test
    fun `hex colours parse with and without the leading hash`() {
        assertEquals(Color(0x2E, 0x7D, 0x32), Vocabulary.parseHex("#2E7D32"))
        assertEquals(Color(0x2E, 0x7D, 0x32), Vocabulary.parseHex("2E7D32"))
        assertEquals(Color(0x2E, 0x7D, 0x32), Vocabulary.parseHex("  #2e7d32  "))
        assertEquals(Color.BLACK, Vocabulary.parseHex("000000"))
        assertEquals(Color.WHITE, Vocabulary.parseHex("#ffffff"))
    }

    @Test
    fun `an unusable colour falls back instead of throwing`() {
        // A half-typed value in the settings field must never break the panel.
        assertNull(Vocabulary.parseHex(null))
        assertNull(Vocabulary.parseHex(""))
        assertNull(Vocabulary.parseHex("#12345"))
        assertNull(Vocabulary.parseHex("#1234567"))
        assertNull(Vocabulary.parseHex("#GGGGGG"))
        assertNull(Vocabulary.parseHex("not a colour"))
    }

    @Test
    fun `colours round-trip through the stored hex form`() {
        val colors = listOf(
            Color(0x00, 0x00, 0x00),
            Color(0xFF, 0xFF, 0xFF),
            Color(0x2E, 0x7D, 0x32),
            Color(0x8A, 0x6D, 0x3B),
        )
        colors.forEach { color ->
            assertEquals(color, Vocabulary.parseHex(Vocabulary.toHex(color)), "round trip failed for $color")
        }
    }

    @Test
    fun `the documented defaults are all parseable`() {
        listOf(
            Vocabulary.DEFAULT_SPELLING,
            Vocabulary.DEFAULT_MEANING,
            Vocabulary.DEFAULT_SENTENCE,
            Vocabulary.DEFAULT_MNEMONIC,
        ).forEach { value ->
            assertEquals(6, Vocabulary.toHex(Vocabulary.parseHex(value)!!).length - 1)
        }
    }

    @Test
    fun `the font size limits are usable`() {
        assertEquals(10, Vocabulary.MIN_FONT_SIZE)
        assertEquals(36, Vocabulary.MAX_FONT_SIZE)
    }
}
