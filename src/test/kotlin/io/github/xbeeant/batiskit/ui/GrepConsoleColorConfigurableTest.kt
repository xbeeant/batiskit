package io.github.xbeeant.batiskit.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xbeeant.batiskit.console.GrepConsoleConfigurable
import io.github.xbeeant.batiskit.ui.components.ColorSpecField
import io.github.xbeeant.batiskit.ui.components.ConsoleColorPreviewPanel
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JLabel
import javax.swing.JTable

/**
 * 真实平台环境里创建 Grep Console 设置页：颜色选择器、预览、规则表色块都得在，
 * 且换成新控件后不能把 isModified 弄成「一打开就是脏的」。
 */
class GrepConsoleColorConfigurableTest : BasePlatformTestCase() {

    fun testColorPickersPreviewAndModifiedState() {
        val configurable = GrepConsoleConfigurable(project)
        try {
            val root = configurable.createComponent()
            configurable.reset()

            val fields = mutableListOf<ColorSpecField>()
            collect(root, ColorSpecField::class.java, fields)
            assertEquals("5 个级别色 + 1 个命中底色", 6, fields.size)

            val previews = mutableListOf<ConsoleColorPreviewPanel>()
            collect(root, ConsoleColorPreviewPanel::class.java, previews)
            assertEquals("应有且只有一份整页效果预览", 1, previews.size)

            assertFalse("reset 之后设置页不应显示未保存", configurable.isModified)

            val tables = mutableListOf<JTable>()
            collect(root, JTable::class.java, tables)
            val rulesTable = tables.firstOrNull { it.columnCount == 9 }
            assertNotNull("应存在关键词高亮规则表", rulesTable)
            val rendered = rulesTable!!.getCellRenderer(0, 5).getTableCellRendererComponent(
                rulesTable, "#112233|#445566", false, false, 0, 5
            )
            assertTrue(
                "颜色列应带色块预览，且文字仍可见",
                rendered is JLabel && rendered.icon != null && rendered.text == "#112233|#445566"
            )

            fields[0].spec = "#123456|#654321"
            assertTrue("改颜色后应标记为已修改", configurable.isModified)

            root.setSize(980, 900)
            repeat(2) { layoutRecursively(root) }
            val image = BufferedImage(980, 900, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                root.paint(graphics)
            } finally {
                graphics.dispose()
            }
            val dir = File("build/ui-snapshots").apply { mkdirs() }
            val output = File(dir, "grep-console-color-settings.png")
            ImageIO.write(image, "png", output)
            assertTrue("设置页快照应成功写出", output.length() > 2_000)
        } finally {
            configurable.disposeUIResources()
        }
    }

    private fun <T : Component> collect(component: Component, type: Class<T>, out: MutableList<T>) {
        if (type.isInstance(component)) out += type.cast(component)
        if (component is Container) component.components.forEach { collect(it, type, out) }
    }

    private fun layoutRecursively(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutRecursively)
        }
    }
}
