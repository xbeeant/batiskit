package io.github.xbeeant.batiskit.console

import com.intellij.util.xmlb.XmlSerializer
import io.github.xbeeant.batiskit.console.grep.GrepMatchHighlightFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.Color

class GrepHighlightColorTest {

    @Test
    fun `默认底色是亮黄与暗棕`() {
        assertEquals("#FFE9A8|#5A4700", GrepHighlightColor.DEFAULT)
        assertEquals(GrepHighlightColor.DEFAULT, GrepConsoleSettingsState().matchHighlightColor)
    }

    @Test
    fun `空值与非法值回退默认`() {
        listOf("", "   ", "not-a-color", "#FFF", "#GGGGGG", "red|blue", "#FFE9A8|oops").forEach {
            assertEquals("输入 [$it]", GrepHighlightColor.DEFAULT, GrepHighlightColor.normalize(it))
        }
        assertEquals(GrepHighlightColor.DEFAULT, GrepHighlightColor.normalize(null))
    }

    @Test
    fun `只填一个 hex 时两个主题共用`() {
        assertEquals("#112233|#112233", GrepHighlightColor.normalize("#112233"))
    }

    @Test
    fun `补井号并统一成大写`() {
        assertEquals("#FFE9A8|#5A4700", GrepHighlightColor.normalize(" ffe9a8 | 5a4700 "))
    }

    @Test
    fun `多余的竖线段不影响解析`() {
        assertEquals("#FFE9A8|#5A4700", GrepHighlightColor.normalize("#FFE9A8|#5A4700|extra"))
    }

    @Test
    fun `色板颜色转成亮暗两份且结果合法`() {
        assertEquals("#000000|#000000", GrepHighlightColor.fromColor(Color.BLACK))
        assertEquals("#646464|#1E1E1E", GrepHighlightColor.fromColor(Color(100, 100, 100)))
        assertEquals("#FFFFFF|#4C4C4C", GrepHighlightColor.fromColor(Color.WHITE))
        listOf(Color.BLACK, Color(100, 100, 100), Color.WHITE, Color(0xFF, 0xE9, 0xA8)).forEach { color ->
            val spec = GrepHighlightColor.fromColor(color)
            assertEquals(spec, GrepHighlightColor.normalize(spec))
            assertNotNull(GrepConsoleSettings.parseColor(spec))
        }
    }

    @Test
    fun `state normalize 补齐并校验底色`() {
        val state = GrepConsoleSettingsState().apply { matchHighlightColor = "oops" }
        state.normalize()
        assertEquals(GrepHighlightColor.DEFAULT, state.matchHighlightColor)

        state.matchHighlightColor = "#ABCDEF"
        state.normalize()
        assertEquals("#ABCDEF|#ABCDEF", state.matchHighlightColor)
    }

    @Test
    fun `底色参与设置序列化往返`() {
        val state = GrepConsoleSettingsState().apply { matchHighlightColor = "#010203|#040506" }
        val restored = XmlSerializer.deserialize(
            XmlSerializer.serialize(state), GrepConsoleSettingsState::class.java
        )
        assertEquals("#010203|#040506", restored.matchHighlightColor)
    }

    @Test
    fun `parseColor 单色返回普通 Color 双色也能解析`() {
        val single = GrepConsoleSettings.parseColor("#FFE9A8|#FFE9A8")
        assertEquals(Color(0xFFE9A8), single)
        assertNull(GrepConsoleSettings.parseColor("oops"))
        assertNull(GrepConsoleSettings.parseColor(null))
        assertNotNull(GrepConsoleSettings.parseColor("#FFE9A8|#5A4700"))
    }

    @Test
    fun `调色板复用未变化的配置串`() {
        var calls = 0
        val palette = GrepHighlightPalette { calls++; "#112233|#112233" }
        val first = palette.color()
        val second = palette.color()
        assertEquals(Color(0x112233), first)
        assertEquals(first, second)
        assertEquals(2, calls) // provider 每次都会调，但 Color 只解析一次并缓存
    }

    @Test
    fun `改配置后调色板立刻换色`() {
        var spec: String? = "#112233|#112233"
        val palette = GrepHighlightPalette { spec }
        assertEquals(Color(0x112233), palette.color())
        spec = "#445566|#445566"
        assertEquals(Color(0x445566), palette.color())
        spec = null
        assertNull(palette.color())
        spec = "#778899|#778899"
        assertEquals(Color(0x778899), palette.color())
    }

    @Test
    fun `非法颜色串不会让调色板抛异常`() {
        assertNull(GrepHighlightPalette { "bad-color" }.color())
    }

    @Test
    fun `过滤器只给命中行着色`() {
        val filter = GrepMatchHighlightFilter(
            queryProvider = { FilterQuery("timeout") },
            colorSpecProvider = { "#112233|#112233" },
        )
        assertNotNull(filter.applyFilter("WARN timeout again", 18))
        assertNull(filter.applyFilter("WARN all good", 13))
    }

    @Test
    fun `颜色不可用或总开关关闭时不高亮`() {
        val query = { FilterQuery("timeout") }
        assertNull(
            GrepMatchHighlightFilter(queryProvider = query, colorSpecProvider = { null })
                .applyFilter("timeout", 7)
        )
        assertNull(
            GrepMatchHighlightFilter(queryProvider = query, colorSpecProvider = { "bad-color" })
                .applyFilter("timeout", 7)
        )
    }

    @Test
    fun `表达式非法或 query 未就绪时过滤器安全返回 null`() {
        assertNull(
            GrepMatchHighlightFilter(queryProvider = { null }, colorSpecProvider = { "#112233|#112233" })
                .applyFilter("timeout", 7)
        )
        assertNull(
            GrepMatchHighlightFilter(queryProvider = { FilterQuery("/(/") }, colorSpecProvider = { "#112233|#112233" })
                .applyFilter("anything", 8)
        )
    }
}
