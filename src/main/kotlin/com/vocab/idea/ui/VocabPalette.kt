package com.vocab.idea.ui

import com.intellij.ui.JBColor
import java.awt.Color
import java.util.Locale

/**
 * Font size and colours for the tool window, resolved from the saved settings.
 *
 * Colours are stored as hex strings so they survive the XML round-trip, and an unparsable
 * value falls back to the default rather than breaking the panel.
 */
data class VocabPalette(
    val fontSize: Int,
    val spelling: Color,
    val meaning: Color,
    val sentence: Color,
    val mnemonic: Color,
)

object Vocabulary {
    const val MIN_FONT_SIZE = 10
    const val MAX_FONT_SIZE = 36

    val DEFAULT_SPELLING = "#2E7D32"
    val DEFAULT_MEANING = "#1A1A1A"
    val DEFAULT_SENTENCE = "#6A6A6A"
    val DEFAULT_MNEMONIC = "#8A6D3B"

    /** Current palette, read fresh so a settings change shows up on the next render. */
    fun palette(): VocabPalette {
        val state = VocabSettings.instance.state
        val size = state.fontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        return VocabPalette(
            fontSize = size,
            // JBColor keeps the text readable when the IDE switches to a dark theme.
            spelling = parseColor(state.spellingColor, DEFAULT_SPELLING, Color(0x2E, 0x7D, 0x32)),
            meaning = parseColor(state.meaningColor, DEFAULT_MEANING, Color(0x1A, 0x1A, 0x1A), dark = Color(0xE6, 0xE6, 0xE6)),
            sentence = parseColor(state.sentenceColor, DEFAULT_SENTENCE, Color(0x6A, 0x6A, 0x6A), dark = Color(0xB0, 0xB0, 0xB0)),
            mnemonic = parseColor(state.mnemonicColor, DEFAULT_MNEMONIC, Color(0x8A, 0x6D, 0x3B), dark = Color(0xC9, 0xA8, 0x6A)),
        )
    }

    /** `#RRGGBB` (or `RRGGBB`) to a colour; null when it cannot be parsed. */
    fun parseHex(value: String?): Color? {
        val text = value?.trim()?.removePrefix("#") ?: return null
        if (text.length != 6) return null
        val rgb = text.toIntOrNull(16) ?: return null
        return Color(rgb)
    }

    fun toHex(color: Color): String =
        String.format(Locale.ROOT, "#%02X%02X%02X", color.red, color.green, color.blue)

    /**
     * Styles a text component with the palette. [role] picks which colour applies.
     * [scale] multiplies the font size, so the word title can stand out from body text.
     */
    fun style(component: javax.swing.JComponent, palette: VocabPalette, role: Role = Role.MEANING, scale: Float = 1f) {
        component.font = component.font.deriveFont(palette.fontSize * scale)
        component.foreground = when (role) {
            Role.SPELLING -> palette.spelling
            Role.MEANING -> palette.meaning
            Role.SENTENCE -> palette.sentence
            Role.MNEMONIC -> palette.mnemonic
        }
    }

    enum class Role { SPELLING, MEANING, SENTENCE, MNEMONIC }

    private fun parseColor(value: String?, expected: String, light: Color, dark: Color = light): Color {
        val parsed = parseHex(value) ?: return JBColor(light, dark)
        // A saved value is used as-is; JBColor is only for the built-in defaults.
        return if (value?.trim().equals(expected, ignoreCase = true)) JBColor(light, dark) else parsed
    }
}
