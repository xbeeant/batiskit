package io.github.xbeeant.batiskit.string.ui

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.xbeeant.batiskit.string.StringOp
import io.github.xbeeant.batiskit.string.StringOpGroup
import io.github.xbeeant.batiskit.string.StringOps
import io.github.xbeeant.batiskit.string.StringOptions
import io.github.xbeeant.batiskit.string.StringRangeOption
import io.github.xbeeant.batiskit.string.StringTarget
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.KeyEvent.VK_DOWN
import java.awt.event.KeyEvent.VK_ENTER
import java.awt.event.KeyEvent.VK_ESCAPE
import java.awt.event.KeyEvent.VK_UP
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent

/**
 * 「搜索全部转换」弹窗：列出所有操作并**逐条显示对当前文本的结果预览**，输入关键字过滤。
 *
 * 全部操作都在一个可搜索列表里，比「五层子菜单里找一项」快；右键菜单里的分组子菜单
 * 仍保留一键直达常用操作的路径（见 `StringActions.kt`）。
 *
 * 顶部还会列出 [ranges] 里可选的**作用范围**（选区 / 字符串内容 / 当前单词 / 当前行），
 * 切换后所有预览按新范围重算——Alt+Enter 时不必为了转一个词先手动选中它。
 */
class StringOpChooser private constructor(
    private val ranges: List<StringRangeOption>,
    private val options: StringOptions,
    private val onChosen: (StringOp, StringTarget) -> Unit
) {

    private val search = JBTextField()
    private val model = CollectionListModel<StringOp>()
    private val list = JBList(model)
    private val rangeBox = ComboBox<StringRangeOption>()
    private var popup: JBPopup? = null

    val component: JComponent = createComponent()

    /** 当前下拉选中的范围；只有一个范围时下拉不显示，直接取那一项。 */
    private val currentRange: StringRangeOption
        get() = (rangeBox.selectedItem as? StringRangeOption)
            ?: ranges.firstOrNull()
            ?: StringRangeOption("文本", StringTarget(""))

    private fun createComponent(): JComponent {
        val root = JPanel(BorderLayout(0, JBUI.scale(4)))
        root.border = JBUI.Borders.empty(6)
        root.preferredSize = Dimension(JBUI.scale(680), JBUI.scale(420))

        search.emptyText.text = "搜索转换（驼峰 / base64 / 去重 / URL …）"
        search.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                applyFilter(search.text)
            }
        })
        search.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) {
                when (e.keyCode) {
                    VK_DOWN -> move(1)
                    VK_UP -> move(-1)
                    VK_ENTER -> choose(list.selectedValue)
                    VK_ESCAPE -> popup?.cancel()
                }
            }
        })

        val top = JPanel(BorderLayout(0, JBUI.scale(4)))
        top.add(search, BorderLayout.CENTER)
        if (ranges.size > 1) {
            rangeBox.renderer = SimpleListCellRenderer.create("") { option: StringRangeOption? -> option?.label ?: "" }
            ranges.forEach(rangeBox::addItem)
            rangeBox.selectedIndex = 0
            // 换范围后重刷列表，让每一条预览都按新文本算
            rangeBox.addActionListener { applyFilter(search.text) }
            val rangeRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0))
            rangeRow.add(JBLabel("作用范围："))
            rangeRow.add(rangeBox)
            top.add(rangeRow, BorderLayout.SOUTH)
        }
        root.add(top, BorderLayout.NORTH)

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = StringOpRenderer(::preview)
        list.emptyText.text = "没有匹配的转换操作"
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) choose(list.selectedValue)
            }
        })
        list.registerKeyboardAction(
            { choose(list.selectedValue) },
            KeyStroke.getKeyStroke(VK_ENTER, 0),
            JComponent.WHEN_FOCUSED
        )

        root.add(
            ScrollPaneFactory.createScrollPane(
                list,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            ),
            BorderLayout.CENTER
        )
        applyFilter("")
        return root
    }

    fun attach(popup: JBPopup) {
        this.popup = popup
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        // 多行文本多半是想排序 / 去重 / 拼接，把这些提到列表最前；单行仍按分组顺序
        val ordered = if (currentRange.target.text.contains('\n')) {
            StringOps.ALL.sortedBy { if (it.group == StringOpGroup.LINES) 0 else 1 }
        } else {
            StringOps.ALL
        }
        val filtered = ordered.filter {
            q.isEmpty() || it.title.lowercase().contains(q) || it.id.lowercase().contains(q) ||
                it.group.title.lowercase().contains(q) || it.description.lowercase().contains(q)
        }
        model.replaceAll(filtered)
        if (filtered.isNotEmpty()) list.selectedIndex = 0
    }

    private fun move(delta: Int) {
        val size = list.model.size
        if (size == 0) return
        list.selectedIndex = (list.selectedIndex + delta).coerceIn(0, size - 1)
        list.ensureIndexIsVisible(list.selectedIndex)
    }

    private fun choose(value: StringOp?) {
        val op = value ?: return
        val target = currentRange.target
        popup?.cancel()
        onChosen(op, target)
    }

    /** 预览压成一行并截断；解码失败时 [StringOps.preview] 返回的是带括号的提示，不至于让弹窗崩掉 */
    private fun preview(op: StringOp): String {
        val flat = StringOps.preview(op, currentRange.target.text.take(4000), options)
            .replace(Regex("\\s+"), " ").trim()
        return when {
            flat.isEmpty() -> "（空结果）"
            flat.length > 100 -> flat.take(100) + "…"
            else -> flat
        }
    }

    companion object {
        fun show(
            context: DataContext,
            ranges: List<StringRangeOption>,
            options: StringOptions = StringOptions(),
            onChosen: (StringOp, StringTarget) -> Unit
        ) {
            if (ranges.isEmpty()) return
            val chooser = StringOpChooser(ranges, options, onChosen)
            val popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(chooser.component, chooser.search)
                .setTitle("String Manipulation · 输入即过滤，回车应用")
                .setResizable(true)
                .setMovable(true)
                .setRequestFocus(true)
                .setMinSize(Dimension(JBUI.scale(480), JBUI.scale(280)))
                .createPopup()
            chooser.attach(popup)
            popup.showInBestPositionFor(context)
        }
    }
}
