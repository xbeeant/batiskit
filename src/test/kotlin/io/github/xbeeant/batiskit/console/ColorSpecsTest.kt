package io.github.xbeeant.batiskit.console

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color

/** 颜色串纯逻辑：设置页、预览、规则表选色都依赖这一层，离线覆盖边界值 */
class ColorSpecsTest {

    @Test
    fun `双色串解析与规范化`() {
        val spec = ColorSpecs.parse(" ffe9a8 | 5a4700 ")
        assertEquals("#FFE9A8", spec.light)
        assertEquals("#5A4700", spec.dark)
        assertEquals("#FFE9A8|#5A4700", spec.encode())
        assertEquals(spec.encode(), ColorSpecs.normalize("#ffe9a8|#5a4700"))
    }

    @Test
    fun `只填一个 hex 时两个主题共用`() {
        val spec = ColorSpecs.parse("#112233")
        assertEquals("#112233", spec.hex(darkTheme = false))
        assertEquals("#112233", spec.hex(darkTheme = true))
        assertEquals("#112233|#112233", ColorSpecs.normalize("#112233"))
    }

    @Test
    fun `空值与非法值视为不着色`() {
        listOf(null, "", "   ", "not-a-color", "#FFF", "#GGGGGG", "red|blue").forEach {
            assertTrue("[$it] 应视为空", ColorSpecs.parse(it).isEmpty)
            assertEquals("", ColorSpecs.normalize(it))
        }
        assertTrue(ColorSpecs.isInvalid("oops"))
        assertFalse(ColorSpecs.isInvalid(""))
        assertFalse(ColorSpecs.isInvalid(null))
        assertNull(ColorSpecs.color("oops", darkTheme = false))
    }

    @Test
    fun `第二段写错时复用浅色而不是丢掉整项`() {
        val spec = ColorSpecs.parse("#FFE9A8|oops")
        assertEquals("#FFE9A8", spec.light)
        assertEquals("#FFE9A8", spec.dark)
    }

    @Test
    fun `第一段写错时整项不生效`() {
        assertTrue(ColorSpecs.parse("oops|#112233").isEmpty)
        assertNull(ColorSpecs.color("oops|#112233", darkTheme = true))
    }

    @Test
    fun `按主题取到对应的 java awt Color`() {
        val spec = "#C62828|#FF6B6B"
        assertEquals(Color(0xC62828), ColorSpecs.color(spec, darkTheme = false))
        assertEquals(Color(0xFF6B6B), ColorSpecs.color(spec, darkTheme = true))
    }

    @Test
    fun `选浅色保留单独调过的深色`() {
        assertEquals(
            "#AABBCC|#445566",
            ColorSpecs.withThemeColor("#112233|#445566", Color(0xAABBCC), darkTheme = false),
        )
    }

    @Test
    fun `两主题共用一色时选浅色会同步更新深色`() {
        assertEquals(
            "#AABBCC|#AABBCC",
            ColorSpecs.withThemeColor("#112233", Color(0xAABBCC), darkTheme = false),
        )
    }

    @Test
    fun `选深色只替换第二段`() {
        assertEquals(
            "#112233|#010203",
            ColorSpecs.withThemeColor("#112233|#445566", Color(0x010203), darkTheme = true),
        )
        assertEquals(
            "#010203|#010203",
            ColorSpecs.withThemeColor("", Color(0x010203), darkTheme = true),
        )
    }

    @Test
    fun `派生暗色与控制台既有的换算保持一致`() {
        listOf(Color.BLACK, Color(100, 100, 100), Color.WHITE, Color(0xFF, 0xE9, 0xA8)).forEach {
            assertEquals(GrepHighlightColor.fromColor(it), ColorSpecs.fromColor(it))
        }
        assertEquals("#FFFFFF|#4C4C4C", ColorSpecs.fromColor(Color.WHITE))
    }

    @Test
    fun `预览文字自动取对比度更高的黑或白`() {
        assertEquals(Color.BLACK, ColorSpecs.readableTextOn(Color.WHITE))
        assertEquals(Color.WHITE, ColorSpecs.readableTextOn(Color.BLACK))
        assertTrue(ColorSpecs.contrastRatio(Color.WHITE, Color.BLACK) > 20.0)
        assertEquals(1.0, ColorSpecs.contrastRatio(Color.RED, Color.RED), 0.0001)
    }
}
