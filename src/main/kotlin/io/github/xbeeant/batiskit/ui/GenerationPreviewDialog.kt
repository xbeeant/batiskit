package io.github.xbeeant.batiskit.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import io.github.xbeeant.batiskit.generate.ChangeKind
import io.github.xbeeant.batiskit.generate.GenerationPlan
import io.github.xbeeant.batiskit.generate.TextDiff
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * 写盘前的变更预览。
 *
 * 三块信息：上面是汇总与模板问题，左边按模板整组开关 + 逐文件勾选，右边是选中项的行级 diff。
 * 「合并」展示的是**本次要生成的内容**与**当前文件内容**的差异；真正落盘时 JavaMerger / XmlMerger
 * 仍按「只补缺」或「同签名覆盖」处理，所以合并项的 diff 不等于最终文件全文——底部有说明。
 */
class GenerationPreviewDialog(
    project: Project,
    private val plan: GenerationPlan
) : DialogWrapper(project, true) {

    private val planModel = PlanTableModel(plan)
    private val summary = JBLabel()
    private val problems = JBLabel()
    private val diffArea = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        text = "选中左侧任意一行查看变更内容"
    }

    private val table = JBTable(planModel).apply {
        selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
        columnModel.getColumn(0).preferredWidth = 52
        columnModel.getColumn(1).preferredWidth = 72
        columnModel.getColumn(2).preferredWidth = 110
        columnModel.getColumn(3).preferredWidth = 320
    }

    private val tableSearch = TableSearchSupport(table, planModel)

    /** 模板 key → 勾选框；取消勾选即把整组产物排除，并持久化到配置里 */
    private val templateBoxes: Map<String, JBCheckBox> = linkedMapOf<String, JBCheckBox>().apply {
        plan.templates.forEach { spec ->
            val key = "${spec.set}:${spec.id}"
            this[key] = JBCheckBox("${spec.name}（${spec.id}）", key !in plan.disabledTemplates).apply {
                toolTipText = "取消勾选后，这套方案里该模板产出的所有文件都不写入"
                addActionListener {
                    if (isSelected) plan.disabledTemplates.remove(key) else plan.disabledTemplates.add(key)
                    planModel.refreshAll()
                    updateSummary()
                    showSelectedDiff()
                }
            }
        }
    }

    init {
        title = "生成预览 · ${plan.setId}"
        table.selectionModel.addListSelectionListener { showSelectedDiff() }
        updateSummary()
        updateProblems()
        init()
        if (plan.files.isNotEmpty()) {
            table.setRowSelectionInterval(0, 0)
            showSelectedDiff()
        }
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(980, 580)
            minimumSize = Dimension(720, 400)
        }

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(summary)
            add(problems.apply { border = JBUI.Borders.emptyTop(4) })
        }
        root.add(header, BorderLayout.NORTH)

        val templatePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6)
            add(JBLabel("模板清单（取消勾选＝整组不生成）").apply {
                border = JBUI.Borders.emptyBottom(6)
            })
            templateBoxes.values.forEach { add(it) }
        }
        val left = JPanel(BorderLayout()).apply {
            val templates = JBScrollPane(templatePanel).apply { preferredSize = Dimension(220, 200) }
            add(templates, BorderLayout.WEST)
            val tablePane = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                val header = JPanel(BorderLayout(JBUI.scale(8), 0))
                header.add(JBLabel("产物清单（搜索只过滤显示，不影响写入）"), BorderLayout.WEST)
                header.add(tableSearch.field, BorderLayout.CENTER)
                header.add(tableSearch.countLabel, BorderLayout.EAST)
                add(header, BorderLayout.NORTH)
                add(JBScrollPane(table), BorderLayout.CENTER)
            }
            add(tablePane, BorderLayout.CENTER)
        }
        val right = JPanel(BorderLayout()).apply {
            add(JBLabel("变更内容（点左侧行切换）").apply {
                border = JBUI.Borders.emptyBottom(4)
            }, BorderLayout.NORTH)
            add(JBScrollPane(diffArea), BorderLayout.CENTER)
        }
        root.add(
            JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right).apply {
                dividerLocation = 570
                resizeWeight = 0.6
            },
            BorderLayout.CENTER
        )
        root.add(
            JBLabel("提示：「合并」按 java / xml 的补缺策略写入，上方 diff 展示的是本次生成内容与现有内容的差异，不是合并后的全文；「覆盖」会整份替换文件。确定后整批可用 Ctrl+Z 撤销。"),
            BorderLayout.SOUTH
        )
        return root
    }

    override fun getPreferredFocusedComponent(): JComponent = table

    private fun updateSummary() {
        val selected = plan.selected().size
        summary.text = "方案 ${plan.setId} · 共 ${plan.files.size} 项：" +
            "新建 ${plan.count(ChangeKind.CREATE)} / 合并 ${plan.count(ChangeKind.MERGE)} / " +
            "覆盖 ${plan.count(ChangeKind.OVERWRITE)} / 无变化 ${plan.count(ChangeKind.UNCHANGED)} · " +
            "将写入 $selected 项"
    }

    private fun updateProblems() {
        // HTML 里的颜色不能写死：深色主题下深红 / 深棕几乎看不清
        val errorColor = htmlColor(UiTokens.error)
        val warnColor = htmlColor(UiTokens.warning)
        val lines = mutableListOf<String>()
        if (plan.errors.isNotEmpty()) {
            lines += "<font color='$errorColor'>渲染错误 ${plan.errors.size} 项（这些产物不会生成）：" +
                plan.errors.take(3).joinToString("；") { escape(it) } + "</font>"
        }
        if (plan.warnings.isNotEmpty()) {
            lines += "<font color='$warnColor'>提示 ${plan.warnings.size} 项：" +
                plan.warnings.take(3).joinToString("；") { escape(it) } + "</font>"
        }
        if (plan.count(ChangeKind.OVERWRITE) > 0) {
            lines += "<font color='$errorColor'>注意：有 ${plan.count(ChangeKind.OVERWRITE)} 个文件会被整份覆盖，" +
                "请确认里面没有手工修改。</font>"
        }
        problems.text = if (lines.isEmpty()) "" else "<html>${lines.joinToString("<br/>")}</html>"
    }

    private fun htmlColor(color: java.awt.Color): String =
        String.format("#%02X%02X%02X", color.red, color.green, color.blue)

    private fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** 装了 RowSorter 之后 selectedRow 是视图索引，取 plan 数据前必须转回模型索引 */
    private fun showSelectedDiff() {
        val row = table.selectedRow
        if (row < 0) {
            diffArea.text = "没有可显示的产物（可能被搜索条件过滤了）"
            diffArea.caretPosition = 0
            return
        }
        showDiff(table.convertRowIndexToModel(row))
    }

    private fun showDiff(row: Int) {
        val file = plan.files.getOrNull(row) ?: return
        diffArea.text = buildString {
            append("模板：").append(file.spec.fileName).append("  ·  合并策略：").append(file.merge).append('\n')
            append("状态：").append(file.status(plan.disabledTemplates)).append("\n\n")
            append(TextDiff.unified(file.existingText, file.proposedText))
        }
        diffArea.caretPosition = 0
    }

    private class PlanTableModel(private val plan: GenerationPlan) : AbstractTableModel() {

        private val headers = arrayOf("写入", "状态", "模板", "产物路径")

        override fun getRowCount(): Int = plan.files.size
        override fun getColumnCount(): Int = headers.size
        override fun getColumnName(column: Int): String = headers[column]
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) Boolean::class.javaObjectType else String::class.java

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val file = plan.files[rowIndex]
            return when (columnIndex) {
                0 -> file.selected && file.kind.writable && file.templateKey !in plan.disabledTemplates
                1 -> file.status(plan.disabledTemplates)
                2 -> file.templateLabel
                else -> file.relativePath
            }
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex == 0) {
                plan.files[rowIndex].selected = value as? Boolean ?: false
                fireTableRowsUpdated(rowIndex, rowIndex)
            }
        }

        fun refreshAll() = fireTableDataChanged()
    }
}
