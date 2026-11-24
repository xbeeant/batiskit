package io.github.xbeeant.batiskit.ui.components

import com.intellij.util.ui.JBUI
import io.github.xbeeant.batiskit.ui.UiText
import io.github.xbeeant.batiskit.ui.UiTokens
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

/**
 * 方法 / 级别徽标：pill 底色 + 等宽粗体文字。
 *
 * 文字本身承载语义，颜色只作增强（覆盖红绿色觉）；配色全部来自 [UiTokens.badge]。
 * 用在列表渲染器里时由渲染器设置背景，组件自身保持透明。
 */
class MethodBadge(text: String = "") : JComponent() {

    var badgeText: String = text
        set(value) {
            field = value
            toolTipText = value
            revalidate()
            repaint()
        }

    init {
        isOpaque = false
        font = UiTokens.fontBadge()
        toolTipText = text
        alignmentY = CENTER_ALIGNMENT
    }

    override fun getPreferredSize(): Dimension {
        val width = getFontMetrics(font).stringWidth(badgeText) + UiTokens.scale(14)
        return Dimension(maxOf(UiTokens.scale(52), width), UiTokens.scale(18))
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val style = UiTokens.badge(badgeText)
            val arc = UiTokens.scale(9)
            g.color = style.background
            g.fillRoundRect(0, 0, width, height, arc, arc)
            g.color = style.foreground
            g.font = font
            val metrics = g.fontMetrics
            val available = (width - UiTokens.scale(10)).coerceAtLeast(0)
            val display = UiText.ellipsize(badgeText, metrics, available)
            val x = (width - metrics.stringWidth(display)) / 2
            val y = (height - metrics.height) / 2 + metrics.ascent
            g.drawString(display, x, y)
        } finally {
            g.dispose()
        }
    }
}

/** 状态栏 / Banner 用的语义色圆点；不承载动画，语义由旁边的文字冗余表达 */
class StatusDot : JComponent() {

    var dotColor: Color = UiTokens.success
        set(value) {
            field = value
            repaint()
        }

    init {
        isOpaque = false
        val size = JBUI.scale(8)
        preferredSize = Dimension(size, size)
        minimumSize = preferredSize
        maximumSize = preferredSize
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = dotColor
            g.fillOval(0, 0, width, height)
        } finally {
            g.dispose()
        }
    }
}
