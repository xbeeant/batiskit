package io.github.xbeeant.batiskit.git.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import io.github.xbeeant.batiskit.ui.UiTokens
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultListCellRenderer
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * 多选列表弹窗（推送 tag / 清理分支共用）：
 * 条目支持灰色副标题，可选一个附加复选框（如「强制推送」）。
 */
class SelectListDialog(
    project: Project?,
    title: String,
    private val items: List<Item>,
    private val extraOption: String? = null,
    private val okText: String = "确定",
    private val warning: String? = null,
) : DialogWrapper(project) {

    data class Item(val label: String, val detail: String = "") {
        override fun toString(): String = if (detail.isEmpty()) label else "$label   $detail"
    }

    private val list = JBList(items)
    private var extraBox: JBCheckBox? = null

    init {
        super.setTitle(title)
        setOKButtonText(okText)
        list.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        init()
    }

    override fun createCenterPanel(): JComponent {
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): JComponent {
                val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                component.border = JBUI.Borders.empty(2, 4)
                if (!isSelected && value is Item && value.detail.isNotEmpty()) {
                    component.foreground = JBColor.GRAY
                }
                return component
            }
        }
        val panel = JPanel(BorderLayout(0, JBUI.scale(8)))
        panel.add(JBScrollPane(list), BorderLayout.CENTER)
        if (warning != null) {
            val label = JBLabel("<html><body style='width:380px'>$warning</body></html>")
            label.foreground = UiTokens.error
            panel.add(label, BorderLayout.NORTH)
        }
        if (extraOption != null) {
            val box = JBCheckBox(extraOption, false)
            extraBox = box
            panel.add(box, BorderLayout.SOUTH)
        }
        panel.preferredSize = Dimension(JBUI.scale(460), JBUI.scale(320))
        return panel
    }

    fun chosen(): List<Item> = list.selectedValuesList

    fun extraSelected(): Boolean = extraBox?.isSelected == true
}
