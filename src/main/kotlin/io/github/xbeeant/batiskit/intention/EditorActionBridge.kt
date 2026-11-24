package io.github.xbeeant.batiskit.intention

import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile

/**
 * 右键 AnAction 与 Alt+Enter intention 之间的桥。
 *
 * 关键取舍：**不复制一套可用性判断**。intention 的 `isAvailable` 直接调用对应
 * AnAction 的 `update()`，所以「什么时候该出现」「显示什么文字」「模块开关是否关掉」
 * 全部与编辑器右键菜单同源；新增右键动作只要按目录登记，行为自动跟齐，不会出现
 * 右键能用、Alt+Enter 不能用（或反过来）的漂移。
 */
object EditorActionBridge {

    /**
     * 构造探测用的 DataContext。
     *
     * 刻意用 [SimpleDataContext] 显式填值，而不是 `DataManager.getDataContext(component)`：
     * intention 的 `isAvailable` 可能不运行在 EDT，而后者要遍历 Swing 组件树取数据。
     * 右键动作只读 PROJECT / EDITOR / PSI_FILE / VIRTUAL_FILE / CARET 这几个键，
     * 这里全部给到即可；真正执行（[EditorActionIntention.invoke]）时仍走平台按组件
     * 构造的完整 DataContext，PSI_ELEMENT、选区等数据一个不少。
     */
    fun dataContext(project: Project, editor: Editor, file: PsiFile?): DataContext {
        val builder = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, editor)
            .add(CommonDataKeys.CARET, editor.caretModel.currentCaret)
        if (file != null) {
            builder.add(CommonDataKeys.PSI_FILE, file)
            file.virtualFile?.let { builder.add(CommonDataKeys.VIRTUAL_FILE, it) }
        }
        return builder.build()
    }

    /** 造一个与右键同 place 的事件；每次都用新的 Presentation，避免动作之间串状态 */
    fun event(context: DataContext, presentation: Presentation = Presentation()): AnActionEvent =
        AnActionEvent.createFromDataContext(ActionPlaces.EDITOR_POPUP, presentation, context)

    /**
     * 用同一个 AnAction 的 `update()` 判断当前上下文是否可用。
     * 单个动作的 update 抛异常（例如依赖的 Git 服务尚未就绪）不应让整个 Alt+Enter 列表消失，
     * 因此这里逐项兜底：失败就当这一项不可用。
     *
     * [presentation] 由调用方传入时，返回后就是 update() 改写过的真实 Presentation——
     * 右键菜单显示的正是这份文案（如「还原 MyBatis SQL」会跟随语言包、「生成时序图（…）」
     * 会带根方法名），intention 的 getText() 用它保证与右键逐字一致。
     */
    fun isAvailable(
        action: AnAction,
        context: DataContext,
        presentation: Presentation = Presentation(),
    ): Boolean = runCatching {
        action.update(event(context, presentation))
        presentation.isEnabledAndVisible
    }.getOrDefault(false)
}
