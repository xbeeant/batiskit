package io.github.xbeeant.batiskit.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 格式化设置的持久化契约与 Options 映射（纯 JVM，不依赖 Application） */
class MapperXmlFormatSettingsTest {

    @Test
    fun `默认值开启格式化 4 空格 不自动保存时格式化`() {
        val service = MapperXmlFormatSettings()
        assertTrue(service.state.enabled)
        assertEquals(4, service.state.indentWidth)
        assertFalse(service.state.formatOnSave)
    }

    @Test
    fun `loadState 就地拷贝字段且保持 state 单例`() {
        val service = MapperXmlFormatSettings()
        val original = service.state
        val loaded = MapperXmlFormatSettingsState().apply {
            enabled = false
            indentWidth = 2
            formatOnSave = true
        }
        service.loadState(loaded)

        assertSame("调用方持有 state 引用，loadState 不能替换实例", original, service.getState())
        assertFalse(service.state.enabled)
        assertEquals(2, service.state.indentWidth)
        assertTrue(service.state.formatOnSave)
    }

    @Test
    fun `loadState 默认值不会被漏拷`() {
        val service = MapperXmlFormatSettings()
        service.loadState(MapperXmlFormatSettingsState())
        assertTrue(service.state.enabled)
        assertEquals(4, service.state.indentWidth)
        assertFalse(service.state.formatOnSave)
    }

    @Test
    fun `options 把缩进宽度交给纯逻辑格式化器 非法值收敛`() {
        val service = MapperXmlFormatSettings()
        service.state.indentWidth = 2
        assertEquals(2, service.options().safeIndent)
        assertEquals(MapperXmlFormatter.INLINE_MAX, service.options().safeInlineMax)

        service.state.indentWidth = 99
        assertEquals(8, service.options().safeIndent)
        service.state.indentWidth = 0
        assertEquals(1, service.options().safeIndent)
    }
}
