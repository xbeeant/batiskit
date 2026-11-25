package io.github.xbeeant.batiskit.rainbow

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiDocumentManager

/**
 * 高亮光标所在的括号作用域（对标 Rainbow Brackets 的 Scope Highlighting）。
 *
 * 交互上做了一个取舍：参考项目用 Ctrl/Alt + 右键触发、Esc 清除，但全局拦截 Esc 会抢 IDE
 * 「关补全 / 取消选择」的核心行为。这里改成**重复触发向外扩展一层**，到最外层后再触发即清除，
 * 另配一个右键「清除作用域高亮」随时直接清掉，不占用任何 IDE 已有快捷键。
 *
 * 作用域由纯逻辑 [BracePairs] 从整份文件的括号配对算出，因此光标在括号上或括号内部任意位置
 * 都能命中；字符串 / 注释里的括号经 [BraceScanner] 的 PSI 叶子过滤天然被排除。
 */
class HighlightBracketScopeAction : AnAction(
    "高亮当前作用域（Batis Kit）",
    "高亮光标所在括号对覆盖的范围；重复触发向外扩展一层，到最外层后再触发清除",
    null
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null &&
            event.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        if (file.textLength > RainbowSettings.maxFileLength()) {
            ScopeHighlightTracker.clear(editor)
            HintManager.getInstance().showInformationHint(editor, "文件超过大小上限，已跳过作用域高亮")
            return
        }

        val caret = editor.caretModel.offset
        val analysis = ReadAction.compute<BraceLevels.Analysis, RuntimeException> {
            BraceAnalysisCache.analysis(file, RainbowSettings.colorCount())
        }
        val all = BracePairs.enclosingAll(analysis, caret)
        if (all.isEmpty()) {
            ScopeHighlightTracker.clear(editor)
            HintManager.getInstance().showInformationHint(editor, "光标不在任何已配对的括号作用域里")
            return
        }

        val previous = ScopeHighlightTracker.session(editor)
        val level = if (previous != null && previous.caret == caret) previous.level + 1 else 0
        if (level >= all.size) {
            ScopeHighlightTracker.clear(editor)
            return
        }
        ScopeHighlightTracker.show(editor, all[level], level, caret)
    }
}

/** 直接清掉临时作用域高亮；没有高亮时菜单项置灰。 */
class ClearBracketScopeAction : AnAction(
    "清除作用域高亮（Batis Kit）",
    "清除「高亮当前作用域」加上的临时背景标记",
    null
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val editor = event.getData(CommonDataKeys.EDITOR)
        event.presentation.isEnabled = editor != null && ScopeHighlightTracker.session(editor) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        event.getData(CommonDataKeys.EDITOR)?.let { ScopeHighlightTracker.clear(it) }
    }
}
