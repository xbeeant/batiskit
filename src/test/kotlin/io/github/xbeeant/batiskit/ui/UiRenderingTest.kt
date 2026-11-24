package io.github.xbeeant.batiskit.ui

import io.github.xbeeant.batiskit.ui.components.FilterChipGroup
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.JPanel

/**
 * 绘制回归测试：筛选 chip 的文字必须真的「看得见」。
 *
 * 曾经的 bug 是把 pill 填充画在 Border.paintBorder 里，而 Swing 的绘制顺序是
 * paintComponent → paintBorder，后画的底色把已经绘制的文字整块盖住了。
 * 这里把 chip 画到离屏图，统计中心区域与底色不同的像素，防止回归。
 */
class UiRenderingTest {

    @Test
    fun `筛选 chip 绘制后中心区域必须存在文字像素`() {
        val group = FilterChipGroup(listOf("GET", "POST"), listOf("GET", "POST")) {}
        group.setSize(300, 60)
        group.doLayout()

        val chip = group.getComponent(0)
        assertTrue("chip 应有可绘制的尺寸", chip.width > 40 && chip.height > 12)

        val image = BufferedImage(chip.width, chip.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            chip.paint(graphics)
        } finally {
            graphics.dispose()
        }

        // 取文字所在的中段：避开四周 1px 描边与圆角
        val x0 = (chip.width * 0.15).toInt()
        val x1 = (chip.width * 0.85).toInt()
        val y0 = (chip.height * 0.2).toInt().coerceAtLeast(1)
        val y1 = (chip.height * 0.8).toInt().coerceAtMost(chip.height - 1)
        val background = Color(image.getRGB(chip.width / 2, 1))
        var textPixels = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val color = Color(image.getRGB(x, y), true)
                if (colorDistance(color, background) > 45) textPixels++
            }
        }
        assertTrue(
            "chip 文字被背景覆盖：中心区域只有 $textPixels 个前景像素",
            textPixels > 20
        )
    }

    @Test
    fun `筛选 chip 组换行后高度覆盖所有行`() {
        val labels = listOf("GET", "POST", "PUT", "DELETE", "PATCH", "REQUEST", "SCHEDULE", "DUBBO")
        val holder = JPanel(java.awt.BorderLayout()).apply { setSize(240, 400) }
        val group = FilterChipGroup(labels, labels) {}
        holder.add(group, java.awt.BorderLayout.NORTH)
        holder.doLayout()
        group.doLayout()
        val chipHeight = group.getComponent(0).height.coerceAtLeast(1)
        val rows = group.components.indices.map { group.getComponent(it).y }.distinct().size
        assertTrue("窄宽度下 chip 应自动换行", rows >= 2)
        assertTrue(
            "WrapLayout 首选高度需覆盖全部 $rows 行（实际 ${group.preferredSize.height}，单行 $chipHeight）",
            group.preferredSize.height >= chipHeight * rows
        )
    }

    private fun colorDistance(a: Color, b: Color): Int =
        kotlin.math.abs(a.red - b.red) + kotlin.math.abs(a.green - b.green) + kotlin.math.abs(a.blue - b.blue)
}
