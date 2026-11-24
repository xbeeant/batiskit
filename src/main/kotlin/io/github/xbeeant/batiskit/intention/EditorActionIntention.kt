package io.github.xbeeant.batiskit.intention

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * 把一个编辑器右键动作包装成 Alt+Enter intention。
 *
 * 与字符串处理神器那个 intention 一样刻意直接实现 [IntentionAction] 而不是继承
 * `PsiElementBaseIntentionAction`：后者要求光标下能取到 PSI 元素且文件可写，控制台、
 * 纯文本、只读文件里会整条消失；而右键动作本来就只关心「有没有工程 / 编辑器 / 文件」。
 *
 * 另外两个必须关掉的默认行为：
 * 1. [startInWriteAction] 返回 false——平台默认把 intention 的 invoke 包进 write action，
 *    而这里的动作会自己弹对话框 / 起后台任务 / 开 WriteCommandAction，套两层会死锁。
 * 2. [generatePreview] 返回 EMPTY——Alt+Enter 的悬浮预览会在后台 read action 里回调 invoke，
 *    此时不该真的弹窗或改文档。
 *
 * 文案：`getText()` 优先用 isAvailable() 阶段 update() 改写过的 presentation 文案（右键菜单
 * 显示的正是这一份），保证「还原 MyBatis SQL」跟随语言包、「生成时序图（…）」带根方法名时
 * 两处菜单逐字一致，不会各显示各的。
 */
abstract class EditorActionIntention(
    private val actionId: String,
    private val fallbackText: String,
) : IntentionAction, DumbAware {

    /**
     * isAvailable() 跑完右键动作 update() 后缓存的真实文案。Alt+Enter 弹窗总是先对当前
     * 上下文逐条 isAvailable 再渲染列表，所以展示时缓存已是本上下文的值；拿不到时退回
     * templatePresentation / 常量兜底。
     */
    @Volatile
    private var cachedText: String? = null

    override fun getFamilyName(): String = EditorActionIds.FAMILY

    /** 与右键菜单同一份文案（update() 动态改写过的优先，如语言包文案 / 时序图根方法名） */
    override fun getText(): String = cachedText?.takeIf { it.isNotBlank() }
        ?: runCatching {
            ActionManager.getInstance().getAction(actionId)?.templatePresentation?.text
        }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: fallbackText

    override fun isDumbAware(): Boolean = true

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null) return false
        // 模块关掉时右键入口本就隐藏，Alt+Enter 不能绕过设置
        if (!EditorActionIds.moduleEnabled(actionId)) return false
        val action = runCatching { ActionManager.getInstance().getAction(actionId) }.getOrNull()
            ?: return false
        val presentation = Presentation()
        val available = EditorActionBridge.isAvailable(
            action, EditorActionBridge.dataContext(project, editor, file), presentation
        )
        if (available) cachedText = presentation.text
        return available
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val current = editor ?: return
        val action = ActionManager.getInstance().getAction(actionId) ?: return
        // 交给平台自己按组件取完整 DataContext、再跑一次 update 并处理 dumb mode；
        // 这里若手工 new 一个 AnActionEvent，会丢掉 PSI_ELEMENT / 选区 / 导航目标等数据。
        ActionManager.getInstance().tryToExecute(
            action, null, current.contentComponent, ActionPlaces.EDITOR_POPUP, true
        )
    }

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY
}

// 每个类只是「动作 id + 兜底文案」；可用性、文案、执行逻辑全在基类，右键菜单改了这边自动跟齐。

class JsonToPojoIntention :
    EditorActionIntention("BatisKit.JsonToPojo", "JSON → Java 对象（Batis Kit）")

class FormatMapperXmlIntention :
    EditorActionIntention("BatisKit.FormatMapperXml", "格式化 Mapper XML（Batis Kit）")

class RestoreMyBatisSqlIntention :
    EditorActionIntention("BatisKit.RestoreMyBatisSql", "还原 MyBatis SQL")

class ToggleRainbowBracketsIntention :
    EditorActionIntention("BatisKit.ToggleRainbowBrackets", "彩虹括号（Batis Kit）")

class HighlightBracketScopeIntention :
    EditorActionIntention("BatisKit.HighlightBracketScope", "高亮当前作用域（Batis Kit）")

class ClearBracketScopeIntention :
    EditorActionIntention("BatisKit.ClearBracketScope", "清除作用域高亮（Batis Kit）")

class TemplateWorkbenchIntention :
    EditorActionIntention("BatisKit.TemplateWorkbench", "Batis Kit 模板工作台")

class SequenceDiagramIntention :
    EditorActionIntention("BatisKit.SequenceDiagram", "生成时序图（Sequence Diagram）")
