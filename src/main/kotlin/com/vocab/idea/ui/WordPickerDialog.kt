package com.vocab.idea.ui

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.vocab.idea.study.BookWord
import com.vocab.idea.study.WordBook
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.util.concurrent.Executors
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.ListSelectionEvent

/**
 * Word picker: one multi-select list, where the user can either tick words by hand or fill in a
 * count and let "随机选" tick that many of them at random. Either way the ticks are what gets
 * added, and the user confirms with 确定, so a random pick can always be adjusted before it is
 * committed.
 *
 * The word list is loaded on a background thread: querying a dictionary of several thousand
 * words on the EDT froze the IDE for the whole query.
 */
class WordPickerDialog(
    private val bookName: String,
) : DialogWrapper(true) {

    private val loader = Executors.newSingleThreadExecutor { r ->
        Thread(r, "vocab-word-picker-loader").apply { isDaemon = true }
    }

    private lateinit var listModel: DefaultListModel<BookWord>
    private lateinit var wordList: JBList<BookWord>
    private lateinit var filterField: JTextField
    private lateinit var countSpinner: JSpinner
    private lateinit var statusLabel: JLabel

    /** Words available before filtering; the filter only narrows what is shown. */
    private var words: List<BookWord> = emptyList()

    /** Set when the dialog closes, so a late background result does not touch a disposed UI. */
    private var closed = false

    /** Coalesces keystrokes; rebuilding the filter on every character was needlessly costly. */
    private val filterDebounce = Timer(120) { applyFilter() }.apply { isRepeats = false }

    init {
        title = "选择单词"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val palette = Vocabulary.palette()
        listModel = DefaultListModel()
        wordList = JBList(listModel).apply {
            selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
            visibleRowCount = 16
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>, value: Any?, index: Int, selected: Boolean, focused: Boolean
                ) = super.getListCellRendererComponent(
                    list, (value as? BookWord)?.spelling ?: "", index, selected, focused
                )
            }
        }
        Vocabulary.style(wordList, palette)

        filterField = JTextField().apply {
            toolTipText = "输入字母筛选列表"
        }
        countSpinner = JSpinner(SpinnerNumberModel(10, 1, 999, 1)).apply {
            // The default spinner is needlessly wide for a 3-digit number.
            preferredSize = Dimension(JBUI.scale(64), preferredSize.height)
            toolTipText = "随机勾选的个数"
        }
        statusLabel = JLabel("正在加载词库…")

        val randomButton = JButton("随机选").apply {
            toolTipText = "按左边填的个数，从当前列表里随机勾选，可再手动增减"
            addActionListener { randomSelect() }
        }
        val clearButton = JButton("清空勾选").apply {
            addActionListener { wordList.clearSelection() }
        }

        val topRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            add(JLabel("个数"))
            add(countSpinner)
            add(randomButton)
            add(clearButton)
        }

        val listPane = JBScrollPane(wordList).apply {
            preferredSize = Dimension(JBUI.scale(360), JBUI.scale(340))
        }

        val content = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.empty(8)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(topRow)
                add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
                    add(JLabel("筛选"))
                    filterField.preferredSize = Dimension(JBUI.scale(220), filterField.preferredSize.height)
                    add(filterField)
                })
            }, BorderLayout.NORTH)
            add(listPane, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }

        filterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = scheduleFilter()
            override fun removeUpdate(e: DocumentEvent?) = scheduleFilter()
            override fun changedUpdate(e: DocumentEvent?) = scheduleFilter()
        })
        wordList.addListSelectionListener { e: ListSelectionEvent ->
            if (!e.valueIsAdjusting) updateStatus()
        }

        setOKActionEnabled(false)
        loadWords()
        return content
    }

    private fun loadWords() {
        loader.execute {
            val loaded = runCatching { WordBook.remainingWords(bookName) }.getOrElse { emptyList() }
            SwingUtilities.invokeLater {
                if (closed) return@invokeLater
                words = loaded
                applyFilter()
                setOKActionEnabled(true)
            }
        }
    }

    private fun scheduleFilter() {
        filterDebounce.restart()
    }

    private fun applyFilter() {
        val query = filterField.text.trim().lowercase()
        val shown = if (query.isEmpty()) words else words.filter { it.spelling.lowercase().contains(query) }
        // Mutate the existing model: replacing it would discard the user's ticks.
        val selected = selectedWords().toSet()
        listModel.clear()
        shown.forEach { listModel.addElement(it) }
        restoreSelection(shown, selected)
        updateStatus()
    }

    /** Re-ticks whatever was ticked before a filter change, within the newly shown rows. */
    private fun restoreSelection(shown: List<BookWord>, previouslySelected: Set<BookWord>) {
        val indices = shown.indices.filter { shown[it] in previouslySelected }
        if (indices.isNotEmpty()) wordList.setSelectedIndices(indices.toIntArray())
    }

    /** Ticks [count] random entries from what the list currently shows. */
    private fun randomSelect() {
        val total = listModel.size()
        if (total == 0) {
            statusLabel.text = "列表为空，没有可选的单词"
            return
        }
        val count = (countSpinner.value as Int).coerceIn(1, total)
        val picked = (0 until total).shuffled().take(count).sorted()
        wordList.setSelectedIndices(picked.toIntArray())
        // Scroll to the first pick so the result is visible.
        wordList.ensureIndexIsVisible(picked.first())
    }

    private fun selectedWords(): List<BookWord> =
        wordList.selectedIndices.map { listModel.get(it) }.filterNotNull()

    private fun updateStatus() {
        val total = words.size
        statusLabel.text = when {
            total == 0 && !closed -> "这本词书里没有未学习的单词"
            else -> "共 $total 个未学单词，已勾选 ${wordList.selectedIndices.size} 个"
        }
    }

    /** Returns chosen voc_ids, or null if cancelled. */
    fun chosenVocIds(): List<String>? {
        show()
        if (exitCode != OK_EXIT_CODE) return null
        return selectedWords().map { it.vocId }
    }

    override fun doCancelAction() {
        closed = true
        filterDebounce.stop()
        loader.shutdownNow()
        super.doCancelAction()
    }

    override fun dispose() {
        closed = true
        filterDebounce.stop()
        loader.shutdownNow()
        super.dispose()
    }
}
