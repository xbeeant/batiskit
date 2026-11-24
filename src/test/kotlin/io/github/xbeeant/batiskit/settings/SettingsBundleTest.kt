package io.github.xbeeant.batiskit.settings

import io.github.xbeeant.batiskit.console.GrepConsoleSettings
import io.github.xbeeant.batiskit.console.GrepConsoleSettingsState
import io.github.xbeeant.batiskit.format.MapperXmlFormatSettings
import io.github.xbeeant.batiskit.format.MapperXmlFormatSettingsState
import io.github.xbeeant.batiskit.git.GitSettings
import io.github.xbeeant.batiskit.git.GitSettingsState
import io.github.xbeeant.batiskit.log.MyBatisLogSettings
import io.github.xbeeant.batiskit.log.MyBatisLogSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 直接 new 服务（不经 Application）验证设置导入 / 导出 / 重置的编解码 */
class SettingsBundleTest {

    private class Fixture {
        val git = GitSettings()
        val log = MyBatisLogSettings()
        val format = MapperXmlFormatSettings()
        val grep = GrepConsoleSettings()
        val sections = listOf(
            SettingsSection("git", "Git Toolbox", GitSettingsState::class.java, git),
            SettingsSection("mybatis-log", "MyBatis Log", MyBatisLogSettingsState::class.java, log),
            SettingsSection(
                "mapper-xml-format", "Mapper XML 格式化",
                MapperXmlFormatSettingsState::class.java, format
            ),
            SettingsSection("grep-console", "Grep Console", GrepConsoleSettingsState::class.java, grep),
        )
    }

    @Test
    fun `导出的 XML 带版本与全部分组`() {
        val fixture = Fixture()
        val xml = SettingsBundle.encode(fixture.sections)
        assertTrue(xml.contains("<batiskit-settings"))
        assertTrue(xml.contains("version=\"1\""))
        fixture.sections.forEach { assertTrue(xml.contains("key=\"${it.key}\"")) }
        assertTrue(xml.contains("<state"))
    }

    @Test
    fun `导出再导入还原各组字段`() {
        val fixture = Fixture()
        fixture.git.state.autoFetchEnabled = false
        fixture.git.state.autoFetchIntervalMinutes = 42
        fixture.log.state.maxEntries = 777
        fixture.format.state.indentWidth = 2
        fixture.format.state.formatOnSave = true
        fixture.grep.config.maxEntries = 9000
        fixture.grep.config.filterPattern = "timeout"
        val xml = SettingsBundle.encode(fixture.sections)

        fixture.git.state.autoFetchEnabled = true
        fixture.git.state.autoFetchIntervalMinutes = 10
        fixture.log.state.maxEntries = 200
        fixture.format.state.indentWidth = 4
        fixture.format.state.formatOnSave = false
        fixture.grep.config.maxEntries = 2000
        fixture.grep.config.filterPattern = ""

        val report = SettingsBundle.decode(xml, fixture.sections)
        assertTrue(report.summary(), report.isSuccess)
        assertEquals(4, report.applied.size)
        assertEquals(false, fixture.git.state.autoFetchEnabled)
        assertEquals(42, fixture.git.state.autoFetchIntervalMinutes)
        assertEquals(777, fixture.log.state.maxEntries)
        assertEquals(2, fixture.format.state.indentWidth)
        assertEquals(true, fixture.format.state.formatOnSave)
        assertEquals(9000, fixture.grep.config.maxEntries)
        assertEquals("timeout", fixture.grep.config.filterPattern)
    }

    @Test
    fun `导入保持 state 单例引用不被替换`() {
        val fixture = Fixture()
        val originalState = fixture.git.state
        fixture.git.state.autoFetchEnabled = false
        val xml = SettingsBundle.encode(fixture.sections)
        fixture.git.state.autoFetchEnabled = true
        SettingsBundle.decode(xml, fixture.sections)
        assertTrue(originalState === fixture.git.state)
        assertEquals(false, fixture.git.state.autoFetchEnabled)
    }

    @Test
    fun `未知分组与缺 state 的节被跳过`() {
        val fixture = Fixture()
        val xml = """
            <batiskit-settings version="1">
              <section key="unknown" type="X"><state/></section>
              <section key="git"><state/></section>
              <section key=" "><state/></section>
            </batiskit-settings>
        """.trimIndent()
        val report = SettingsBundle.decode(xml, fixture.sections)
        // 有一组能导入就算部分成功，跳过项单独列在摘要里
        assertTrue(report.isSuccess)
        assertEquals(1, report.applied.size)
        assertEquals(2, report.skipped.size)
        assertTrue(report.summary().contains("跳过 2 组"))
    }

    @Test
    fun `空文件与坏 XML 给出可读错误`() {
        val fixture = Fixture()
        assertTrue(SettingsBundle.decode("  ", fixture.sections).error!!.contains("文件为空"))
        assertTrue(SettingsBundle.decode("<broken>", fixture.sections).error!!.contains("XML 解析失败"))
        assertTrue(
            SettingsBundle.decode("<other-root/>", fixture.sections).error!!.contains("不是 Batis Kit 设置文件")
        )
    }

    @Test
    fun `DOCTYPE 与外部实体被拒绝`() {
        val fixture = Fixture()
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <batiskit-settings version="1">
              <section key="git"><state>&xxe;</state></section>
            </batiskit-settings>
        """.trimIndent()
        assertFalse(SettingsBundle.decode(xml, fixture.sections).isSuccess)
    }

    @Test
    fun `含换行的文本字段导出再导入不丢换行`() {
        val fixture = Fixture()
        fixture.git.state.autoFetchExclusions = "repoA\nrepoB\n"
        val xml = SettingsBundle.encode(fixture.sections)
        fixture.git.state.autoFetchExclusions = ""
        val report = SettingsBundle.decode(xml, fixture.sections)
        assertTrue(report.summary(), report.isSuccess)
        assertEquals("repoA\nrepoB\n", fixture.git.state.autoFetchExclusions)
    }

    @Test
    fun `恢复默认把各组写回代码默认值`() {
        val fixture = Fixture()
        fixture.git.state.autoFetchEnabled = false
        fixture.log.state.maxEntries = 1
        fixture.format.state.indentWidth = 8
        fixture.grep.config.maxEntries = 300
        assertEquals(4, SettingsBundle.reset(fixture.sections))
        assertEquals(true, fixture.git.state.autoFetchEnabled)
        assertEquals(200, fixture.log.state.maxEntries)
        assertEquals(4, fixture.format.state.indentWidth)
        assertEquals(2000, fixture.grep.config.maxEntries)
    }
}
