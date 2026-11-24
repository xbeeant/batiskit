package io.github.xbeeant.batiskit.refactor

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import io.github.xbeeant.batiskit.navigation.isMapperInterface

/**
 * Alt+Enter：给绑定了 XML 的 Mapper 接口补 `@Mapper` 注解（含 import 整理）。
 *
 * 对标 rockjava/intellij-mybatis-plugin 的「auto register mapper as spring bean」——
 * 在注解驱动的工程里，mapper 接口的 Spring 注册靠 `@Mapper`（或 `@MapperScan` 扫描），
 * 漏写注解要到启动时才报 `NoSuchBeanDefinitionException`。这里只处理单接口级别的事：
 * 接口没有任何 `@Mapper` 注解时提供修复；`@MapperScan` 扫描包的场景无法静态判断，
 * 交由用户选择是否使用。
 */
class AddMapperAnnotationIntention : PsiElementBaseIntentionAction() {

    override fun getFamilyName(): String = "MyBatis"

    override fun getText(): String = "为 Mapper 接口添加 @Mapper 注解"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val clazz = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false) ?: return false
        return isMapperInterface(clazz) && !hasMapperAnnotation(clazz)
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val clazz = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false) ?: return
        if (hasMapperAnnotation(clazz)) {
            return
        }
        WriteCommandAction.runWriteCommandAction(project) {
            // addAnnotation 走平台的注解插入：已 import 时用简名，冲突时用全限定名，import 自动整理
            clazz.modifierList?.addAnnotation(MAPPER_ANNOTATION)
        }
    }

    /**
     * 预览展示「补了 @Mapper 之后的接口声明」：与 invoke 一样只动类级注解，用
     * PsiFileFactory 造临时 Java 文件解析后展示改动后的声明片段（不含方法体）。
     *
     * invoke 的 WriteCommandAction / addAnnotation 不能在后台预览上下文执行（会死锁），
     * 这里只读 PSI、纯文本构造，不回写物理文件。
     */
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        val element = file.findElementAt(editor.caretModel.offset) ?: return IntentionPreviewInfo.EMPTY
        val clazz = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
            ?: return IntentionPreviewInfo.EMPTY
        if (hasMapperAnnotation(clazz)) return IntentionPreviewInfo.EMPTY
        // addAnnotation 把 @Mapper 插到修饰符列表最前；这里按同样口径重建接口声明
        val header = clazz.text.substringBefore('{').trim()
        val snippet = "@Mapper\n$header"
        val temp = PsiFileFactory.getInstance(project)
            .createFileFromText("Preview.java", JavaFileType.INSTANCE, snippet)
        return IntentionPreviewInfo.CustomDiff(JavaFileType.INSTANCE, file.name, "", temp.text)
    }

    companion object {
        const val MAPPER_ANNOTATION = "org.apache.ibatis.annotations.Mapper"

        fun hasMapperAnnotation(clazz: PsiClass): Boolean =
            clazz.annotations.any { it.qualifiedName == MAPPER_ANNOTATION }
    }
}
