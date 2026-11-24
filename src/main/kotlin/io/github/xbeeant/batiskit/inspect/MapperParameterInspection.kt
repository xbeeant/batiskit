package io.github.xbeeant.batiskit.inspect

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlText
import io.github.xbeeant.batiskit.navigation.MapperXmlResolver

/**
 * Mapper XML 参数绑定检查：`#{…}` / `${…}` 里的名字必须能在接口方法上绑到东西。
 *
 * MyBatis 这类错误要等启动（甚至只有调到那个分支）才抛 `BindingException`，
 * 而 XML 与接口的对应关系是静态的，完全可以在编辑器里提前报出来。
 *
 * 判定口径见 [ParamBinding] —— 保守优先，单参数 POJO 的属性名不去猜，避免满屏误报。
 */
class MapperParameterInspection : LocalInspectionTool(), DumbAware {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) {
            // 索引没建好时解析不到接口类，直接不检查，避免把整片 XML 报红
            return PsiElementVisitor.EMPTY_VISITOR
        }
        return object : XmlElementVisitor() {
            override fun visitXmlText(text: XmlText) {
                inspect(text, holder)
            }
        }
    }

    private fun inspect(text: XmlText, holder: ProblemsHolder) {
        val statement = MapperXmlResolver.statementOf(text) ?: return
        val method = MapperXmlResolver.method(statement) ?: return
        val context = ParamBinding.context(paramsOf(method), MapperXmlResolver.eachVarsOf(text, statement))
        if (!context.checkable) {
            return
        }
        ParamBinding.expressions(text.text).forEach { expression ->
            val problem = ParamBinding.validate(expression, context) ?: return@forEach
            register(holder, text, expression, problem, method)
        }
    }

    private fun register(
        holder: ProblemsHolder,
        text: XmlText,
        expression: ParamBinding.Expression,
        problem: ParamBinding.Problem,
        method: PsiMethod
    ) {
        val manager = SmartPointerManager.getInstance(text.project)
        val fixes = mutableListOf<LocalQuickFix>()
        problem.renameTo?.let { newName ->
            fixes += RenameParamFix(
                manager.createSmartPsiElementPointer(text),
                TextRange(expression.rootStart, expression.rootEnd),
                expression.root,
                newName
            )
        }
        val index = problem.addParamIndex
        val value = problem.addParamValue
        if (index != null && value != null) {
            method.parameterList.parameters.getOrNull(index)?.let { parameter ->
                fixes += AddParamQuickFix(
                    manager.createSmartPsiElementPointer(parameter),
                    parameter.name.orEmpty(),
                    value
                )
            }
        }
        holder.registerProblem(
            text,
            TextRange(expression.start, expression.end),
            problem.message,
            *fixes.toTypedArray()
        )
    }

    // ------------------------------------------------------------------ PSI 辅助

    private fun paramsOf(method: PsiMethod): List<MapperParam> =
        method.parameterList.parameters.map { MapperParam(it.name.orEmpty(), ParamAnnotations.of(it)) }

    /**
     * Quickfix 1：把表达式改成接口上真实存在的名字。
     *
     * 按绝对偏移做文本替换（保留原格式与实体转义），不重建 PSI。
     * 注意：高亮可能在文件副本上跑（测试/预览环境），所以写回时按 VirtualFile 重新取真实文件与文档，
     * 并且只有在偏移处的内容仍是原来的名字时才改，避免文档已变更时改错位置。
     */
    private class RenameParamFix(
        private val target: SmartPsiElementPointer<XmlText>,
        private val range: TextRange,
        private val oldName: String,
        private val newName: String
    ) : LocalQuickFix {

        override fun getFamilyName(): String = "修正 MyBatis 参数名"

        override fun getName(): String = "把参数名改成 \"$newName\""

        /** 自己开写命令，保证可撤销（改文本与提交文档要在同一个命令里） */
        override fun startInWriteAction(): Boolean = false

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val captured = target.element ?: return
            // 走平台推荐的写法：预览模式下写到副本，正常模式下先确保文件可写（只读文件/版本库只读）
            if (!IntentionPreviewUtils.prepareElementForWrite(captured)) {
                return
            }
            IntentionPreviewUtils.write<RuntimeException> {
                val source = captured.text
                val start = range.startOffset
                val end = range.endOffset
                if (start < 0 || end > source.length || source.substring(start, end) != oldName) {
                    // 文本已经变了，放弃这次修复（重新 Alt+Enter 即可）
                    return@write
                }
                val updated = source.substring(0, start) + newName + source.substring(end)
                val replacement = textElement(project, updated) ?: return@write
                captured.replace(replacement)
            }
        }

        /** 用只含一段文本的临时 mapper 解析出等价的 XmlText，替换原节点（保留 PSI 事务，不裸改文档） */
        private fun textElement(project: Project, text: String): XmlText? {
            val wrapper = PsiFileFactory.getInstance(project)
                .createFileFromText("mybatis-param-fix.xml", XmlFileType.INSTANCE, "<mapper>$text</mapper>") as? XmlFile
            return wrapper?.rootTag?.value?.textElements?.firstOrNull()
        }
    }

    /**
     * Quickfix 2：给接口参数补 `@Param("…")`。
     *
     * 适用于「XML 里的名字是对的、Java 侧少写了注解」的情况——比如多个参数没加 `@Param`，
     * 编译又没保留参数名时，运行时同样会绑不上。
     */
    private class AddParamQuickFix(
        private val target: SmartPsiElementPointer<PsiParameter>,
        private val parameterName: String,
        private val value: String
    ) : LocalQuickFix {

        override fun getFamilyName(): String = "补 @Param"

        override fun getName(): String = "给参数 \"$parameterName\" 添加 @Param(\"$value\")"

        override fun startInWriteAction(): Boolean = false

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val parameter = target.element ?: return
            if (!IntentionPreviewUtils.prepareElementForWrite(parameter)) {
                return
            }
            IntentionPreviewUtils.write<RuntimeException> {
                ParamAnnotations.add(project, parameter, value)
            }
        }
    }
}
