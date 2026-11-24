package io.github.xbeeant.batiskit.inspect

import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.XmlElementVisitor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import io.github.xbeeant.batiskit.navigation.MapperXmlResolver

/**
 * Mapper XML 的 `${…}` 使用检查：字符串替换是 SQL 注入的主要来源。
 *
 * `${…}` 不走预编译参数，值会原样拼进 SQL 文本；外部可控的输入一旦走到这里
 * 就是注入点。静态判断不了「值是否来自用户输入」，所以统一报 WARNING，
 * 由使用者确认：只是传值的改用 `#{}`（提供 quickfix），
 * order by / 列名拼接这类必须动态拼 SQL 的场景自行忽略或加白名单校验。
 *
 * 只看语句标签与 `<sql>` 片段里的文本——`<resultMap>` / `<if test>` 属性里的
 * OGNL 不是 SQL，不在本检查范围内。
 */
class MapperDollarInspection : LocalInspectionTool(), DumbAware {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) {
            return PsiElementVisitor.EMPTY_VISITOR
        }
        return object : XmlElementVisitor() {
            override fun visitXmlText(text: XmlText) {
                inspect(text, holder)
            }
        }
    }

    private fun inspect(text: XmlText, holder: ProblemsHolder) {
        val file = text.containingFile as? XmlFile ?: return
        val root = file.rootTag ?: return
        if (root.name != "mapper") {
            return
        }
        if (!insideSqlContext(text, root)) {
            return
        }
        val manager = SmartPointerManager.getInstance(text.project)
        DollarSubstitution.occurrences(text.text).forEach { occurrence ->
            holder.registerProblem(
                text,
                TextRange(occurrence.start, occurrence.end),
                "「${occurrence.text}」是字符串替换，值直接拼进 SQL，存在 SQL 注入风险；只是传值时请改用 #{}"
                    + "（order by / 列名等无法用 #{} 的场景请自行校验取值）",
                DollarToHashFix(manager.createSmartPsiElementPointer(text), occurrence)
            )
        }
    }

    /** 文本是否落在语句标签或 `<sql>` 片段里 */
    private fun insideSqlContext(text: XmlText, root: XmlTag): Boolean {
        var tag = PsiTreeUtil.getParentOfType(text, XmlTag::class.java) ?: return false
        while (true) {
            if (MapperXmlResolver.isStatementTag(tag) || tag.name == "sql") {
                return true
            }
            if (tag === root || tag.name == "mapper") {
                return false
            }
            tag = PsiTreeUtil.getParentOfType(tag, XmlTag::class.java) ?: return false
        }
    }

    /**
     * Quickfix：把 `${x}` 改成 `#{x}`。
     * 按绝对偏移替换整段文本（保留原格式与实体转义），文档已变更时放弃修复（重新 Alt+Enter 即可）。
     */
    private class DollarToHashFix(
        private val target: SmartPsiElementPointer<XmlText>,
        private val occurrence: DollarSubstitution.Occurrence
    ) : LocalQuickFix {

        override fun getFamilyName(): String = "把 \${…} 改成 #{…}"

        override fun getName(): String = "改成 ${DollarSubstitution.toHashSyntax(occurrence)}"

        override fun startInWriteAction(): Boolean = false

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val captured = target.element ?: return
            if (!IntentionPreviewUtils.prepareElementForWrite(captured)) {
                return
            }
            IntentionPreviewUtils.write<RuntimeException> {
                val source = captured.text
                if (occurrence.start < 0 || occurrence.end > source.length ||
                    source.substring(occurrence.start, occurrence.end) != occurrence.text
                ) {
                    // 文本已经变了，放弃这次修复（重新 Alt+Enter 即可）
                    return@write
                }
                val updated = source.substring(0, occurrence.start) +
                    DollarSubstitution.toHashSyntax(occurrence) +
                    source.substring(occurrence.end)
                val replacement = textElement(project, updated) ?: return@write
                captured.replace(replacement)
            }
        }

        /** 用只含一段文本的临时 mapper 解析出等价的 XmlText，替换原节点（保留 PSI 事务，不裸改文档） */
        private fun textElement(project: Project, text: String): XmlText? {
            val wrapper = PsiFileFactory.getInstance(project)
                .createFileFromText("mybatis-dollar-fix.xml", XmlFileType.INSTANCE, "<mapper>$text</mapper>") as? XmlFile
            return wrapper?.rootTag?.value?.textElements?.firstOrNull()
        }
    }
}
