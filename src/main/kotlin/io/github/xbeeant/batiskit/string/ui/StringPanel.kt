package io.github.xbeeant.batiskit.string.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.xbeeant.batiskit.ui.ToolWindowShell
import io.github.xbeeant.batiskit.ui.UiTokens
import io.github.xbeeant.batiskit.ui.UiTokens.Density
import io.github.xbeeant.batiskit.ui.components.ContentSwitcher
import io.github.xbeeant.batiskit.ui.components.SearchField
import io.github.xbeeant.batiskit.ui.components.StatusBarPanel
import io.github.xbeeant.batiskit.string.StringOp
import io.github.xbeeant.batiskit.string.StringOpException
import io.github.xbeeant.batiskit.string.StringOps
import io.github.xbeeant.batiskit.string.StringOptions
import io.github.xbeeant.batiskit.string.StringTransform
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent

/**
 * 「String Manipulation」工具窗口：左边挑操作（带逐条预览），右边输入 / 结果实时联动。
 *
 * 与编辑器右键的区别是这里能调选项：拼接 / 拆分分隔符、忽略大小写、降序、逐行 trim、
 * 跳过空行；适合一次处理一批文本，结果只读、复制即用，也可以「结果回填输入」接着串下一个操作。
 */
class StringPanel(private val project: Project) : Disposable {

    private val input = JBTextArea()
    private val result = JBTextArea()
    private val search = SearchField("搜索转换（驼峰 / base64 / 去重 …）", ::applyFilter)
    private val model = CollectionListModel<StringOp>()
    private val list = JBList(model)
    private val renderer = StringOpRenderer(::listPreview)

    private val separator = JBTextField()
    private val splitSeparator = JBTextField()
    private val ignoreCaseBox = JBCheckBox("忽略大小写")
    private val descendingBox = JBCheckBox("降序")
    private val trimItemsBox = JBCheckBox("逐行 trim")
    private val skipEmptyBox = JBCheckBox("跳过空行")

    private lateinit var shell: ToolWindowShell
    private lateinit var listSwitcher: ContentSwitcher
    private lateinit var mainSplit: JSplitPane

    private val properties = PropertiesComponent.getInstance()

    /** 恢复持久化选项时会触发一堆 document / selection 监听，这期间不要回写，否则会把默认值写回去 */
    private var restoring = false

    val component: JComponent = createComponent()

    override fun dispose() {
        saveState()
    }

    // ------------------------------------------------------------------ UI

    private fun createComponent(): JComponent {
        val font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        input.font = font
        result.font = font
        input.lineWrap = false
        result.lineWrap = false
        result.isEditable = false
        result.background = UiTokens.surfaceSunken
        input.border = UiTokens.emptyPx(Density.fieldPadV, Density.fieldPadH)
        result.border = UiTokens.emptyPx(Density.fieldPadV, Density.fieldPadH)

        input.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                updateResult()
            }
        })

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = renderer
        syncDensity()
        list.addListSelectionListener {
            if (!it.valueIsAdjusting && !restoring) {
                updateResult()
                saveState()
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) copyResult()
            }
        })

        val listScroll = ScrollPaneFactory.createScrollPane(
            list,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        )
        listSwitcher = ContentSwitcher(listScroll)
        val left = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(listSwitcher, BorderLayout.CENTER)
        }

        val ioSplit = JSplitPane(
            JSplitPane.VERTICAL_SPLIT,
            ScrollPaneFactory.createScrollPane(
                input,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            ),
            ScrollPaneFactory.createScrollPane(
                result,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            )
        )
        ioSplit.resizeWeight = 0.5
        ioSplit.dividerSize = Density.divider

        val right = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(ioSplit, BorderLayout.CENTER)
        }
        mainSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right)
        mainSplit.resizeWeight = 0.32
        mainSplit.dividerSize = Density.divider

        shell = ToolWindowShell("String Manipulation", mainSplit)
        shell.setActions(
            listOf(
                ToolWindowShell.Action("复制结果", "把右侧结果复制到剪贴板", ::copyResult),
                ToolWindowShell.Action("结果回填输入", "把结果放回输入框，接着串下一个操作") {
                    input.text = result.text
                },
                ToolWindowShell.Action("导入剪贴板", "把剪贴板文本作为输入") {
                    StringTransform.readClipboard()?.let { input.text = it }
                },
                ToolWindowShell.Action("导入编辑器选区", "把当前编辑器选中的文本作为输入") {
                    val selected = FileEditorManager.getInstance(project)
                        .selectedTextEditor?.selectionModel?.selectedText
                    if (!selected.isNullOrEmpty()) input.text = selected
                },
                ToolWindowShell.Action("清空", "清空输入框") { input.text = "" }
            )
        )
        shell.setSearchField(search)
        shell.setAdvanced("转换选项", advancedPanel(), ::enabledOptionCount)
        shell.onClearFilters { search.text = "" }
        // 窄宽度把左右分栏拉回均衡，避免输入框被挤成一条
        shell.onNarrowChanged { narrow ->
            mainSplit.resizeWeight = if (narrow) 0.5 else 0.32
            mainSplit.revalidate()
        }

        restoreState()
        updateResult()
        return shell.component
    }

    /** 4 个持久开关一行，两个分隔符按需展开，不再常驻占掉工具栏 */
    private fun advancedPanel(): JComponent {
        separator.text = StringOptions().separator
        splitSeparator.text = StringOptions().splitSeparator
        separator.preferredSize = Dimension(JBUI.scale(90), separator.preferredSize.height)
        splitSeparator.preferredSize = Dimension(JBUI.scale(90), splitSeparator.preferredSize.height)
        separator.toolTipText = "拼接分隔符；支持 \\n \\t \\r \\\\"
        splitSeparator.toolTipText = "拆分分隔符；支持 \\n \\t \\r \\\\"
        trimItemsBox.isSelected = true
        skipEmptyBox.isSelected = true

        val refresh = {
            if (!restoring) {
                updateResult()
                saveState()
            }
        }
        separator.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) { refresh() }
        })
        splitSeparator.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) { refresh() }
        })
        listOf(ignoreCaseBox, descendingBox, trimItemsBox, skipEmptyBox).forEach { box ->
            box.addActionListener { refresh() }
        }

        val panel = JPanel(BorderLayout(0, Density.gap)).apply { isOpaque = false }
        val separatorRow = JPanel(FlowLayout(FlowLayout.LEFT, UiTokens.scale(4), 0)).apply {
            isOpaque = false
            add(JBLabel("拼接符"))
            add(separator)
            add(JBLabel("拆分符"))
            add(splitSeparator)
            isVisible = false
        }
        val toggles = JPanel(FlowLayout(FlowLayout.LEFT, UiTokens.scale(4), 0)).apply {
            isOpaque = false
            add(ignoreCaseBox)
            add(descendingBox)
            add(trimItemsBox)
            add(skipEmptyBox)
            add(ActionLink("分隔符…") {
                separatorRow.isVisible = !separatorRow.isVisible
                panel.revalidate()
                panel.repaint()
            })
        }
        panel.add(toggles, BorderLayout.NORTH)
        panel.add(separatorRow, BorderLayout.SOUTH)
        return panel
    }

    private fun enabledOptionCount(): Int =
        listOf(ignoreCaseBox, descendingBox, trimItemsBox, skipEmptyBox).count { it.isSelected }

    // ------------------------------------------------------------------ 状态

    private fun options(): StringOptions = StringOptions(
        separator = separator.text,
        splitSeparator = splitSeparator.text,
        ignoreCase = ignoreCaseBox.isSelected,
        descending = descendingBox.isSelected,
        trimItems = trimItemsBox.isSelected,
        skipEmpty = skipEmptyBox.isSelected
    )

    private fun applyFilter() {
        val q = search.text.trim().lowercase()
        val previous = list.selectedValue
        val filtered = StringOps.ALL.filter {
            q.isEmpty() || it.title.lowercase().contains(q) || it.id.lowercase().contains(q) ||
                it.group.title.lowercase().contains(q) || it.description.lowercase().contains(q)
        }
        model.replaceAll(filtered)
        val index = if (previous != null) filtered.indexOf(previous) else -1
        list.selectedIndex = if (index >= 0) index else filtered.indices.firstOrNull() ?: -1
        if (list.selectedIndex >= 0) list.ensureIndexIsVisible(list.selectedIndex)
        if (filtered.isEmpty()) {
            listSwitcher.showEmpty("没有匹配的转换操作", "换个关键词，或清空搜索", "清除筛选") {
                search.text = ""
            }
        } else {
            listSwitcher.showContent()
        }
        updateResult()
    }

    /** 输入框为空时每项预览都是「（空结果）」，切成单行密度；有输入后恢复两行预览 */
    private fun syncDensity() {
        val compact = input.text.isEmpty()
        if (renderer.compact == compact && list.fixedCellHeight > 0) return
        renderer.compact = compact
        list.fixedCellHeight = if (compact) Density.listRowSingle else Density.listRowDouble
        list.revalidate()
        list.repaint()
    }

    private fun updateResult() {
        syncDensity()
        val op = list.selectedValue
        val source = input.text
        if (op == null) {
            result.text = ""
            setStatus("先在左侧选一个转换操作", error = false)
            return
        }
        val output = try {
            StringOps.apply(op, source, options())
        } catch (ex: StringOpException) {
            result.text = ""
            setStatus(ex.message ?: "转换失败", error = true)
            return
        }
        result.text = output
        val same = if (source == output) "（无变化）" else ""
        setStatus("输入 ${lineCount(source)} 行 / ${source.length} 字符  →  结果 ${lineCount(output)} 行 / ${output.length} 字符 $same", false)
        // 列表里逐条预览依赖输入框内容，输入变了要重画
        list.repaint()
    }

    private fun setStatus(text: String, error: Boolean) {
        shell.setStatus(
            if (error) StatusBarPanel.State.ERROR else StatusBarPanel.State.IDLE,
            text,
            "输入 ${lineCount(input.text)} 行  →  结果 ${lineCount(result.text)} 行"
        )
    }

    /** 列表里的预览取输入的前 500 字符，避免大文本下每敲一个字都跑全量转换 */
    private fun listPreview(op: StringOp): String {
        val flat = StringOps.preview(op, input.text.take(500), options()).replace(Regex("\\s+"), " ").trim()
        return when {
            flat.isEmpty() -> "（空结果）"
            flat.length > 80 -> flat.take(80) + "…"
            else -> flat
        }
    }

    private fun copyResult() {
        val text = result.text
        if (text.isEmpty()) return
        CopyPasteManager.copyTextToClipboard(text)
        setStatus("已复制结果到剪贴板（${text.length} 字符）", error = false)
    }

    private fun lineCount(text: String): Int = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

    // ------------------------------------------------------------------ 记住上次的选择

    private fun restoreState() {
        restoring = true
        separator.text = properties.getValue(KEY_SEPARATOR) ?: StringOptions().separator
        splitSeparator.text = properties.getValue(KEY_SPLIT) ?: StringOptions().splitSeparator
        ignoreCaseBox.isSelected = properties.getValue(KEY_IGNORE_CASE) == "true"
        descendingBox.isSelected = properties.getValue(KEY_DESCENDING) == "true"
        trimItemsBox.isSelected = (properties.getValue(KEY_TRIM) ?: "true") == "true"
        skipEmptyBox.isSelected = (properties.getValue(KEY_SKIP_EMPTY) ?: "true") == "true"
        applyFilter()
        val saved = properties.getValue(KEY_OP)?.let { StringOps.byId(it) } ?: StringOps.ALL.first()
        val index = StringOps.ALL.indexOf(saved)
        // applyFilter 已经按搜索词（此时为空）填好了全量列表
        list.selectedIndex = if (index in model.items.indices) index else 0
        restoring = false
    }

    private fun saveState() {
        if (restoring) return
        list.selectedValue?.let { properties.setValue(KEY_OP, it.id) }
        properties.setValue(KEY_SEPARATOR, separator.text)
        properties.setValue(KEY_SPLIT, splitSeparator.text)
        properties.setValue(KEY_IGNORE_CASE, ignoreCaseBox.isSelected.toString())
        properties.setValue(KEY_DESCENDING, descendingBox.isSelected.toString())
        properties.setValue(KEY_TRIM, trimItemsBox.isSelected.toString())
        properties.setValue(KEY_SKIP_EMPTY, skipEmptyBox.isSelected.toString())
    }

    private companion object {
        const val KEY_OP = "batiskit.string.op"
        const val KEY_SEPARATOR = "batiskit.string.separator"
        const val KEY_SPLIT = "batiskit.string.splitSeparator"
        const val KEY_IGNORE_CASE = "batiskit.string.ignoreCase"
        const val KEY_DESCENDING = "batiskit.string.descending"
        const val KEY_TRIM = "batiskit.string.trimItems"
        const val KEY_SKIP_EMPTY = "batiskit.string.skipEmpty"
    }
}
