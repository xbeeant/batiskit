package io.github.xbeeant.batiskit.intention

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * 在真实 PSI / ActionManager 容器里验证 Alt+Enter 桥接层。
 *
 * 重点不是「弹窗长什么样」，而是三件容易漂移的事：
 * 1. 可用性由右键动作自己的 `update()` 决定，且单个动作抛异常不会拖垮整条列表；
 * 2. 三个子菜单 intention 能解析出 plugin.xml 里的 group 并在编辑器上下文可用；
 * 3. Alt+Enter 与右键的上下文过滤一致（Mapper XML 格式化只在 XML 里出现、时序图只在方法里出现），
 *    且文案跟右键的 update() 动态改写同源（语言包文案），不会各显示各的。
 */
class EditorActionIntentionTest : BasePlatformTestCase() {

    fun `test update 探测复用右键动作的可用性判断`() {
        val file = myFixture.configureByText("Plain.java", "class Plain {}")
        val context = EditorActionBridge.dataContext(project, myFixture.editor, file)

        val enabled = object : AnAction("enabled") {
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = e.getData(CommonDataKeys.EDITOR) != null
            }

            override fun actionPerformed(e: AnActionEvent) = Unit
        }
        val disabled = object : AnAction("disabled") {
            override fun update(e: AnActionEvent) {
                e.presentation.isEnabledAndVisible = false
            }

            override fun actionPerformed(e: AnActionEvent) = Unit
        }
        val broken = object : AnAction("broken") {
            override fun update(e: AnActionEvent) {
                error("故意抛错：单个动作不应该拖垮整个 Alt+Enter 列表")
            }

            override fun actionPerformed(e: AnActionEvent) = Unit
        }

        assertTrue(EditorActionBridge.isAvailable(enabled, context))
        assertFalse(EditorActionBridge.isAvailable(disabled, context))
        assertFalse(EditorActionBridge.isAvailable(broken, context))
    }

    fun `test 三个子菜单 intention 在编辑器上下文可用`() {
        val file = myFixture.configureByText("Plain.java", "class Plain {}")
        val editor = myFixture.editor
        listOf(
            StringManipulationGroupIntention(),
            GitLinkGroupIntention(),
            GitToolboxGroupIntention(),
        ).forEach { intention ->
            assertTrue("${intention.text} 应可用", intention.isAvailable(project, editor, file))
            assertTrue("子菜单标题不应为空", intention.text.isNotBlank())
            assertFalse("invoke 会弹 popup，不能被平台包进 write action", intention.startInWriteAction())
        }
    }

    fun `test intention 文案跟随右键动作 update 的动态文案`() {
        val file = myFixture.configureByText("Plain.java", "class Plain {}")
        val editor = myFixture.editor
        val intention = RestoreMyBatisSqlIntention()
        assertTrue(intention.isAvailable(project, editor, file))

        // 右键菜单实际显示的文案：跑一次 action.update() 得到的 presentation.text
        val action = ActionManager.getInstance().getAction("BatisKit.RestoreMyBatisSql")!!
        val presentation = Presentation()
        action.update(
            AnActionEvent.createFromDataContext(
                ActionPlaces.EDITOR_POPUP, presentation,
                EditorActionBridge.dataContext(project, editor, file),
            )
        )
        assertEquals("Alt+Enter 文案必须与右键菜单逐字一致", presentation.text, intention.text)
    }

    fun `test Alt+Enter 与右键的上下文过滤一致`() {
        val file = myFixture.configureByText(
            "Sample.java",
            "class Sample { void run() { int a = 1; } }",
        )
        val editor = myFixture.editor

        // 普通 Java 文件：Mapper XML 格式化不该出现；光标在类上，时序图也不该出现
        editor.caretModel.moveToOffset(0)
        assertFalse(FormatMapperXmlIntention().isAvailable(project, editor, file))
        assertFalse(SequenceDiagramIntention().isAvailable(project, editor, file))
        // 没有 update 覆写的动作按默认可用，与右键菜单一致
        assertTrue(JsonToPojoIntention().isAvailable(project, editor, file))

        // 把光标挪进方法体，时序图动作自己就会判定为可用
        editor.caretModel.moveToOffset(file.text.indexOf("int a"))
        assertTrue(SequenceDiagramIntention().isAvailable(project, editor, file))
    }

    fun `test intention 文案与右键菜单同源且不进 write action`() {
        val intention = JsonToPojoIntention()
        assertEquals(EditorActionIds.FAMILY, intention.familyName)
        assertTrue(intention.text.isNotBlank())
        assertFalse("invoke 会自己弹窗 / 起后台任务，不能被平台包进 write action", intention.startInWriteAction())
        assertTrue(intention.isDumbAware)
    }
}
