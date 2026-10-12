package com.vocab.idea.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.util.ui.JBUI
import com.vocab.idea.study.Answer
import com.vocab.idea.study.DictionaryFormat
import com.vocab.idea.study.DueCard
import com.vocab.idea.study.Scheduler
import com.vocab.idea.study.WordBook
import java.awt.BorderLayout
import java.awt.GridLayout
import java.util.Collections
import java.util.WeakHashMap
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.border.EmptyBorder

class VocabToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.component.add(VocabPanel(project))
    }
}

/**
 * 学习面板：选词 → 逐张显示卡片 → 作答后自动推进。
 */
class VocabPanel(private val project: Project) : JPanel() {

    private var queue = mutableListOf<DueCard>()
    private var pos = 0
    private var revealed = false

    /**
     * 选词对话框读取的词书名，由最近一次导入决定。
     * 为 null 表示这次启动还没导入过词库，此时应提示用户去导入。
     */
    private var currentBook: String? = null

    private val progressLabel = JLabel(" ")
    private val wordTitle = JTextArea(2, 10)
    private val meaningArea = JTextArea(3, 10)
    private val sentenceArea = JTextArea(3, 10)
    private val remethodArea = JTextArea(2, 10)

    private val showBtn = JButton("显示答案")
    private val knowBtn = JButton("认识")
    private val vagueBtn = JButton("模糊")
    private val forgetBtn = JButton("忘记")

    init {
        openPanels.add(this)

        layout = BorderLayout(0, JBUI.scale(8))
        border = EmptyBorder(JBUI.insets(10))

        listOf(wordTitle, meaningArea, sentenceArea, remethodArea).forEach { area ->
            area.isEditable = false
            area.isOpaque = false
            area.border = null
            area.lineWrap = true
            area.wrapStyleWord = true
        }

        val topRow = JPanel(GridLayout(1, 3, JBUI.scale(4), 0)).apply {
            add(JButton("选择单词").apply { addActionListener { pickWords() } })
            add(JButton("导入词典").apply {
                toolTipText = "选择你下载好的 NDJSON 词库文件（每行一个单词）"
                addActionListener { importDictionary() }
            })
            add(progressLabel)
        }

        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(Box.createVerticalGlue())
            add(wordTitle)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(meaningArea)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(sentenceArea)
            add(Box.createVerticalStrut(JBUI.scale(8)))
            add(remethodArea)
            add(Box.createVerticalGlue())
        }

        val bottom = JPanel(GridLayout(1, 4, JBUI.scale(4), 0)).apply {
            add(showBtn)
            add(knowBtn)
            add(vagueBtn)
            add(forgetBtn)
        }

        add(topRow, BorderLayout.NORTH)
        add(center, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)

        showBtn.addActionListener { revealed = true; renderCurrent() }
        knowBtn.addActionListener { answer(Answer.KNOW) }
        vagueBtn.addActionListener { answer(Answer.VAGUE) }
        forgetBtn.addActionListener { answer(Answer.FORGET) }

        loadQueue()
    }

    /**
     * 让用户挑选自己下载好的 NDJSON 词库文件并导入。
     *
     * 词库不随插件分发（体积太大），所以这里只有一条路径：选文件。
     * 文件名会作为词书名。
     */
    private fun importDictionary() {
        val chosenFile = FileChooser.chooseFile(
            FileChooserDescriptorFactory.createSingleFileDescriptor("json")
                .withTitle("选择词典文件")
                .withDescription("需要每行一个单词的 NDJSON 词库（kajweb/dict 格式）"),
            project,
            null,
        ) ?: return

        val file = runCatching { VfsUtilCore.virtualToIoFile(chosenFile) }.getOrNull()
        if (file == null) {
            wordTitle.text = "无法读取这个文件，请换一个位置再试。"
            return
        }

        val verdict = DictionaryFormat.inspect(file)
        if (verdict is DictionaryFormat.Verdict.Unusable) {
            wordTitle.text = "这个文件无法导入：${verdict.reason}"
            return
        }

        val book = file.nameWithoutExtension
        runImport {
            val report = progressReporter()
            // The file name becomes the book name, so the picker looks up this book afterwards.
            currentBook = book
            WordBook.importNdjson(file, report)
        }
    }

    private fun progressReporter(): (Int) -> Unit = { done ->
        ApplicationManager.getApplication().invokeLater {
            wordTitle.text = "正在导入… $done 个"
        }
    }

    /**
     * Runs [work] off the EDT and reports the outcome. Importing parses and writes thousands of
     * rows, so doing it on the EDT would freeze the IDE.
     */
    private fun runImport(work: () -> Int) {
        wordTitle.text = "正在导入…"
        setButtonsEnabled(false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val imported = runCatching { work() }.getOrElse { error ->
                ApplicationManager.getApplication().invokeLater {
                    wordTitle.text = "导入失败：${error.message}"
                    setButtonsEnabled(true)
                }
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater {
                wordTitle.text = "已导入 $imported 个单词（词书：$currentBook）。"
                loadQueue()
            }
        }
    }

    private fun pickWords() {
        val book = currentBook
        if (book == null) {
            wordTitle.text = "还没有导入词库，请先点「导入词典」选择下载好的词库文件。"
            return
        }
        val dialog = WordPickerDialog(book)
        val chosen = dialog.chosenVocIds() ?: return
        if (chosen.isEmpty()) {
            wordTitle.text = "没有勾选任何单词。"
            return
        }
        wordTitle.text = "正在添加…"
        setButtonsEnabled(false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val added = WordBook.addWords(chosen)
            ApplicationManager.getApplication().invokeLater {
                loadQueue()
                if (added == 0) wordTitle.text = "没有新增单词（都已在学习队列里）。"
            }
        }
    }

    private fun loadQueue() {
        setButtonsEnabled(false)
        ApplicationManager.getApplication().executeOnPooledThread {
            val due = Scheduler.due().toMutableList()
            ApplicationManager.getApplication().invokeLater {
                queue = due
                pos = 0
                revealed = false
                renderCurrent()
            }
        }
    }

    private fun renderCurrent() {
        applyAppearance()
        val total = queue.size
        progressLabel.text = if (total == 0) "今日已全部完成" else "${pos + 1}/$total"
        if (pos !in queue.indices) {
            wordTitle.text = if (total == 0) "今日已全部完成！" else "本轮学习结束，下次再见。"
            meaningArea.text = ""
            sentenceArea.text = ""
            remethodArea.text = ""
            setButtonsEnabled(false)
            return
        }
        setButtonsEnabled(true)
        val card = queue[pos]
        wordTitle.text = card.spelling + (card.phonetic?.let { "   $it" } ?: "")
        if (!revealed) {
            meaningArea.text = ""
            sentenceArea.text = ""
            remethodArea.text = ""
            return
        }
        meaningArea.text = card.meaning ?: ""
        sentenceArea.text = listOfNotNull(card.sentence, card.sentenceCn?.let { "—— $it" }).joinToString("\n")
        remethodArea.text = card.remethod?.let { "助记：$it" } ?: ""
    }

    /** 从设置里读取字体大小和颜色，每次渲染时重新套用。 */
    private fun applyAppearance() {
        val palette = Vocabulary.palette()
        Vocabulary.style(wordTitle, palette, Vocabulary.Role.SPELLING, scale = 1.4f)
        Vocabulary.style(meaningArea, palette, Vocabulary.Role.MEANING)
        Vocabulary.style(sentenceArea, palette, Vocabulary.Role.SENTENCE)
        Vocabulary.style(remethodArea, palette, Vocabulary.Role.MNEMONIC)
        progressLabel.font = progressLabel.font.deriveFont(palette.fontSize.toFloat())
        listOf(showBtn, knowBtn, vagueBtn, forgetBtn).forEach {
            it.font = it.font.deriveFont(palette.fontSize.toFloat())
        }
        border = EmptyBorder(JBUI.insets(10))
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        showBtn.isEnabled = enabled && !revealed
        knowBtn.isEnabled = enabled
        vagueBtn.isEnabled = enabled
        forgetBtn.isEnabled = enabled
    }

    private fun answer(a: Answer) {
        if (pos !in queue.indices) return
        val card = queue[pos]
        // 调度器返回真正写库的那张卡。用原来的卡会让档位过期，下一次作答就会按错误档位计算。
        val outcome = Scheduler.applyAnswer(card, a)

        queue.removeAt(pos)
        if (outcome.requeue) {
            // 失败后往后推固定几位；剩余不足就放到队尾。
            queue.add(Scheduler.pushBackIndex(queue.size, pos), outcome.card)
        }
        revealed = false
        if (pos >= queue.size) pos = queue.size
        renderCurrent()
    }

    companion object {
        /** 已打开的面板，设置变更后用来立即刷新外观。 */
        private val openPanels: MutableSet<VocabPanel> = Collections.newSetFromMap(WeakHashMap())

        /** 设置页应用后调用，让已打开的工具窗口立刻套用新的字体与颜色。 */
        fun refreshOpenPanels() {
            ApplicationManager.getApplication().invokeLater {
                openPanels.toList().forEach { it.renderCurrent() }
            }
        }
    }
}
