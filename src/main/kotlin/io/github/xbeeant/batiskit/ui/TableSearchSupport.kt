package io.github.xbeeant.batiskit.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import io.github.xbeeant.batiskit.util.SearchQuery
import javax.swing.JTable
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.TableModel
import javax.swing.table.TableRowSorter

/**
 * 表格的「搜索 + 列头排序 + 结果计数」通用接线。
 *
 * 搜索走 [SearchQuery]：空格分隔 AND、`-foo` 排除、`/正则/` 匹配，匹配范围是所有列。
 * 过滤只影响显示，不改模型数据，也不影响预览里的勾选 / 最终写入结果。
 * 搜索与排序只影响显示，不改变规则声明顺序 / 勾选结果。
 * [countLabel] 显示「可见 / 共 N 项」，搜索或排序变化、模型刷新后都会更新。
 * 需要一并展示上面这行说明时打开 [showDisplayHint]，把 [hintLabel] 加进搜索行下方；
 * 默认关闭，既有调用方（各自已有说明文字）外观不变。
 */
class TableSearchSupport(
    table: JTable,
    private val model: TableModel,
    /** 是否附带一行「只影响显示」的说明；默认 false，保持既有调用方外观不变 */
    showDisplayHint: Boolean = false,
) {

    val field = JBTextField()

    val countLabel = JBLabel()

    /** 可选说明：只有 [showDisplayHint] 打开时才有文本，调用方按需加进自己的面板 */
    val hintLabel: JBLabel = JBLabel().apply {
        if (showDisplayHint) text = HINT_TEXT
    }

    private val sorter = TableRowSorter(model)

    init {
        table.rowSorter = sorter
        field.emptyText.text = "搜索：空格分隔 AND，-foo 排除，/正则/ 匹配"
        field.toolTipText = "搜索范围是所有列，只过滤显示、不改数据；清空显示全部，点列头可排序"
        field.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = applyFilter()
            override fun removeUpdate(e: DocumentEvent) = applyFilter()
            override fun changedUpdate(e: DocumentEvent) = applyFilter()

            private fun applyFilter() {
                sorter.rowFilter = SearchQuery.rowFilter(SearchQuery.parse(field.text))
                updateCount()
            }
        })
        sorter.addRowSorterListener { updateCount() }
        updateCount()
    }

    /** 模型数据重建 / 刷新后手动同步一次计数（sorter 事件不一定每次都发） */
    fun refreshCount() = updateCount()

    private fun updateCount() {
        val total = model.rowCount
        val visible = sorter.viewRowCount
        countLabel.text = formatCount(visible, total)
        countLabel.toolTipText = if (visible == total) {
            "共 $total 项；搜索只过滤显示，不改数据"
        } else {
            "当前显示 $visible 项，共 $total 项；搜索只过滤显示，不改数据"
        }
    }

    companion object {
        /** 搜索与排序只影响显示，不改变规则声明顺序 / 勾选结果 */
        const val HINT_TEXT = "搜索与排序只影响显示，不改变规则声明顺序 / 勾选结果"

        /** 纯逻辑，方便单测；visible 异常大于 total 时按全量处理 */
        fun formatCount(visible: Int, total: Int): String =
            if (visible >= total) "共 $total 项" else "$visible / $total 项"
    }
}
