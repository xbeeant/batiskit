package io.github.xbeeant.batiskit.api.ui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.SwingUtilities

/**
 * 会换行的 FlowLayout：原生实现的首选高度只按「单行」计算，
 * 放在 BorderLayout 的 NORTH 里时，窄面板换行后多出来的行会被父容器裁掉。
 *
 * 与经典实现的关键差别：组件树**首次布局时宽度为 0**，不能按「无限宽 = 单行」估算，
 * 否则 BoxLayout 只会分配一行高度，第二行文字直接消失。这里改用典型工具窗口宽度
 * [fallbackWidth] 估算行数，宁可首屏预留多一点高度，也不裁字；实际宽度确定后再收敛。
 */
class WrapLayout : FlowLayout {

    /** 还没有真实宽度时用于估算行数的典型工具窗口宽度 */
    var fallbackWidth: Int = 320

    /** 已经向上报过的首选高度，避免父容器高度与首选高度永远不相等时反复 revalidate */
    private var lastReportedHeight = -1

    constructor() : super()

    constructor(align: Int) : super(align)

    constructor(align: Int, hgap: Int, vgap: Int) : super(align, hgap, vgap)

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)

    override fun minimumLayoutSize(target: Container): Dimension {
        val minimum = layoutSize(target, false)
        val insets = target.insets
        minimum.width = (minimum.width - hgap * 2).coerceAtLeast(insets.left + insets.right)
        minimum.height = (minimum.height - vgap * 2).coerceAtLeast(insets.top + insets.bottom)
        return minimum
    }

    override fun layoutContainer(target: Container) {
        super.layoutContainer(target)
        // 宽度变化导致行数变化后，让父容器按新的首选高度重新分配空间（同一高度只触发一次）
        val wanted = preferredLayoutSize(target).height
        if (wanted != target.height && wanted != lastReportedHeight) {
            lastReportedHeight = wanted
            SwingUtilities.invokeLater { target.revalidate() }
        }
    }

    /** 按容器宽度模拟逐行摆放，返回真实的首选/最小尺寸 */
    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            val insets = target.insets
            val measured = target.width.takeIf { it > 0 }
                ?: target.parent?.width?.takeIf { it > 0 }
            val hasRealWidth = measured != null
            val outerWidth = measured ?: fallbackWidth.coerceAtLeast(1)
            val availableWidth = (outerWidth - insets.left - insets.right).coerceAtLeast(1)
            var rowWidth = 0
            var rowHeight = 0
            var maxWidth = 0
            // 必须计入容器 insets：带边框的状态栏 / 动作行否则会被少分配十几像素，文字被裁掉
            var totalHeight = insets.top + insets.bottom + vgap * 2

            for (i in 0 until target.componentCount) {
                val component = target.getComponent(i)
                if (!component.isVisible) continue
                val size = if (preferred) component.preferredSize else component.minimumSize
                if (rowWidth > 0 && rowWidth + hgap + size.width > availableWidth) {
                    totalHeight += rowHeight + vgap
                    maxWidth = maxOf(maxWidth, rowWidth)
                    rowWidth = 0
                    rowHeight = 0
                }
                if (rowWidth > 0) rowWidth += hgap
                rowWidth += size.width
                rowHeight = maxOf(rowHeight, size.height)
            }
            totalHeight += rowHeight
            maxWidth = maxOf(maxWidth, rowWidth) + hgap * 2 + insets.left + insets.right

            // 有真实宽度时按容器宽度撑满；估算时不要用一个假宽度把父容器撑出去
            val width = if (hasRealWidth) outerWidth else minOf(maxWidth, outerWidth)
            return Dimension(width.coerceAtLeast(0), totalHeight.coerceAtLeast(0))
        }
    }
}
