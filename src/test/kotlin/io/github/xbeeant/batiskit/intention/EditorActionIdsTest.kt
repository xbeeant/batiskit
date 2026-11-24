package io.github.xbeeant.batiskit.intention

import io.github.xbeeant.batiskit.settings.ModuleToggles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Alt+Enter 入口目录的纯 JVM 回归：插件描述（plugin.xml）与 Kotlin 目录必须双向对齐。
 *
 * 右键父菜单是唯一入口来源，这里守住三条约束：
 * 1. 目录里的 id 不重复、不凭空多出 plugin.xml 里没有的动作；
 * 2. 父菜单的每个引用都有 Alt+Enter 覆盖（直接 intention / 已有专属 intention / 子菜单 intention）；
 * 3. 每个被包装的动作都能映射到某个模块开关，关闭模块时入口才会一起隐藏。
 */
class EditorActionIdsTest {

    private val pluginXml: String by lazy { File("src/main/resources/META-INF/plugin.xml").readText() }

    @Test
    fun `直接暴露的 action id 唯一且都来自 RootGroup`() {
        assertEquals(EditorActionIds.DIRECT.size, EditorActionIds.DIRECT.toSet().size)
        assertTrue(EditorActionIds.ROOT_REFERENCES.containsAll(EditorActionIds.DIRECT))
        assertTrue(EditorActionIds.ROOT_REFERENCES.containsAll(EditorActionIds.DEDICATED))
        assertTrue(EditorActionIds.ROOT_REFERENCES.containsAll(EditorActionIds.SUBMENUS))
    }

    @Test
    fun `三类覆盖互斥且不多不少覆盖 RootGroup 的全部引用`() {
        val covered = EditorActionIds.DIRECT + EditorActionIds.DEDICATED + EditorActionIds.SUBMENUS
        assertEquals("有 action 被重复归类", covered.size, covered.toSet().size)
        assertEquals(covered.size, EditorActionIds.ROOT_REFERENCES.size)
        assertEquals(
            EditorActionIds.ROOT_REFERENCES.toSet(),
            covered.toSet(),
        )
        EditorActionIds.ROOT_REFERENCES.forEach { id ->
            assertTrue("$id 没有被 Alt+Enter 覆盖", EditorActionIds.isCovered(id))
        }
    }

    @Test
    fun `已有专属 intention 的动作不再包一层 避免 Alt+Enter 出现重复项`() {
        assertTrue("BatisKit.GenerateAllSetter" in EditorActionIds.DEDICATED)
        assertTrue("BatisKit.StringManipulationChooser" in EditorActionIds.DEDICATED)
        assertFalse("BatisKit.GenerateAllSetter" in EditorActionIds.DIRECT)
        assertFalse("BatisKit.StringManipulationChooser" in EditorActionIds.DIRECT)
    }

    @Test
    fun `被包装的动作都能映射到模块开关`() {
        (EditorActionIds.DIRECT + EditorActionIds.DEDICATED).forEach { id ->
            val module = ModuleToggles.moduleOfActionId(id)
            assertTrue("$id 没有归属模块", module != null && module in ModuleToggles.MODULES)
        }
    }

    @Test
    fun `插件描述里的 RootGroup 引用与目录逐字一致`() {
        val block = pluginXml
            .substringAfter("<group id=\"BatisKit.RootGroup\"")
            .substringBefore("</group>")
        val refs = Regex("<reference ref=\"([^\"]+)\"")
            .findAll(block)
            .map { it.groupValues[1] }
            .toList()
        assertEquals(EditorActionIds.ROOT_REFERENCES, refs)
    }

    @Test
    fun `插件描述注册了全部新 intention 且分类为 Batis Kit`() {
        val classes = EditorActionIds.DIRECT_INTENTION_CLASSES + EditorActionIds.SUBMENU_INTENTION_CLASSES
        assertEquals(
            EditorActionIds.DIRECT.size + EditorActionIds.SUBMENU_INTENTION_CLASSES.size,
            classes.size,
        )
        classes.forEach { simple ->
            val fqn = "io.github.xbeeant.batiskit.intention.$simple"
            assertTrue("$fqn 未注册", pluginXml.contains("<className>$fqn</className>"))
            assertTrue(
                "$fqn 的 category 不是 Batis Kit",
                pluginXml.substringAfter("<className>$fqn</className>")
                    .substringBefore("</intentionAction>")
                    .contains("<category>Batis Kit</category>"),
            )
        }
    }
}
