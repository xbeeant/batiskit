package io.github.xbeeant.batiskit.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import io.github.xbeeant.batiskit.api.ui.ApiListPanel
import io.github.xbeeant.batiskit.log.ui.MyBatisLogPanel
import io.github.xbeeant.batiskit.string.ui.StringPanel
import io.github.xbeeant.batiskit.ui.UiTokens.Density
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JTextPane
import javax.swing.text.JTextComponent

/**
 * 真实平台环境里核对三个工具窗口：输入框 padding、字符串列表密度切换、离屏快照。
 *
 * 纯 Swing 测试覆盖不到 PropertiesComponent / project service / 平台 LAF，
 * 而用户反馈的三张截图恰好都来自这些页面，所以必须在平台 fixture 里渲染一次。
 */
class CompactPagesTest : BasePlatformTestCase() {

    fun testInputAreasHaveThinPadding() {
        listOf(StringPanel(project).component, myBatisLogTextPage()).forEach { page ->
            val editors = descendants(page)
                .filterIsInstance<JTextComponent>()
                .filter { it is JBTextArea || it is JTextPane }
                .filter { it.border != null }
            assertTrue("页面里应有可输入的文本区", editors.isNotEmpty())
            editors.forEach { editor ->
                val insets = editor.border.getBorderInsets(editor)
                assertTrue(
                    "文本区上下 padding 过大：${editor.javaClass.simpleName} ${insets.top}/${insets.bottom}",
                    insets.top <= Density.fieldPadV && insets.bottom <= Density.fieldPadV
                )
            }
        }
    }

    fun testStringListUsesSingleRowWhenInputIsEmpty() {
        val page = StringPanel(project).component
        val list = descendants(page).filterIsInstance<JBList<*>>().single()
        assertTrue("操作列表不能变空，实际 ${list.model.size} 项", list.model.size >= 15)
        assertEquals("空输入时列表应切成单行行高", Density.listRowSingle, list.fixedCellHeight)

        val input = descendants(page).filterIsInstance<JBTextArea>().first()
        input.text = "helloWorld"
        assertEquals("有输入时应恢复两行预览行高", Density.listRowDouble, list.fixedCellHeight)
    }

    fun testThreePagesRenderAndWriteSnapshots() {
        val dir = File("build/ui-snapshots").apply { mkdirs() }
        val pages = listOf(
            "compact-api-panel" to ApiListPanel(project).component,
            "compact-string-panel" to StringPanel(project).component,
            "compact-mybatislog-text" to myBatisLogTextPage(),
        )
        pages.forEach { (name, page) ->
            page.setSize(560, 900)
            layoutRecursively(page)
            val image = BufferedImage(560, 900, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                page.paint(graphics)
            } finally {
                graphics.dispose()
            }
            val output = File(dir, "$name.png")
            ImageIO.write(image, "png", output)
            assertTrue("$name 快照应成功写出", output.length() > 3_000)

            var painted = 0
            for (y in 0 until 900 step 4) {
                for (x in 0 until 560 step 4) {
                    if (image.getRGB(x, y) != 0) painted++
                }
            }
            assertTrue("$name 快照不应是空白图（实际 $painted）", painted > 3_000)
        }
    }

    private fun myBatisLogTextPage(): Component {
        val page = MyBatisLogPanel(project).component
        // 用户截图里的「文本还原」页：左右两个文本区 + 按钮行 + 状态行
        descendants(page).filterIsInstance<JBTabbedPane>().single().selectedIndex = 1
        return page
    }

    private fun descendants(root: Component): List<Component> {
        val result = mutableListOf<Component>()
        fun walk(component: Component) {
            result.add(component)
            if (component is Container) component.components.forEach(::walk)
        }
        walk(root)
        return result
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
