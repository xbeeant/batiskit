package io.github.xbeeant.batiskit.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.TitledSeparator
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import io.github.xbeeant.batiskit.config.MapperAssistSettings
import io.github.xbeeant.batiskit.ui.UiTokens
import io.github.xbeeant.batiskit.ui.components.BannerPanel
import io.github.xbeeant.batiskit.ui.components.FixedHeightRow
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings → Tools → Batis Kit → 模块开关。
 *
 * 沿用设计系统的「分组标题 + 后果 Banner + 44px 整行可点开关 + 组尾批量操作」结构；
 * 关掉的模块不再出现在右键菜单 / 工具窗口，对应的启动活动与控制台高亮也一并停用。
 */
class ModuleTogglesConfigurable : SearchableConfigurable {

    override fun getId(): String = ID

    private val boxes = LinkedHashMap<String, JBCheckBox>()

    private var panel: JPanel? = null

    private val enabledCount = JBLabel()

    override fun getDisplayName(): String = "模块开关"

    override fun createComponent(): JComponent = JPanel(BorderLayout()).apply {
        border = UiTokens.empty(16, 16, 16, 16)
        val body = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // 后果说明前置成 Banner，不再用 <html><h2><p> 塞一大段说明
        body.add(FixedHeightRow(BannerPanel().apply {
            display(
                BannerPanel.Type.INFO,
                "改动对所有工程生效",
                "关闭会同时隐藏右键入口与工具窗口、停用启动活动和后台高亮；默认全部开启"
            )
        }))
        body.add(JPanel().apply {
            isOpaque = false
            preferredSize = Dimension(1, UiTokens.scale(12))
            maximumSize = Dimension(Int.MAX_VALUE, UiTokens.scale(12))
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        body.add(FixedHeightRow(TitledSeparator("内置模块")))

        ModuleToggles.MODULES.forEach { module ->
            val row = toggleRow(module)
            row.alignmentX = JComponent.LEFT_ALIGNMENT
            body.add(row)
        }

        // 组尾：批量操作 + 当前启用数量，操作不再和开关混在一起
        val footer = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, UiTokens.scale(44))
            border = UiTokens.line(UiTokens.borderSubtle, top = 1)
            enabledCount.font = UiTokens.fontSmall()
            enabledCount.foreground = UiTokens.textSecondary
            val buttons = JPanel(FlowLayout(FlowLayout.LEFT, UiTokens.scale(8), 0)).apply {
                isOpaque = false
                add(JButton("全部启用").apply {
                    addActionListener { setAll(true) }
                })
                add(JButton("全部停用").apply {
                    addActionListener { setAll(false) }
                })
                add(enabledCount)
            }
            add(buttons, BorderLayout.WEST)
        }
        body.add(footer)
        // 所有行高度固定后，视口里多出来的空间全部沉到末尾，不再撑大分组间距
        body.add(Box.createVerticalGlue())

        add(JBScrollPane(body).apply {
            border = null
            viewport.isOpaque = false
            isOpaque = false
        }, BorderLayout.CENTER)
        panel = this
        reset()
    }

    /** 整行 44px、整行可点，分隔线用 subtle 而不是每格加边框 */
    private fun toggleRow(module: String): JPanel {
        val box = JBCheckBox(ModuleToggles.LABELS[module].orEmpty()).apply {
            font = UiTokens.fontBase()
            foreground = UiTokens.textPrimary
            isOpaque = false
            addActionListener { updateCount() }
        }
        boxes[module] = box
        val row = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = UiTokens.listBackground()
            border = UiTokens.compound(
                UiTokens.line(UiTokens.borderSubtle, bottom = 1),
                UiTokens.empty(0, 8, 0, 8)
            )
            preferredSize = Dimension(1, UiTokens.scale(44))
            maximumSize = Dimension(Int.MAX_VALUE, UiTokens.scale(44))
            add(box, BorderLayout.WEST)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent?) {
                    // 点在行内空白处也切换；点在复选框上由复选框自己处理，不会重复触发
                    if (event?.component === this@apply) box.doClick()
                }

                override fun mouseEntered(event: MouseEvent?) {
                    background = UiTokens.surfaceHover
                    repaint()
                }

                override fun mouseExited(event: MouseEvent?) {
                    background = UiTokens.listBackground()
                    repaint()
                }
            })
        }
        return row
    }

    private fun setAll(value: Boolean) {
        boxes.values.forEach { it.isSelected = value }
        updateCount()
    }

    private fun updateCount() {
        enabledCount.text = "  ${boxes.values.count { it.isSelected }} / ${ModuleToggles.MODULES.size} 已启用"
    }

    override fun isModified(): Boolean {
        if (panel == null) return false
        val service = ModuleToggles.getInstance()
        return ModuleToggles.MODULES.any { boxes[it]?.isSelected != service.isEnabled(it) }
    }

    override fun apply() {
        val service = ModuleToggles.getInstance()
        val rainbowChanged = boxes[ModuleToggles.RAINBOW]?.isSelected != service.isEnabled(ModuleToggles.RAINBOW)
        val mapperChanged = boxes[ModuleToggles.MAPPER]?.isSelected != service.isEnabled(ModuleToggles.MAPPER)
        ModuleToggles.MODULES.forEach { module ->
            service.setEnabled(module, boxes[module]?.isSelected ?: true)
        }
        if (rainbowChanged || mapperChanged) {
            // 高亮 / 侧栏图标由 daemon 缓存，改完立刻重跑所有已打开工程
            MapperAssistSettings.restartHighlighting()
        }
        reset()
    }

    override fun reset() {
        val service = ModuleToggles.getInstance()
        ModuleToggles.MODULES.forEach { module ->
            boxes[module]?.isSelected = service.isEnabled(module)
        }
        updateCount()
    }

    override fun disposeUIResources() {
        panel = null
        boxes.clear()
    }

    companion object {
        const val ID = "io.github.xbeeant.batiskit.modules.settings"
    }
}
