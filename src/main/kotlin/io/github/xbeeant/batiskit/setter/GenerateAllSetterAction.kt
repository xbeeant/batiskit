package io.github.xbeeant.batiskit.setter

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiJavaFile

/**
 * 编辑器右键「生成所有 Setter（默认值）」。
 *
 * 光标放在对象变量 / 字段 / 参数 / `new X()` 上，或选中一段对象表达式即可用；
 * 菜单按当前位置能不能解析出 setter 来决定显示，不在 Java 对象上直接隐藏。
 */
class GenerateAllSetterAction : AnAction() {

    /** BGT：update 里要走 PSI 解析，不能占 EDT */
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && resolve(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val plan = resolve(e) ?: return
        AllSetterGenerator.generate(project, plan)
    }

    /** PSI 读取统一包在 read action 里（BGT 的 action update 不自带读锁） */
    private fun resolve(e: AnActionEvent): SetterPlan? {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return null
        val file = e.getData(CommonDataKeys.PSI_FILE) as? PsiJavaFile ?: return null
        return ReadAction.compute<SetterPlan?, RuntimeException> {
            if (file.isValid) SetterResolver.resolve(editor, file) else null
        }
    }
}
