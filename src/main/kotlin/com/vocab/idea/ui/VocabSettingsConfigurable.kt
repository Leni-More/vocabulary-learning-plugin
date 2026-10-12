package com.vocab.idea.ui

import com.intellij.openapi.options.Configurable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JColorChooser
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextField
import javax.swing.SpinnerNumberModel

/** Settings page: daily word count and the appearance of the tool window. */
class VocabSettingsConfigurable : Configurable {

    private val settings = VocabSettings.instance

    private lateinit var dailySpinner: JSpinner
    private lateinit var fontSpinner: JSpinner
    private lateinit var spellingField: JTextField
    private lateinit var meaningField: JTextField
    private lateinit var sentenceField: JTextField
    private lateinit var mnemonicField: JTextField
    private lateinit var preview: JLabel
    private var panel: JPanel? = null

    override fun createComponent(): JComponent {
        val state = settings.state

        dailySpinner = JSpinner(SpinnerNumberModel(state.dailyNewWords.coerceAtLeast(1), 1, 999, 1))
        fontSpinner = JSpinner(
            SpinnerNumberModel(
                state.fontSize.coerceIn(Vocabulary.MIN_FONT_SIZE, Vocabulary.MAX_FONT_SIZE),
                Vocabulary.MIN_FONT_SIZE,
                Vocabulary.MAX_FONT_SIZE,
                1,
            )
        )

        spellingField = JTextField(Vocabulary.migratedSpelling(state.spellingColor), 9)
        meaningField = JTextField(state.meaningColor, 9)
        sentenceField = JTextField(state.sentenceColor, 9)
        mnemonicField = JTextField(state.mnemonicColor, 9)

        preview = JLabel("预览：vocabulary　词汇　例句")

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)

            add(section("学习"))
            add(labelled("每日新词数量", dailySpinner))
            add(Box.createVerticalStrut(JBUI.scale(12)))

            add(section("外观"))
            add(labelled("字体大小", fontSpinner))
            add(Box.createVerticalStrut(JBUI.scale(6)))

            add(labeledRow("单词颜色", spellingField))
            add(labeledRow("释义颜色", meaningField))
            add(labeledRow("例句颜色", sentenceField))
            add(labeledRow("助记颜色", mnemonicField))
            add(Box.createVerticalStrut(JBUI.scale(8)))

            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(preview) })

            val refresh = JButton("刷新预览")
            refresh.addActionListener { updatePreview() }
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(refresh) })

            fontSpinner.addChangeListener { updatePreview() }
            listOf(spellingField, meaningField, sentenceField, mnemonicField).forEach { field ->
                field.addActionListener { updatePreview() }
            }
            updatePreview()
        }

        panel = JPanel(BorderLayout()).apply { add(content, BorderLayout.NORTH) }
        return panel!!
    }

    /** Re-reads the fields so the preview reflects what has been typed. */
    private fun updatePreview() {
        val size = (fontSpinner.value as Int).coerceIn(Vocabulary.MIN_FONT_SIZE, Vocabulary.MAX_FONT_SIZE)
        preview.font = preview.font.deriveFont(size.toFloat())
        // The preview shows the meaning colour, which is the one most used on a card.
        preview.foreground = Vocabulary.parseHex(meaningField.text) ?: Color(0x1A1A1A)
    }

    private fun labeledRow(label: String, field: JTextField): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(JLabel(label))
            add(field)
            add(JButton("选择…").apply {
                addActionListener {
                    val current = Vocabulary.parseHex(field.text) ?: Color.WHITE
                    val picked = JColorChooser.showDialog(panel, label, current)
                    if (picked != null) {
                        field.text = Vocabulary.toHex(picked)
                        updatePreview()
                    }
                }
            })
        }

    private fun section(title: String) = JLabel(title).apply {
        font = font.deriveFont(font.style or Font.BOLD)
        border = JBUI.Borders.emptyBottom(4)
    }

    private fun labelled(label: String, component: JComponent) =
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(JLabel(label))
            component.preferredSize = Dimension(JBUI.scale(80), component.preferredSize.height)
            add(component)
        }

    override fun isModified(): Boolean {
        val state = settings.state
        return (dailySpinner.value as Int) != state.dailyNewWords ||
            (fontSpinner.value as Int) != state.fontSize ||
            Vocabulary.migratedSpelling(spellingField.text) != Vocabulary.migratedSpelling(state.spellingColor) ||
            normalize(meaningField.text, Vocabulary.DEFAULT_MEANING) != normalize(state.meaningColor, Vocabulary.DEFAULT_MEANING) ||
            normalize(sentenceField.text, Vocabulary.DEFAULT_SENTENCE) != normalize(state.sentenceColor, Vocabulary.DEFAULT_SENTENCE) ||
            normalize(mnemonicField.text, Vocabulary.DEFAULT_MNEMONIC) != normalize(state.mnemonicColor, Vocabulary.DEFAULT_MNEMONIC)
    }

    override fun apply() {
        val state = settings.state
        state.dailyNewWords = dailySpinner.value as Int
        state.fontSize = fontSpinner.value as Int
        // An unusable value falls back to the default instead of being stored.
        state.spellingColor = normalize(spellingField.text, Vocabulary.DEFAULT_SPELLING)
        state.meaningColor = normalize(meaningField.text, Vocabulary.DEFAULT_MEANING)
        state.sentenceColor = normalize(sentenceField.text, Vocabulary.DEFAULT_SENTENCE)
        state.mnemonicColor = normalize(mnemonicField.text, Vocabulary.DEFAULT_MNEMONIC)
        VocabPanel.refreshOpenPanels()
    }

    override fun reset() {
        val state = settings.state
        dailySpinner.value = state.dailyNewWords
        fontSpinner.value = state.fontSize.coerceIn(Vocabulary.MIN_FONT_SIZE, Vocabulary.MAX_FONT_SIZE)
        spellingField.text = Vocabulary.migratedSpelling(state.spellingColor)
        meaningField.text = state.meaningColor
        sentenceField.text = state.sentenceColor
        mnemonicField.text = state.mnemonicColor
        if (::preview.isInitialized) updatePreview()
    }

    /** Keeps a valid stored value, otherwise falls back to the default. */
    private fun normalize(value: String?, default: String): String =
        Vocabulary.parseHex(value)?.let { Vocabulary.toHex(it) } ?: default

    override fun getDisplayName(): String = "vocabulary-learning"
}
