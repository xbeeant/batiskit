package io.github.xbeeant.batiskit.rainbow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color

/** 颜色生成器的纯逻辑回归（只依赖 JDK Color，不碰平台 API） */
class RainbowPaletteTest {

    @Test
    fun `生成指定数量并把越界值收敛到合法区间`() {
        assertEquals(5, RainbowPalette.generate(5).size)
        assertEquals(1, RainbowPalette.generate(0).size)
        assertEquals(1, RainbowPalette.generate(-3).size)
        assertEquals(RainbowPalette.MAX_COLORS, RainbowPalette.generate(99).size)
    }

    @Test
    fun `hex 格式合法且能被反解成色相`() {
        for (color in RainbowPalette.generate(10)) {
            val hex = RainbowPalette.hex(color)
            assertTrue("$hex 应为 #RRGGBB", hex.matches(Regex("#[0-9A-F]{6}")))
            assertNotNull(RainbowPalette.baseHue(hex))
        }
    }

    @Test
    fun `不同色相起点产生不同配色`() {
        val red = RainbowPalette.generate(5, RainbowPalette.Hue.RED)
        val blue = RainbowPalette.generate(5, RainbowPalette.Hue.BLUE)
        assertNotEquals(RainbowPalette.hex(red[0]), RainbowPalette.hex(blue[0]))
        // 同一参数必须稳定，方便设置页重复预览
        assertEquals(
            RainbowPalette.hex(RainbowPalette.generate(5, RainbowPalette.Hue.RED)[0]),
            RainbowPalette.hex(red[0]),
        )
    }

    @Test
    fun `单色档位饱和度低且明度逐层递减`() {
        val colors = RainbowPalette.generate(5, RainbowPalette.Hue.MONOCHROME, RainbowPalette.Luminosity.BRIGHT)
        val hsb = colors.map { Color.RGBtoHSB(it.red, it.green, it.blue, null) }
        assertTrue(hsb.all { it[1] <= 0.1f })
        assertTrue(hsb.first()[2] > hsb.last()[2])
    }

    @Test
    fun `亮度档位影响明度`() {
        val bright = RainbowPalette.generate(1, RainbowPalette.Hue.BLUE, RainbowPalette.Luminosity.BRIGHT)[0]
        val dark = RainbowPalette.generate(1, RainbowPalette.Hue.BLUE, RainbowPalette.Luminosity.DARK)[0]
        val b = Color.RGBtoHSB(bright.red, bright.green, bright.blue, null)
        val d = Color.RGBtoHSB(dark.red, dark.green, dark.blue, null)
        assertTrue(b[2] > d[2])
        assertTrue(b[1] > 0.5f)
    }

    @Test
    fun `起始色 hex 提取色相且非法值回退`() {
        assertEquals(0f, RainbowPalette.baseHue("#FF0000")!!, 1f)
        assertEquals(120f, RainbowPalette.baseHue("00FF00")!!, 1f)
        assertNull(RainbowPalette.baseHue("#GG0000"))
        assertNull(RainbowPalette.baseHue("#FFF"))
        assertNull(RainbowPalette.baseHue(null))
        val fromHex = RainbowPalette.generate(1, RainbowPalette.Hue.BLUE, baseHex = "#FF0000")[0]
        val fromRed = RainbowPalette.generate(1, RainbowPalette.Hue.RED)[0]
        assertEquals(RainbowPalette.hex(fromRed), RainbowPalette.hex(fromHex))
    }

    @Test
    fun `未知名称回退到默认档位`() {
        assertEquals(RainbowPalette.Hue.BLUE, RainbowPalette.Hue.fromName("nope"))
        assertEquals(RainbowPalette.Hue.GREEN, RainbowPalette.Hue.fromName("green"))
        assertEquals(RainbowPalette.Luminosity.BRIGHT, RainbowPalette.Luminosity.fromName(null))
        assertEquals(RainbowPalette.Luminosity.DARK, RainbowPalette.Luminosity.fromName("DARK"))
    }
}
