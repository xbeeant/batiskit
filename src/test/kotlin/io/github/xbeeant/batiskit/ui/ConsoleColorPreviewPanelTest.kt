package io.github.xbeeant.batiskit.ui

import io.github.xbeeant.batiskit.console.GrepConsoleSettingsState
import io.github.xbeeant.batiskit.console.GrepHighlightColor
import io.github.xbeeant.batiskit.console.HighlightRule
import io.github.xbeeant.batiskit.console.LogLevel
import io.github.xbeeant.batiskit.ui.components.ConsoleColorPreviewPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.text.StyleConstants

/** 整页预览：与控制台共用 LogHighlighter，因此断言的是「真的用上了表单里的颜色」 */
class ConsoleColorPreviewPanelTest {

    private val levelColors = LogLevel.entries.associateWith { "#FF0000|#0000FF" }

    @Test
    fun `浅色与深色栏各取对应那一份级别色和命中底色`() {
        val panel = ConsoleColorPreviewPanel()
        panel.update(
            ConsoleColorPreviewPanel.PreviewState(
                levelColors = levelColors,
                matchColor = "#00FF00|#FFFF00",
                highlightKeywords = false,
            )
        )
        val light = panel.lightPane.styledDocument
        val dark = panel.darkPane.styledDocument
        val text = light.getText(0, light.length)
        val errorAt = text.indexOf("ERROR")
        val matchAt = text.indexOf("pay/order")
        assertTrue("预览应包含模拟的 ERROR 日志行", errorAt >= 0)
        assertTrue("预览应包含 grep 命中片段", matchAt >= 0)

        assertEquals(Color(0xFF0000), StyleConstants.getForeground(light.getCharacterElement(errorAt).attributes))
        assertEquals(Color(0x0000FF), StyleConstants.getForeground(dark.getCharacterElement(errorAt).attributes))
        assertEquals(Color(0x00FF00), StyleConstants.getBackground(light.getCharacterElement(matchAt).attributes))
        assertEquals(Color(0xFFFF00), StyleConstants.getBackground(dark.getCharacterElement(matchAt).attributes))
    }

    @Test
    fun `关键词规则色优先于级别色并保留加粗`() {
        val panel = ConsoleColorPreviewPanel()
        panel.update(
            ConsoleColorPreviewPanel.PreviewState(
                levelColors = levelColors,
                rules = listOf(HighlightRule(pattern = "Exception", color = "#123456|#ABCDEF", bold = true)),
                highlightKeywords = true,
            )
        )
        val doc = panel.lightPane.styledDocument
        val at = doc.getText(0, doc.length).indexOf("Exception")
        val attributes = doc.getCharacterElement(at).attributes
        assertEquals(Color(0x123456), StyleConstants.getForeground(attributes))
        assertTrue(StyleConstants.isBold(attributes))
        assertEquals(
            Color(0xABCDEF),
            StyleConstants.getForeground(panel.darkPane.styledDocument.getCharacterElement(at).attributes),
        )
    }

    @Test
    fun `关闭着色后预览回落到默认文字色`() {
        val panel = ConsoleColorPreviewPanel()
        panel.update(
            ConsoleColorPreviewPanel.PreviewState(
                levelColors = levelColors,
                colorizeLevel = false,
                highlightKeywords = false,
            )
        )
        val attributes = panel.lightPane.styledDocument.getCharacterElement(0).attributes
        assertNotEquals(Color(0xFF0000), StyleConstants.getForeground(attributes))
    }

    @Test
    fun `默认配色渲染成快照供人工核对`() {
        val panel = ConsoleColorPreviewPanel()
        val defaults = GrepConsoleSettingsState.defaultLevelColors()
        panel.update(
            ConsoleColorPreviewPanel.PreviewState(
                levelColors = LogLevel.entries.associateWith { defaults[it.name].orEmpty() },
                rules = GrepConsoleSettingsState.defaultRules(),
                matchColor = GrepHighlightColor.DEFAULT,
            )
        )
        panel.setSize(900, 240)
        repeat(2) { layoutRecursively(panel) }
        val image = BufferedImage(900, 240, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            panel.paint(graphics)
        } finally {
            graphics.dispose()
        }
        val dir = File("build/ui-snapshots").apply { mkdirs() }
        val output = File(dir, "grep-console-color-preview.png")
        ImageIO.write(image, "png", output)
        assertTrue("快照应成功写出", output.length() > 2_000)
    }

    private fun layoutRecursively(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutRecursively)
        }
    }
}
