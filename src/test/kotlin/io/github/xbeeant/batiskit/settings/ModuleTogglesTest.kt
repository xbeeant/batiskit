package io.github.xbeeant.batiskit.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ModuleToggles] 的纯 JVM 测试：只碰 companion 里的纯函数 [ModuleToggles.moduleOfActionId]
 * 与直接 new 出来的实例（getState / loadState / setEnabled / 实例 isEnabled）。
 * 不调 companion 的 getInstance() / isEnabled()——那两个会触发 Application 初始化。
 */
class ModuleTogglesTest {

    @Test
    fun `GenerateAllSetter 归 all-setter 而不是 generator`() {
        // 名字里同时含 "Generate"，但 SETTER 分支必须排在 GENERATOR 前面
        assertEquals(ModuleToggles.SETTER, ModuleToggles.moduleOfActionId("GenerateAllSetter"))
        assertEquals(ModuleToggles.SETTER, ModuleToggles.moduleOfActionId("BatisKit.GenerateAllSetterAction"))
    }

    @Test
    fun `FormatMapperXml 归 mapper`() {
        assertEquals(ModuleToggles.MAPPER, ModuleToggles.moduleOfActionId("FormatMapperXml"))
        assertEquals(ModuleToggles.MAPPER, ModuleToggles.moduleOfActionId("BatisKit.FormatMapperXmlAction"))
    }

    @Test
    fun `GitLinkGroup 归 git`() {
        assertEquals(ModuleToggles.GIT, ModuleToggles.moduleOfActionId("GitLinkGroup"))
    }

    @Test
    fun `RootGroup 与未知入口返回 null`() {
        assertNull(ModuleToggles.moduleOfActionId("RootGroup"))
        assertNull(ModuleToggles.moduleOfActionId("BatisKit.RootGroup"))
        assertNull(ModuleToggles.moduleOfActionId(""))
        assertNull(ModuleToggles.moduleOfActionId("SomeUnknownAction"))
    }

    @Test
    fun `其余模块的 actionId 映射`() {
        assertEquals(ModuleToggles.RAINBOW, ModuleToggles.moduleOfActionId("ToggleRainbowBrackets"))
        assertEquals(ModuleToggles.GREP, ModuleToggles.moduleOfActionId("OpenGrepTabAction"))
        assertEquals(ModuleToggles.LOG, ModuleToggles.moduleOfActionId("RestoreMyBatisSql"))
        assertEquals(ModuleToggles.STRING, ModuleToggles.moduleOfActionId("StringManipulation"))
        assertEquals(ModuleToggles.SEQUENCE, ModuleToggles.moduleOfActionId("SequenceDiagram"))
        assertEquals(ModuleToggles.JSON, ModuleToggles.moduleOfActionId("JsonToPojo"))
        assertEquals(ModuleToggles.GENERATOR, ModuleToggles.moduleOfActionId("CompareTable"))
    }

    @Test
    fun `默认全开且未知模块按开启处理`() {
        val toggles = ModuleToggles()
        ModuleToggles.MODULES.forEach { module ->
            assertTrue("$module 应默认开启", toggles.isEnabled(module))
        }
        assertTrue(toggles.isEnabled("unknown-module"))
    }

    @Test
    fun `setEnabled 只影响指定模块`() {
        val toggles = ModuleToggles()
        toggles.setEnabled(ModuleToggles.LOG, false)
        assertFalse(toggles.isEnabled(ModuleToggles.LOG))
        assertTrue(toggles.isEnabled(ModuleToggles.GIT))
        toggles.setEnabled(ModuleToggles.LOG, true)
        assertTrue(toggles.isEnabled(ModuleToggles.LOG))
    }

    @Test
    fun `loadState 补齐缺失模块 保留显式关闭 丢弃未知键`() {
        val toggles = ModuleToggles()
        toggles.loadState(
            ModuleToggles.State(
                linkedMapOf(
                    ModuleToggles.GREP to false,
                    "obsolete-module" to false,
                )
            )
        )
        val state = toggles.getState().enabled
        assertEquals(ModuleToggles.MODULES.toSet(), state.keys)
        assertFalse(state[ModuleToggles.GREP]!!)
        assertTrue("配置里缺的键按开启补齐", state[ModuleToggles.GENERATOR]!!)
        assertFalse("未知键不应被带进状态", state.containsKey("obsolete-module"))
    }

    @Test
    fun `loadState 后 isEnabled 与规范化结果一致`() {
        val toggles = ModuleToggles()
        toggles.loadState(
            ModuleToggles.State(
                linkedMapOf(
                    ModuleToggles.MAPPER to false,
                    ModuleToggles.JSON to true,
                )
            )
        )
        assertFalse(toggles.isEnabled(ModuleToggles.MAPPER))
        assertTrue(toggles.isEnabled(ModuleToggles.JSON))
        assertTrue(toggles.isEnabled(ModuleToggles.SETTER))
        assertTrue(toggles.isEnabled("unknown-module"))
    }
}
