package io.github.xbeeant.batiskit.console

import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置要落盘（batiskit-grep-console.xml），data class 规则表必须能被平台的
 * XML 序列化器原样读回，否则用户一改规则重启就丢，或者加载时报错。
 */
class GrepConsoleSettingsTest {

    @Test
    fun `设置与规则表可以序列化再读回`() {
        val state = GrepConsoleSettingsState().apply {
            enabled = false
            colorizeLevel = false
            highlightKeywords = true
            captureEnabled = false
            maxEntries = 512
            filterMode = ConsoleFilterMode.ERRORS_ONLY.name
            filterPattern = "timeout order"
            ruleFilterEnabled = true
            linkNavigation = false
            levelColors[LogLevel.ERROR.name] = "#111111|#EEEEEE"
            rules = mutableListOf(
                HighlightRule(pattern = "my-key", regex = false, caseSensitive = true, color = "#123456"),
                HighlightRule(pattern = "\\bboom\\b", regex = true, bold = false),
                HighlightRule(pattern = ".*noisy line.*", regex = true, italic = true, wholeLine = true),
            )
            filterRules = mutableListOf(
                FilterRule(pattern = ".*DEBUG.*", regex = true, wholeLine = true, action = FilterAction.REMOVE.name),
                FilterRule(pattern = "keep me", action = FilterAction.NONE.name, continueMatching = true),
            )
        }

        val element = XmlSerializer.serialize(state)
        val restored = XmlSerializer.deserialize(element, GrepConsoleSettingsState::class.java)

        assertEquals(false, restored.enabled)
        assertEquals(false, restored.colorizeLevel)
        assertEquals(true, restored.highlightKeywords)
        assertEquals(false, restored.captureEnabled)
        assertEquals(512, restored.maxEntries)
        assertEquals(ConsoleFilterMode.ERRORS_ONLY, restored.mode())
        assertEquals("timeout order", restored.filterPattern)
        assertEquals(true, restored.ruleFilterEnabled)
        assertEquals(false, restored.linkNavigation)
        assertEquals("#111111|#EEEEEE", restored.levelColors[LogLevel.ERROR.name])
        assertEquals("#2E7D32|#81C784", restored.levelColors[LogLevel.INFO.name])
        assertEquals(3, restored.rules.size)
        assertEquals("my-key", restored.rules[0].pattern)
        assertEquals(true, restored.rules[0].caseSensitive)
        assertEquals("\\bboom\\b", restored.rules[1].pattern)
        assertEquals(true, restored.rules[1].regex)
        assertEquals(false, restored.rules[1].bold)
        assertTrue(restored.rules[1].ranges("boom crashed").isNotEmpty())
        // 新增的高亮属性也要能原样读回
        assertEquals(true, restored.rules[2].italic)
        assertEquals(true, restored.rules[2].wholeLine)
        assertFalse(restored.rules[2].ranges("noisy line here").isEmpty())
        // 过滤规则表：动作名读回并归一化
        assertEquals(2, restored.filterRules.size)
        assertEquals(FilterAction.REMOVE, restored.filterRules[0].actionType())
        assertEquals(FilterAction.NONE, restored.filterRules[1].actionType())
        assertEquals(true, restored.filterRules[1].continueMatching)
        // 坏的动作名在 normalize 时回退 REMOVE，不会被序列化器拒之门外
        val bad = GrepConsoleSettingsState().apply { filterRules = mutableListOf(FilterRule(pattern = "x", action = "NOPE")) }
        bad.normalize()
        assertEquals(FilterAction.REMOVE, bad.filterRules[0].actionType())
    }
}
