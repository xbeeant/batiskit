package io.github.xbeeant.batiskit.setter

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile

/**
 * Alt+Enter 版本的「生成所有 Setter（默认值）」，与右键动作共用同一套解析 + 写入逻辑。
 */
class GenerateAllSetterIntention : PsiElementBaseIntentionAction() {

    override fun getFamilyName(): String = "Generate All Setter"

    override fun getText(): String = "为对象生成所有 Setter（默认值）"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val file = element.containingFile as? PsiJavaFile ?: return false
        return editor != null && SetterResolver.resolve(editor, file) != null
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        if (editor == null) {
            return
        }
        val file = element.containingFile as? PsiJavaFile ?: return
        val plan = SetterResolver.resolve(editor, file) ?: return
        AllSetterGenerator.generate(project, plan)
    }

    /** 预览会在后台 read action 里回调 invoke，写动作会死锁；与其它 intention 保持一致 */
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY
}
