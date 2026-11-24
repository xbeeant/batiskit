package io.github.xbeeant.batiskit.settings

import io.github.xbeeant.batiskit.git.GitSettings
import io.github.xbeeant.batiskit.git.GitSettingsState
import io.github.xbeeant.batiskit.log.MyBatisLogSettings
import io.github.xbeeant.batiskit.log.MyBatisLogSettingsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置持久化契约：这两个服务以前只有 `@Service + @State` 却没实现
 * `PersistentStateComponent`，@State 会被平台直接忽略，改过的设置重启 IDE 全回默认。
 * 这里直接 new 服务（不经 Application）验证 getState / loadState 的往返与单例语义。
 */
class SettingsPersistenceTest {

    @Test
    fun `Git 设置 loadState 就地拷贝字段且保持 state 单例`() {
        val service = GitSettings()
        val original = service.state
        val loaded = GitSettingsState().apply {
            inlineBlameEnabled = true
            autoFetchEnabled = false
            autoFetchIntervalMinutes = 42
            commitMessageRegex = "^[A-Z]+-[0-9]+ .+"
            remoteLinkTemplate = "https://example.test/{path}#L{line}"
            branchStaleDays = 7
        }

        service.loadState(loaded)

        assertSame("调用方持有 state 引用，loadState 不能替换实例", original, service.getState())
        assertTrue(service.state.inlineBlameEnabled)
        assertFalse(service.state.autoFetchEnabled)
        assertEquals(42, service.state.autoFetchIntervalMinutes)
        assertEquals("^[A-Z]+-[0-9]+ .+", service.state.commitMessageRegex)
        assertEquals("https://example.test/{path}#L{line}", service.state.remoteLinkTemplate)
        assertEquals(7, service.state.branchStaleDays)
    }

    @Test
    fun `Git 设置默认值不会被 loadState 漏拷`() {
        val service = GitSettings()
        service.loadState(GitSettingsState())
        assertEquals(true, service.state.pendingCheckEnabled)
        assertEquals("master,main,develop", service.state.branchCleanupExcludes)
        assertEquals("", service.state.autoFetchExclusions)
    }

    @Test
    fun `MyBatis Log 设置 loadState 就地拷贝字段且保持 state 单例`() {
        val service = MyBatisLogSettings()
        val original = service.state
        val loaded = MyBatisLogSettingsState().apply {
            captureEnabled = false
            appendSemicolon = true
            normalizeDate = false
            maxEntries = 750
        }

        service.loadState(loaded)

        assertSame(original, service.getState())
        assertFalse(service.state.captureEnabled)
        assertTrue(service.state.appendSemicolon)
        assertFalse(service.state.normalizeDate)
        assertEquals(750, service.state.maxEntries)
        assertEquals(true, service.state.formatSql)
    }

    @Test
    fun `getState 返回的就是可写的同一个 state`() {
        val git = GitSettings()
        git.getState().inlineBlameEnabled = true
        assertTrue(git.state.inlineBlameEnabled)

        val log = MyBatisLogSettings()
        log.getState().maxEntries = 99
        assertEquals(99, log.state.maxEntries)
    }
}
