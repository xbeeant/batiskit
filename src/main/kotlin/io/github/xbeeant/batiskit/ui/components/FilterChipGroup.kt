package io.github.xbeeant.batiskit.ui.components

import io.github.xbeeant.batiskit.api.ui.WrapLayout
import io.github.xbeeant.batiskit.ui.UiText
import io.github.xbeeant.batiskit.ui.UiTokens
import java.awt.BasicStroke
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.BorderFactory
import javax.swing.JPanel
import javax.swing.JToggleButton

/**
 * 可按压筛选 chip 组：替代原来一行行 `JBCheckBox`。
 *
 * 未选中态使用完整的低强调文字色，而不是 opacity（透明度会把对比度压到 AA 以下）。
 * 背景、边框与文字统一在 [ChipButton.paintComponent] 里自绘：
 * Swing 的绘制顺序是 paintComponent → paintBorder，若把填充放进 Border 会盖住已绘制的文字。
 */
class FilterChipGroup(
    labels: List<String>,
    initiallySelected: Collection<String> = labels,
    private val onChanged: (Set<String>) -> Unit,
) : JPanel(WrapLayout(FlowLayout.LEFT, UiTokens.scale(4), UiTokens.scale(2))) {

    private val chips = linkedMapOf<String, ChipButton>()

    /** 批量改状态时抑制回调，避免「全选 / 清空」触发 N 次重排 */
    private var notifying = true

    init {
        isOpaque = false
        labels.forEach { label ->
            val chip = ChipButton(label, label in initiallySelected)
            chip.addItemListener {
                paintState(chip)
                if (notifying) onChanged(selected())
            }
            chips[label] = chip
            add(chip)
        }
    }

    fun selected(): Set<String> = chips.filterValues { it.isSelected }.keys.toSet()

    fun activeCount(): Int = chips.values.count { it.isSelected }

    fun setSelected(label: String, value: Boolean) {
        chips[label]?.isSelected = value
    }

    fun isSelected(label: String): Boolean = chips[label]?.isSelected == true

    /** 全选 / 清空：只触发一次变更回调 */
    fun setAll(value: Boolean) {
        notifying = false
        chips.values.forEach { it.isSelected = value }
        chips.values.forEach(::paintState)
        notifying = true
        onChanged(selected())
    }

    private fun paintState(chip: ChipButton) {
        chip.foreground = if (chip.isSelected) UiTokens.accent else UiTokens.textMuted
        chip.repaint()
    }

    /** 自绘 pill：底色 → 描边 → 焦点环 → 文字，顺序不可颠倒 */
    private inner class ChipButton(label: String, selected: Boolean) : JToggleButton(label, selected) {

        init {
            font = UiTokens.fontSmall()
            isOpaque = false
            isContentAreaFilled = false
            isFocusPainted = false
            isRolloverEnabled = true
            // Border 只负责留白，绝不参与绘制
            border = BorderFactory.createEmptyBorder(
                UiTokens.scale(2), UiTokens.scale(10), UiTokens.scale(2), UiTokens.scale(10)
            )
            foreground = if (selected) UiTokens.accent else UiTokens.textMuted
            toolTipText = "$label — 按此筛选"
            getAccessibleContext().accessibleName = label
            alignmentY = CENTER_ALIGNMENT
        }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                // 圆角外的四个角保持与父容器一致，避免出现色块
                g.color = UiTokens.listBackground()
                g.fillRect(0, 0, width, height)
                val arc = UiTokens.scale(12)
                g.color = if (isSelected) UiTokens.accentSubtle else UiTokens.surfaceRaised
                g.fillRoundRect(0, 0, width, height, arc, arc)
                g.color = if (isSelected) UiTokens.accent else UiTokens.borderSubtle
                g.stroke = BasicStroke(1f)
                g.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
                if (hasFocus()) {
                    g.color = UiTokens.accent
                    g.stroke = BasicStroke(UiTokens.scale(2).toFloat())
                    g.drawRoundRect(1, 1, width - 3, height - 3, arc, arc)
                }
                // 文字必须最后画
                g.font = font
                g.color = foreground
                val metrics = g.fontMetrics
                val display = UiText.ellipsize(
                    getText().orEmpty(), metrics, (width - UiTokens.scale(20)).coerceAtLeast(0)
                )
                val x = (width - metrics.stringWidth(display)) / 2
                val y = (height - metrics.height) / 2 + metrics.ascent
                g.drawString(display, x, y)
            } finally {
                g.dispose()
            }
        }
    }
}
