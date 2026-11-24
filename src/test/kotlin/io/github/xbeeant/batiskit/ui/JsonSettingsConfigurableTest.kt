package io.github.xbeeant.batiskit.ui

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xbeeant.batiskit.json.ui.JsonSettingsConfigurable
import io.github.xbeeant.batiskit.ui.components.FixedHeightRow
import java.awt.Component
import java.awt.Container

/**
 * 真实平台环境里创建「JSON → Java 对象」设置页。
 *
 * 回归：曾因给 JViewport 设 border 抛 IllegalArgumentException，
 * createComponent() 失败导致页面全空白，而纯 Swing 布局测试覆盖不到。
 */
class JsonSettingsConfigurableTest : BasePlatformTestCase() {

    fun testCreateComponentHasVisibleRows() {
        val configurable = JsonSettingsConfigurable()
        try {
            val component = configurable.createComponent()
            assertNotNull("createComponent 必须返回可用组件", component)
            val rows = mutableListOf<FixedHeightRow>()
            collect(component, rows)
            assertTrue("设置页至少应有 8 行表单，实际 ${rows.size}", rows.size >= 8)
            assertTrue("设置页宽度应可用", component.preferredSize.width > 100)
        } finally {
            configurable.disposeUIResources()
        }
    }

    private fun collect(component: Component, rows: MutableList<FixedHeightRow>) {
        if (component is FixedHeightRow) rows.add(component)
        if (component is Container) component.components.forEach { collect(it, rows) }
    }
}
