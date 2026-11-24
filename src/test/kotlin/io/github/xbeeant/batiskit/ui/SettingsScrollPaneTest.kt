package io.github.xbeeant.batiskit.ui

import com.intellij.ui.components.JBScrollPane
import io.github.xbeeant.batiskit.json.ui.JsonOptionsPanel
import io.github.xbeeant.batiskit.json.ui.JsonSettingsConfigurable
import io.github.xbeeant.batiskit.ui.components.FixedHeightRow
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JPanel

/**
 * 走完整的设置页创建链：JScrollPane → JViewport → view。
 *
 * 回归曾导致「设置页全空白」的 bug：给 JViewport 设 border 会抛
 * IllegalArgumentException("JViewport.setBorder() not supported")，
 * createComponent() 失败后平台拿不到组件，页面就是空的。
 */
class SettingsScrollPaneTest {

    @Test
    fun `设置页滚动容器可创建且所有行可见`() {
        val view = JsonOptionsPanel({}, {}, showTemplateEditor = true).component
        val component = JsonSettingsConfigurable.settingsScrollPane(view)
        assertTrue("设置页应返回滚动容器", component is JBScrollPane)
        val scroll = component as JBScrollPane
        assertNotNull("留白必须由 JScrollPane.setViewportBorder 承担", scroll.viewportBorder)

        val root = JPanel(BorderLayout()).apply { add(scroll, BorderLayout.CENTER) }
        root.setSize(900, 700)
        repeat(3) { layoutOnce(root) }

        assertTrue("view 必须有可见尺寸：${view.size}", view.width > 100 && view.height > 100)
        val rows = view.components.filterIsInstance<FixedHeightRow>()
        assertTrue("至少应有 8 个表单行，实际 ${rows.size}", rows.size >= 8)
        rows.forEachIndexed { index, row ->
            assertTrue("第 $index 行被压成 0 高（页面空白）", row.height > 8)
            assertTrue("第 $index 行位置异常：y=${row.y}", row.y >= 0)
        }

        val image = BufferedImage(900, 700, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            root.paint(graphics)
        } finally {
            graphics.dispose()
        }
        val dir = File("build/ui-snapshots").apply { mkdirs() }
        val output = File(dir, "settings-json-scrollpane.png")
        ImageIO.write(image, "png", output)
        assertTrue("快照应成功写出", output.length() > 2_000)

        var painted = 0
        for (y in 0 until 400 step 4) {
            for (x in 0 until 900 step 4) {
                if (image.getRGB(x, y) != 0) painted++
            }
        }
        assertTrue("设置页顶部区域不应是空白（着色像素 $painted）", painted > 500)
    }

    private fun layoutOnce(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutOnce)
        }
    }
}
