package io.github.xbeeant.batiskit.ui.components

import io.github.xbeeant.batiskit.ui.UiTokens
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

/**
 * 颜色预览色块：设置页、规则表单元格共用。
 *
 * 画成 Icon 而不是自绘 JLabel 背景，是为了让 Swing 的默认文本渲染照常进行 —— 之前
 * chip 把填充画进 `Border.paintBorder()`，结果把文字盖住。这里色块只占 icon 区域，
 * 文字由宿主组件自己画，互不干扰。
 */
class ColorSwatchIcon(
    private val color: Color?,
    private val side: Int = UiTokens.scale(14),
) : Icon {

    override fun getIconWidth(): Int = side

    override fun getIconHeight(): Int = side

    override fun paintIcon(component: Component, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = UiTokens.scale(4)
            val fill = color
            if (fill == null) {
                // 「无颜色 / 格式不对」：浅底 + 斜线，不用纯白，浅色主题下也能看出边界
                g.color = Color(0xE9EDF3)
                g.fillRoundRect(x, y, side, side, arc, arc)
                g.color = Color(0xC0392B)
                g.drawLine(x + 2, y + side - 3, x + side - 3, y + 2)
            } else {
                g.color = fill
                g.fillRoundRect(x, y, side, side, arc, arc)
            }
            g.color = UiTokens.borderControl
            g.drawRoundRect(x, y, side - 1, side - 1, arc, arc)
        } finally {
            g.dispose()
        }
    }

    companion object {
        /** 表格渲染器用：颜色串 → 当前主题下的色块 */
        fun of(color: Color?): Icon = ColorSwatchIcon(color)
    }
}
