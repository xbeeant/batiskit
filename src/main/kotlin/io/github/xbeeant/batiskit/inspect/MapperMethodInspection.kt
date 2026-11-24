package io.github.xbeeant.batiskit.inspect

import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaElementVisitor
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiMethod
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import io.github.xbeeant.batiskit.meta.MapperSqlRules
import io.github.xbeeant.batiskit.meta.MapperStatementSql
import io.github.xbeeant.batiskit.navigation.MapperXmlResolver
import io.github.xbeeant.batiskit.navigation.selectResultType
import io.github.xbeeant.batiskit.navigation.statementKind

/**
 * Mapper 接口侧的检查：需要 XML 绑定的方法，是否真有对应的语句。
 *
 * 这是 MyBatis 最常见的一类上线事故——接口加了方法、XML 忘了写，或者 id 拼错一个字母，
 * 编译期毫无提示，只有跑到那一行才抛 `BindingException: Invalid bound statement (not found)`。
 *
 * 判定口径（见 [StatementBinding.needsStatement]）：
 *  - 只看**直接声明在接口里**的方法，继承来的方法（`BaseMapper` / 自定义基接口）不管；
 *  - 带方法体的不行（`default` / `static`），MyBatis 不绑定这类方法；
 *  - 写了 `@Select` / `@Insert` / `@Update` / `@Delete` / `*Provider` 的不行，SQL 在注解里；
 *  - **接口完全没有绑定 XML 时不检查**——注解式 mapper、以及忘了建 XML 的情况都不在此报，
 *    宁可漏报也不要让「不用 XML 的人」满屏红。
 */
class MapperMethodInspection : LocalInspectionTool(), DumbAware {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        if (DumbService.isDumb(holder.project)) {
            return PsiElementVisitor.EMPTY_VISITOR
        }
        return object : JavaElementVisitor() {
            override fun visitClass(aClass: PsiClass) {
                inspect(aClass, holder)
            }
        }
    }

    private fun inspect(clazz: PsiClass, holder: ProblemsHolder) {
        if (!clazz.isInterface) {
            return
        }
        val files = MapperXmlResolver.xmlFiles(clazz)
        if (files.isEmpty()) {
            // 没有绑定 XML：可能是注解式 mapper，也可能压根不用 XML，都不是本检查的判定范围
            return
        }
        val ids = files.flatMap { file -> statementIds(file) }.toSet()
        val methods = clazz.methods.map { method ->
            MapperMethod(
                name = method.name,
                annotations = annotationsOf(method),
                hasBody = method.body != null,
                inherited = false
            )
        }
        val xmlName = files.joinToString("、") { it.name }
        StatementBinding.missingStatements(methods, ids).forEach { missing ->
            val method = clazz.findMethodsByName(missing.name, false).firstOrNull() ?: return@forEach
            holder.registerProblem(
                method.nameIdentifier ?: method,
                "Mapper 方法 \"${missing.name}\" 在 $xmlName 里没有对应语句，" +
                    "调用时会抛 Invalid bound statement (not found)",
                *fixesFor(files, method).toTypedArray()
            )
        }
    }

    /** 语句 id 全集（只取增删改查标签，`<sql>` / `<resultMap>` 的 id 不算） */
    private fun statementIds(file: XmlFile): List<String> =
        file.rootTag?.subTags
            ?.filter { it.name in StatementBinding.STATEMENT_TAGS }
            ?.mapNotNull { it.getAttributeValue("id")?.trim()?.takeIf { id -> id.isNotEmpty() } }
            ?: emptyList()

    /** 注解的 FQN（解析不到时退化成源码里写的名字，够用来比对简单名） */
    private fun annotationsOf(method: PsiMethod): Set<String> =
        method.modifierList.annotations.mapNotNullTo(mutableSetOf()) { annotation ->
            annotation.qualifiedName ?: annotation.nameReferenceElement?.text
        }

    /**
     * 只在「唯一一个 XML、且有根标签」时提供生成语句骨架的修复：
     * 同 namespace 多个 XML 时该落到哪个文件是用户的意图，猜错等于写坏别人的文件。
     */
    private fun fixesFor(files: List<XmlFile>, method: PsiMethod): List<LocalQuickFix> {
        if (files.size != 1) {
            return emptyList()
        }
        val root = files[0].rootTag ?: return emptyList()
        val pointer = SmartPointerManager.getInstance(root.project).createSmartPsiElementPointer(root)
        val kind = statementKind(method.name)
        return listOf(
            AddStatementFix(
                target = pointer,
                methodName = method.name,
                kind = kind,
                resultType = if (kind == "select") selectResultType(method) else null,
                sql = MapperStatementSql.build(root.project, method, kind, files[0]),
                fileName = files[0].name
            )
        )
    }

    /**
     * 在绑定 XML 里补一条语句骨架（`id` 与返回类型按方法名推断，SQL 留 TODO）。
     *
     * 与 [io.github.xbeeant.batiskit.navigation.GenerateXmlStatementIntention] 同一套推断规则，
     * 区别是这里只在已有 XML 上追加，不涉及建目录/建文件，因此可以安全地放在 quickfix 里。
     */
    private class AddStatementFix(
        private val target: SmartPsiElementPointer<XmlTag>,
        private val methodName: String,
        private val kind: String,
        private val resultType: String?,
        private val sql: String?,
        private val fileName: String
    ) : LocalQuickFix {

        override fun getFamilyName(): String = "生成 MyBatis 语句"

        override fun getName(): String = "在 $fileName 中生成 <$kind id=\"$methodName\">"

        /** 自己开写命令，保证可撤销 */
        override fun startInWriteAction(): Boolean = false

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val root = target.element ?: return
            // 跨文件写入：预览模式下平台拿不到同一个副本，返回 false 时静默放弃即可
            if (!IntentionPreviewUtils.prepareElementForWrite(root)) {
                return
            }
            IntentionPreviewUtils.write<RuntimeException> {
                // select 缺 resultType 时 MyBatis 同样起不来，注释里点名提醒
                val note = if (kind == "select" && resultType == null) "补充 resultType" else null
                val skeleton = MapperSqlRules.statementXml(kind, methodName, resultType, sql, note)
                val wrapper = PsiFileFactory.getInstance(project)
                    .createFileFromText("mybatis-statement.xml", XmlFileType.INSTANCE, "<mapper>$skeleton</mapper>") as? XmlFile
                val created = wrapper?.rootTag?.subTags?.firstOrNull() ?: return@write
                root.addSubTag(created.copy() as XmlTag, true)
            }
        }

        /**
         * 预览展示「将追加到绑定 XML 的语句标签」：note / resultType / sql 与 [applyFix]
         * 用的是同一份构造参数，跨文件写入本身不进预览。
         *
         * 平台的自动预览把改动应用到 Java 接口的副本上，而本 fix 写的是另一个 XML 文件，
         * 副本里没有目标 rootTag（[applyFix] 里 IntentionPreviewUtils.prepareElementForWrite
         * 也会因此返回 false），所以不能用默认 DIFF；这里用 PsiFileFactory 造临时 XmlFile
         * 取回真实语句片段，以 CustomDiff 展示，不伪造一份并不存在的 Java 侧改动。
         */
        override fun generatePreview(project: Project, previewDescriptor: ProblemDescriptor): IntentionPreviewInfo {
            val note = if (kind == "select" && resultType == null) "补充 resultType" else null
            val skeleton = MapperSqlRules.statementXml(kind, methodName, resultType, sql, note)
            val temp = PsiFileFactory.getInstance(project).createFileFromText(
                "mybatis-statement.xml", XmlFileType.INSTANCE, "<mapper>$skeleton</mapper>"
            ) as? XmlFile
            val snippet = temp?.rootTag?.subTags?.firstOrNull()?.text ?: skeleton
            return IntentionPreviewInfo.CustomDiff(XmlFileType.INSTANCE, fileName, "", snippet)
        }
    }
}
