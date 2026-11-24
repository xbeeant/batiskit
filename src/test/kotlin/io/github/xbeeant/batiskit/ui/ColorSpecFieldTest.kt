package io.github.xbeeant.batiskit.ui

import io.github.xbeeant.batiskit.console.ColorSpecs
import io.github.xbeeant.batiskit.ui.components.ColorSpecField
import io.github.xbeeant.batiskit.ui.components.ColorUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import javax.swing.JButton

/**
 * 颜色选择器的交互与像素回归：色板点击要写回配置串，预览要真的按主题画出颜色。
 * 平台 ColorPicker 是模态框，不能进 headless 测试，所以注入固定返回值的 picker。
 */
class ColorSpecFieldTest {

    private var picked: Color? = null
    private val picker: (Component, Color, String) -> Color? = { _, _, _ -> picked }

    @Test
    fun `设置配置串后浅深色块与规范化值同步`() {
        val field = ColorSpecField(defaultSpec = "#C62828|#FF6B6B", picker = picker)
        field.spec = "#112233|#445566"
        assertEquals(Color(0x112233), field.lightButton.swatchColor)
        assertEquals(Color(0x445566), field.darkButton.swatchColor)
        assertEquals("#112233|#445566", field.normalizedSpec)
    }

    @Test
    fun `点浅色色块写回配置串并通知预览刷新`() {
        var changes = 0
        val field = ColorSpecField(picker = picker, onChange = { changes++ })
        field.spec = "#112233|#445566"
        val beforeClick = changes
        picked = Color(0xABCDEF)
        field.lightButton.doClick()
        assertEquals("#ABCDEF|#445566", field.spec)
        assertEquals(Color(0xABCDEF), field.lightButton.swatchColor)
        assertEquals("色板选色后预览应再刷新一次", beforeClick + 1, changes)
    }

    @Test
    fun `点深色色块只改深色那一份`() {
        val field = ColorSpecField(picker = picker)
        field.spec = "#112233|#445566"
        picked = Color(0x010203)
        field.darkButton.doClick()
        assertEquals("#112233|#010203", field.spec)
        assertEquals(Color(0x010203), field.darkButton.swatchColor)
    }

    @Test
    fun `两主题共用一色时选浅色会一起更新`() {
        val field = ColorSpecField(picker = picker)
        field.spec = "#112233"
        picked = Color(0xABCDEF)
        field.lightButton.doClick()
        assertEquals("#ABCDEF|#ABCDEF", field.spec)
    }

    @Test
    fun `取消色板不改动当前颜色`() {
        val field = ColorSpecField(picker = picker)
        field.spec = "#112233|#445566"
        picked = null
        field.lightButton.doClick()
        assertEquals("#112233|#445566", field.spec)
    }

    @Test
    fun `手输非法值色块变空且规范化结果为空`() {
        val field = ColorSpecField(picker = picker)
        field.spec = "oops"
        assertTrue(ColorSpecs.isInvalid(field.spec))
        assertEquals("", field.normalizedSpec)
        assertNull(field.lightButton.swatchColor)
        assertNull(field.darkButton.swatchColor)
    }

    @Test
    fun `默认 自动 清除三个按钮都写到配置串`() {
        val field = ColorSpecField(
            defaultSpec = "#FFE9A8|#5A4700",
            allowEmpty = true,
            picker = picker,
        )
        field.spec = "#FFFFFF|#000000"
        click(field, "默认")
        assertEquals("#FFE9A8|#5A4700", field.spec)

        click(field, "自动")
        assertEquals(ColorSpecs.fromColor(Color(0xFFE9A8)), field.spec)

        click(field, "清除")
        assertEquals("", field.spec)
        assertNull(field.lightButton.swatchColor)
    }

    @Test
    fun `背景色预览左右两半按主题渲染不同颜色`() {
        val field = ColorSpecField(usage = ColorUsage.BACKGROUND, picker = picker)
        field.spec = "#FF0000|#0000FF"
        val preview = previewOf(field)
        val image = paint(preview)

        val half = image.width / 2
        val red = Color(0xFF0000)
        val blue = Color(0x0000FF)
        val leftRed = count(image, 2, half - 2, red)
        val rightBlue = count(image, half + 2, image.width - 2, blue)
        val leftBlue = count(image, 2, half - 2, blue)
        val rightRed = count(image, half + 2, image.width - 2, red)
        assertTrue("左半应渲染浅色主题的红底，实际 $leftRed px", leftRed > 200)
        assertTrue("右半应渲染深色主题的蓝底，实际 $rightBlue px", rightBlue > 200)
        assertTrue("两半不能串色（leftBlue=$leftBlue, rightRed=$rightRed）", leftBlue < 30 && rightRed < 30)
    }

    @Test
    fun `前景色预览能画出对应主题的文字色`() {
        val field = ColorSpecField(usage = ColorUsage.FOREGROUND, picker = picker)
        field.spec = "#FF0000|#00AA00"
        val image = paint(previewOf(field))
        val half = image.width / 2
        assertTrue(count(image, 2, half - 2, Color(0xFF0000)) > 5)
        assertTrue(count(image, half + 2, image.width - 2, Color(0x00AA00)) > 5)
    }

    private fun click(field: Component, text: String) {
        val buttons = mutableListOf<Component>()
        collect(field, buttons)
        val button = buttons.filterIsInstance<JButton>().firstOrNull { it.text == text }
        assertNotNull("应存在「$text」按钮", button)
        button!!.doClick()
    }

    private fun previewOf(field: Component): Component {
        val components = mutableListOf<Component>()
        collect(field, components)
        val preview = components.firstOrNull {
            (it as? javax.swing.JComponent)?.toolTipText?.startsWith("颜色预览") == true
        }
        assertNotNull("应存在行内颜色预览", preview)
        return preview!!
    }

    private fun paint(component: Component): BufferedImage {
        component.setSize(component.preferredSize)
        val image = BufferedImage(component.width, component.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            component.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun count(image: BufferedImage, from: Int, until: Int, target: Color): Int {
        var found = 0
        for (y in 2 until image.height - 2) {
            for (x in from until minOf(until, image.width)) {
                val color = Color(image.getRGB(x, y))
                val distance = kotlin.math.abs(color.red - target.red) +
                    kotlin.math.abs(color.green - target.green) +
                    kotlin.math.abs(color.blue - target.blue)
                if (distance < 45) found++
            }
        }
        return found
    }

    private fun collect(component: Component, out: MutableList<Component>) {
        out += component
        if (component is Container) component.components.forEach { collect(it, out) }
    }
}
