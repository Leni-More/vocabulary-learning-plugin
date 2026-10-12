package com.vocab.idea.ui

import com.intellij.ui.JBColor
import java.awt.Color
import java.util.Locale
import javax.swing.UIManager

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

    /** The word line defaults to the button grey, so this is only the stored fallback value. */
    val DEFAULT_SPELLING = "#5A5A5A"
    val DEFAULT_MEANING = "#1A1A1A"
    val DEFAULT_SENTENCE = "#6A6A6A"
    val DEFAULT_MNEMONIC = "#8A6D3B"

    /** The green the word line used before it started following the buttons. */
    const val LEGACY_DEFAULT_SPELLING = "#2E7D32"

    /** Current palette, read fresh so a settings change shows up on the next render. */
    fun palette(): VocabPalette {
        val state = VocabSettings.instance.state
        val size = state.fontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        return VocabPalette(
            fontSize = size,
            spelling = spellingColor(state.spellingColor),
            // JBColor keeps the text readable when the IDE switches to a dark theme.
            meaning = parseColor(state.meaningColor, DEFAULT_MEANING, Color(0x1A, 0x1A, 0x1A), dark = Color(0xE6, 0xE6, 0xE6)),
            sentence = parseColor(state.sentenceColor, DEFAULT_SENTENCE, Color(0x6A, 0x6A, 0x6A), dark = Color(0xB0, 0xB0, 0xB0)),
            mnemonic = parseColor(state.mnemonicColor, DEFAULT_MNEMONIC, Color(0x8A, 0x6D, 0x3B), dark = Color(0xC9, 0xA8, 0x6A)),
        )
    }

    /**
     * The word and its phonetic are painted in the same colour as the answer buttons.
     *
     * Reading it from the look and feel instead of hard-coding a hex keeps the two identical in
     * every theme. The old green default is treated as "never picked a colour" too, so an
     * upgrade does not keep painting the word green.
     */
    private fun spellingColor(stored: String?): Color {
        val hex = migratedSpelling(stored)
        return if (hex == DEFAULT_SPELLING) buttonForeground() else parseHex(hex) ?: buttonForeground()
    }

    /**
     * The spelling colour as stored, with the old green default rewritten to the current one, so
     * the settings field shows the value that is actually in use.
     */
    fun migratedSpelling(stored: String?): String {
        val hex = parseHex(stored)?.let { toHex(it) } ?: return DEFAULT_SPELLING
        return if (hex == LEGACY_DEFAULT_SPELLING) DEFAULT_SPELLING else hex
    }

    /** Whatever the current theme paints button labels with. */
    fun buttonForeground(): Color =
        UIManager.getColor("Button.foreground")
            ?: JBColor(Color(0x5A, 0x5A, 0x5A), Color(0xBB, 0xBB, 0xBB))

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
