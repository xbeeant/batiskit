package io.github.xbeeant.batiskit.action

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.xbeeant.batiskit.template.TemplateSpec
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane

/**
 * 漂移报告 + 选择性同步。
 *
 * 差异列表只是「看」，真正要改什么由右侧模板清单决定：勾选的产物会按当前表结构重新渲染，
 * 然后照样弹出生成预览，确认后才写入。不提供「按字段偷偷补代码」的魔法——
 * 生成器只有模板这一条链路，同步也必须走它，结果才和首次生成一致。
 */
class TableDriftDialog(
    project: Project,
    private val report: DriftReport,
    private val specs: List<TemplateSpec>
) : DialogWrapper(project, true) {

    private val checks: Map<String, JBCheckBox> = linkedMapOf<String, JBCheckBox>().apply {
        specs.forEach { spec ->
            val key = "${spec.set}:${spec.id}"
            this[key] = JBCheckBox("${spec.name}（${spec.id}）", true).apply {
                toolTipText = "勾选后按当前表结构重新渲染 ${spec.output}"
            }
        }
    }

    private val text = JBTextArea().apply {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        caretPosition = 0
    }

    init {
        title = "表结构漂移 · Batis Kit"
        setOKButtonText("重新生成选中产物")
        setCancelButtonText("关闭")
        text.text = buildString {
            if (report.isClean) {
                append("✓ 未发现漂移\n")
            } else {
                report.items.groupBy { it.table }.forEach { (table, items) ->
                    append("【$table】${items.size} 项\n")
                    items.forEach { append("  · [${it.kind.title}] ").append(it.detail).append('\n') }
                    append('\n')
                }
            }
            if (report.notes.isNotEmpty()) {
                append("说明：\n")
                report.notes.forEach { append("  · ").append(it).append('\n') }
            }
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(900, 520)
            minimumSize = Dimension(660, 380)
        }
        root.add(
            JBLabel("检测结果：${report.summary()}　·　共 ${report.items.size} 项差异（产物是否存在以磁盘文件为准，不看索引）；勾选右侧模板后可重新生成"),
            BorderLayout.NORTH
        )

        val left = JPanel(BorderLayout()).apply {
            add(JBLabel("差异明细").apply { border = JBUI.Borders.emptyBottom(4) }, BorderLayout.NORTH)
            add(JBScrollPane(text), BorderLayout.CENTER)
        }
        val right = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            add(JBLabel("要同步的产物（已排除的模板不列出）").apply {
                border = JBUI.Borders.emptyBottom(8)
            })
            checks.values.forEach { add(it) }
        }
        root.add(
            JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, JBScrollPane(right)).apply {
                dividerLocation = 610
                resizeWeight = 0.7
            },
            BorderLayout.CENTER
        )
        root.add(
            JBLabel("提示：同步会重新渲染模板并走「生成预览」，Java / XML 仍按补缺策略合并，整批可 Ctrl+Z 撤销。'实体多出字段' 只作提醒，不会被自动删除。"),
            BorderLayout.SOUTH
        )
        return root
    }

    /** 用户勾选的模板 key；未勾选的会在本次同步里被排除 */
    fun selectedTemplates(): Set<String> =
        checks.filterValues { it.isSelected }.keys.toSet()

    /** 一个都没勾时不必弹预览，直接当取消处理 */
    override fun doOKAction() {
        if (selectedTemplates().isEmpty()) {
            setErrorText("请至少勾选一个要同步的产物")
            return
        }
        super.doOKAction()
    }
}
