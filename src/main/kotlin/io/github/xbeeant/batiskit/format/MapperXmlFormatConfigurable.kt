package io.github.xbeeant.batiskit.format

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.xbeeant.batiskit.ui.components.FixedHeightRow
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Settings → Tools → Batis Kit → Mapper XML 格式化 */
class MapperXmlFormatConfigurable : SearchableConfigurable {

    override fun getId(): String = "io.github.xbeeant.batiskit.format.settings"

    private val settings = MapperXmlFormatSettings.getInstance()

    private val enabledBox = JBCheckBox("启用 Mapper XML 格式化（接管 Ctrl+Alt+L 与右键「格式化 Mapper XML」）")
    private val saveBox = JBCheckBox("保存文件时自动格式化 Mapper XML")
    private val indentField = JBTextField(4)

    private var panel: JComponent? = null

    override fun getDisplayName(): String = "Mapper XML 格式化"

    override fun createComponent(): JComponent {
        val root = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }
        root.add(fullRow(enabledBox))
        root.add(fullRow(saveBox))

        val indentRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JBLabel("每层缩进空格数（1–8，默认 4）："), BorderLayout.WEST)
            add(indentField, BorderLayout.CENTER)
        }
        root.add(fullRow(indentRow))
        root.add(
            fullRow(
                JBLabel("SQL / XML 联合缩进：XML 层级打底、SQL 内部相对层次保留；只调整空白，SQL 与标签混排的行不拆开。")
            )
        )
        root.add(
            fullRow(
                JBLabel("关闭总开关后由 IDE 自带的 XML 格式化器接管；设置对所有工程生效。")
            )
        )
        root.add(Box.createVerticalGlue())
        reset()
        return root.also { panel = it }
    }

    override fun isModified(): Boolean =
        enabledBox.isSelected != settings.state.enabled ||
            saveBox.isSelected != settings.state.formatOnSave ||
            indentField.text.trim().toIntOrNull() != settings.state.indentWidth

    override fun apply() {
        val width = indentField.text.trim().toIntOrNull()
        if (width == null || width !in 1..8) {
            throw ConfigurationException("缩进宽度必须是 1–8 之间的整数")
        }
        settings.state.enabled = enabledBox.isSelected
        settings.state.formatOnSave = saveBox.isSelected
        settings.state.indentWidth = width
    }

    override fun reset() {
        enabledBox.isSelected = settings.state.enabled
        saveBox.isSelected = settings.state.formatOnSave
        indentField.text = settings.state.indentWidth.toString()
    }

    override fun disposeUIResources() {
        panel = null
    }

    /** 固定高度行：多余空间交给末尾的 glue，而不是摊到每一行 */
    private fun fullRow(component: JComponent): JComponent = FixedHeightRow(component)
}
