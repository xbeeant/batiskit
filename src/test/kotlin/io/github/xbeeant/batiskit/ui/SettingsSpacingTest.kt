package io.github.xbeeant.batiskit.ui

import com.intellij.ui.components.JBLabel
import io.github.xbeeant.batiskit.json.ui.JsonOptionsPanel
import io.github.xbeeant.batiskit.ui.components.FixedHeightRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * 设置页行间距回归测试。
 *
 * 复现的是：BoxLayout(Y_AXIS) 把多余高度按 maximumSize 权重分给每一行，
 * 普通 JPanel 的 maximumSize 无限，于是行间距随窗口高度变大。
 * 对照组保留旧写法，用来证明断言确实能捕获这个 bug。
 */
class SettingsSpacingTest {

    /** 对照组：旧的「普通 JPanel 行 + vertical glue」在 700px 视口里被拉散 */
    @Test
    fun `未固定高度的行会被 BoxLayout 拉伸`() {
        val loose = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            repeat(8) { index ->
                add(JPanel(BorderLayout()).apply {
                    add(JBLabel("设置项 $index"), BorderLayout.CENTER)
                })
            }
            add(Box.createVerticalGlue())
        }
        loose.setSize(900, 700)
        layoutRecursively(loose)

        val rows = loose.components.filterIsInstance<JPanel>()
        val span = rows.last().y - rows.first().y
        assertTrue("旧写法应被明显拉伸（实际跨度 $span px），否则测试无法捕获 bug", span > 300)
    }

    /** FixedHeightRow 的 maximumSize 锁到当前首选高度，内容变化后依然同步 */
    @Test
    fun `固定高度行不随容器变高且随内容自适应`() {
        val label = JBLabel("一行")
        val row = FixedHeightRow(label)
        val preferred = row.preferredSize.height
        assertEquals("maximumSize.height 必须等于首选高度", preferred, row.maximumSize.height)

        label.text = "<html>两行<br>内容</html>"
        assertEquals("内容变化后最大高度要重新计算", row.preferredSize.height, row.maximumSize.height)

        val host = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            repeat(4) { add(FixedHeightRow(JBLabel("设置项 $it"))) }
            add(Box.createVerticalGlue())
        }
        host.setSize(900, 700)
        layoutRecursively(host)
        val rows = host.components.filterIsInstance<FixedHeightRow>()
        assertTrue("4 行内容应贴顶，而不是铺满 700px", rows.last().y + rows.last().height < 200)
    }

    /** 真实「JSON → Java 对象」设置页：10 行紧凑排布，剩余空间全给 glue */
    @Test
    fun `JSON 转对象设置页行间距紧凑且内容贴顶`() {
        val panel = JsonOptionsPanel({}, {}, showTemplateEditor = true).component
        panel.setSize(900, 700)
        layoutRecursively(panel)

        val rows = panel.components.filterIsInstance<FixedHeightRow>()
        assertTrue("应设置页至少有 8 个固定高度行，实际 ${rows.size}", rows.size >= 8)

        rows.zipWithNext { previous, next ->
            val gap = next.y - (previous.y + previous.height)
            assertTrue(
                "相邻行间距被拉伸到 $gap px（y=${previous.y} → ${next.y}）",
                gap in 0..8
            )
        }
        val bottom = rows.last().y + rows.last().height
        assertTrue("内容应贴顶而不是铺满 700px 视口（底部 $bottom px）", bottom < 420)

        val glue = panel.components.filterIsInstance<Box.Filler>().singleOrNull()
        assertTrue("末尾应有 vertical glue 吸收剩余空间", glue != null && glue.height > 200)

        // 离屏快照供人工核对：文字完整、行距均匀、无空白断层
        val image = BufferedImage(900, 700, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }
        val dir = File("build/ui-snapshots").apply { mkdirs() }
        val output = File(dir, "settings-json-compact.png")
        ImageIO.write(image, "png", output)
        assertTrue("快照应成功写出", output.length() > 2_000)
    }

    private fun layoutRecursively(component: Component) {
        repeat(2) { layoutOnce(component) }
    }

    private fun layoutOnce(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutOnce)
        }
    }
}
