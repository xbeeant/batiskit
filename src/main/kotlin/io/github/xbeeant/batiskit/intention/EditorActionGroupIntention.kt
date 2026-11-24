package io.github.xbeeant.batiskit.intention

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiFile
import io.github.xbeeant.batiskit.intention.EditorActionBridge
import io.github.xbeeant.batiskit.intention.EditorActionIds
import io.github.xbeeant.batiskit.intention.EditorActionIds.FAMILY

/**
 * 把右键父菜单（RootGroup）里的一个**子菜单**（ActionGroup）包装成 Alt+Enter intention。
 *
 * Alt+Enter 的意图列表不支持真正的子菜单缩进，要让它与编辑器右键保持一致，只能让子菜单
 * 自己也占一条：选中后弹出与右键**完全同一份** group 的 popup（带速度搜索）。这样
 * 「String Manipulation / 远程仓库链接 / Git Toolbox」三个子菜单在两处菜单里同名、同图标、
 * 同可用条件；以后往子菜单里加动作，Alt+Enter 自动跟上，不需要再写类。
 *
 * 与 [EditorActionIntention] 相同的取舍：直接实现 [IntentionAction]（不依赖光标下有 PSI
 * 元素）、`startInWriteAction=false`（invoke 要弹 UI）、`generatePreview=EMPTY`（预览阶段
 * 不真的弹窗）。文案同样取 update() 后的 presentation，与右键逐字一致。
 */
abstract class EditorActionGroupIntention(
    private val groupId: String,
    private val fallbackText: String,
) : IntentionAction, DumbAware {

    @Volatile
    private var cachedText: String? = null

    override fun getFamilyName(): String = FAMILY

    /** 与右键子菜单同一份标题；插件未加载（headless 索引）时退回常量 */
    override fun getText(): String = cachedText?.takeIf { it.isNotBlank() }
        ?: runCatching {
            ActionManager.getInstance().getAction(groupId)?.templatePresentation?.text
        }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: fallbackText

    override fun isDumbAware(): Boolean = true

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null) return false
        // 模块关掉时右键子菜单本就隐藏（父菜单 getChildren 会裁掉），Alt+Enter 不能绕过设置
        if (!EditorActionIds.moduleEnabled(groupId)) return false
        val group = runCatching { ActionManager.getInstance().getAction(groupId) }
            .getOrNull() as? ActionGroup ?: return false
        val presentation = Presentation()
        val available = runCatching {
            group.update(EditorActionBridge.event(EditorActionBridge.dataContext(project, editor, file), presentation))
            presentation.isEnabledAndVisible
        }.getOrDefault(false)
        if (available) cachedText = presentation.text
        return available
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val current = editor ?: return
        val group = ActionManager.getInstance().getAction(groupId) as? ActionGroup ?: return
        // 执行阶段用编辑器组件上的完整 DataContext：PSI_ELEMENT、选区、导航目标等
        // 由平台提供，子菜单里的 Git / 字符串动作才能拿到和右键一样的数据。
        val context = DataManager.getInstance().getDataContext(current.contentComponent)
        val popup = JBPopupFactory.getInstance().createActionGroupPopup(
            text,
            group,
            context,
            JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
            false,
        )
        popup.showInBestPositionFor(current)
    }

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY
}

/** 右键的「String Manipulation（字符串处理神器）」子菜单，弹出同一份转换列表 */
class StringManipulationGroupIntention :
    EditorActionGroupIntention("BatisKit.StringManipulation", "String Manipulation（字符串处理神器）")

/** 右键的「远程仓库链接（Batis Kit）」子菜单 */
class GitLinkGroupIntention :
    EditorActionGroupIntention("BatisKit.GitLinkGroup", "远程仓库链接（Batis Kit）")

/** 右键的「Git Toolbox（Batis Kit）」子菜单 */
class GitToolboxGroupIntention :
    EditorActionGroupIntention("BatisKit.GitToolboxGroup", "Git Toolbox（Batis Kit）")
