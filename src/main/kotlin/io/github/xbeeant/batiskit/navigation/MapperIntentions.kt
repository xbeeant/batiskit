package io.github.xbeeant.batiskit.navigation

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.psi.PsiFile
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import io.github.xbeeant.batiskit.config.GeneratorState
import io.github.xbeeant.batiskit.config.MapperAssistSettings
import io.github.xbeeant.batiskit.config.dialectEnum
import io.github.xbeeant.batiskit.inspect.ParamAnnotations
import io.github.xbeeant.batiskit.meta.MapperSqlRules
import io.github.xbeeant.batiskit.meta.MapperStatementSql
import io.github.xbeeant.batiskit.write.ModuleRoots

/** Mapper 接口判定：已有绑定 XML，或类名以 Mapper / Dao 结尾 */
internal fun isMapperInterface(clazz: PsiClass): Boolean {
    if (!clazz.isInterface) {
        return false
    }
    val name = clazz.name ?: return false
    return MapperXmlResolver.xmlRoot(clazz) != null ||
        name.endsWith("Mapper") || name.endsWith("Dao")
}

internal fun findMapperMethod(element: PsiElement): PsiMethod? {
    val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java) ?: return null
    val clazz = method.containingClass ?: return null
    return if (isMapperInterface(clazz)) method else null
}

/** 方法名推断语句类型（select / insert / update / delete）；前缀表可在 Mapper 辅助设置页改写 */
internal fun statementKind(methodName: String): String = MapperSqlRules.kindOf(
    methodName,
    MapperSqlRules.parsePrefixRules(MapperAssistSettings.getInstance().state.prefixRules)
)

/** select 语句的返回类型（List 取泛型实参），解析不到返回 null */
internal fun selectResultType(method: PsiMethod): String? {
    val returnType = method.returnType as? PsiClassType ?: return null
    val itemType = if (returnType.className in listOf("List", "Collection", "Set", "Page")) {
        returnType.parameters.firstOrNull() as? PsiClassType ?: returnType
    } else {
        returnType
    }
    return itemType.resolve()?.qualifiedName
}

/**
 * Alt+Enter：为 Mapper 方法缺失 @Param 的参数补注解（含 import 整理）
 */
class AddParamAnnotationIntention : PsiElementBaseIntentionAction() {

    override fun getFamilyName(): String = "MyBatis"

    override fun getText(): String = "为 Mapper 方法参数添加 @Param 注解"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val method = findMapperMethod(element) ?: return false
        return missingParams(method).isNotEmpty()
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val method = findMapperMethod(element) ?: return
        val missing = missingParams(method)
        if (missing.isEmpty()) {
            return
        }
        WriteCommandAction.runWriteCommandAction(project) {
            // 注解的插入与 import 整理统一走 ParamAnnotations，与检查里的 quickfix 共用一份实现
            missing.forEach { param -> ParamAnnotations.add(project, param, param.name.orEmpty()) }
        }
    }

    /**
     * 预览展示「补了 @Param 之后的方法」：按 [ParamAnnotations.add] 写入的
     * `@Param("name")` 文本重建参数列表，再用 PsiFileFactory 造临时 Java 文件解析出片段。
     *
     * invoke 里的 WriteCommandAction / import 整理不能在后台预览上下文执行（会死锁），
     * 这里只读 PSI、纯文本构造，绝不回写物理文件；import 整理不影响片段里的注解形态。
     */
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        val element = file.findElementAt(editor.caretModel.offset) ?: return IntentionPreviewInfo.EMPTY
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
            ?: return IntentionPreviewInfo.EMPTY
        val missing = missingParams(method)
        if (missing.isEmpty()) return IntentionPreviewInfo.EMPTY
        val params = method.parameterList.parameters.joinToString(", ") { param ->
            if (param in missing) "@Param(\"${param.name.orEmpty()}\") ${param.text}" else param.text
        }
        // 签名里的参数列表总在方法体之前，只替换第一处
        val modified = method.text.replaceFirst(method.parameterList.text, "($params)")
        // 放进临时接口里让平台按 Java 语法解析，再取回改动后方法的规范文本
        val temp = PsiFileFactory.getInstance(project).createFileFromText(
            "Preview.java", JavaFileType.INSTANCE, "interface Preview {\n$modified\n}"
        ) as? PsiJavaFile
        val snippet = temp?.classes?.firstOrNull()?.methods?.firstOrNull()?.text ?: modified
        return IntentionPreviewInfo.CustomDiff(JavaFileType.INSTANCE, file.name, "", snippet)
    }

    private fun missingParams(method: PsiMethod): List<PsiParameter> =
        method.parameterList.parameters.filterNot { ParamAnnotations.has(it) }
}

/**
 * Alt+Enter：在 Mapper XML 中生成与方法对应的语句骨架；
 * 接口还没有 XML 时按生成器的路径规则（资源根 / mapperXmlDir / 方言 / 库名）新建文件
 */
class GenerateXmlStatementIntention : PsiElementBaseIntentionAction() {

    override fun getFamilyName(): String = "MyBatis"

    override fun getText(): String = "在 Mapper XML 中生成对应语句"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val method = findMapperMethod(element) ?: return false
        return MapperXmlResolver.statements(method).isEmpty()
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val method = findMapperMethod(element) ?: return
        val clazz = method.containingClass ?: return
        val files = MapperXmlResolver.xmlFiles(clazz)
        when {
            // 唯一绑定：直接追加
            files.size == 1 -> appendToRoot(project, method, files[0].rootTag)
            // 同 namespace 多个 XML：弹窗选择落点（MybatisX 同款交互）
            files.size > 1 -> chooseXmlAndAppend(project, method, files)
            // 没有绑定 XML：按生成器路径规则新建后追加
            else -> {
                var added: XmlTag? = null
                WriteCommandAction.runWriteCommandAction(project) {
                    val root = createMapperXml(project, clazz) ?: return@runWriteCommandAction
                    added = appendStatement(project, method, root)
                }
                added?.let { MapperXmlStatementWriter.navigateTo(project, it.containingFile, method.name) }
            }
        }
    }

    private fun appendToRoot(project: Project, method: PsiMethod, root: XmlTag?) {
        if (root == null) {
            return
        }
        // appendStatement 自己开写命令；写动作完成后打开目标文件并定位到新语句
        val added = appendStatement(project, method, root)
        added?.let { MapperXmlStatementWriter.navigateTo(project, it.containingFile, method.name) }
    }

    /** 多个 XML 文件都绑定同一 namespace 时，让用户选择语句落点（条目用包装类型，平台禁止 PsiElement 直接做弹窗模型） */
    private fun chooseXmlAndAppend(project: Project, method: PsiMethod, files: List<XmlFile>) {
        val roots = files.mapNotNull { it.rootTag }
        if (roots.isEmpty()) {
            return
        }
        if (roots.size == 1) {
            appendToRoot(project, method, roots[0])
            return
        }
        // PSI 读取（相对路径 / tooltip）必须在构建时完成——渲染器在 EDT 无锁回调，不能再碰 PSI/VFS
        val items = roots.map { root ->
            PopupItem(
                root,
                middleTruncate(displayPath(project, root.containingFile)),
                root.containingFile?.virtualFile?.path.orEmpty()
            )
        }
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle("该 Mapper 绑定了多个 XML，选择文件")
            .setRenderer(SimpleListCellRenderer.create { label, value, _ ->
                label.text = value.display
                // 悬停提示展示完整路径
                label.toolTipText = value.tooltip
            })
            .setItemChosenCallback { item -> appendToRoot(project, method, item.root) }
            .createPopup()
        popup.showInFocusCenter()
    }

    /** 弹窗条目：预计算展示文本与 tooltip，避免渲染时访问 PSI */
    private class PopupItem(
        val root: XmlTag,
        val display: String,
        val tooltip: String
    )

    /** 展示用路径：优先取相对当前工程根的路径（缩短长度），跨工程时回退绝对路径 */
    private fun displayPath(project: Project, file: PsiFile?): String {
        val vf = file?.virtualFile ?: return file?.name ?: "(unknown)"
        val basePath = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        if (basePath != null) {
            VfsUtilCore.getRelativePath(vf, basePath, '/')?.let { return it }
        }
        return vf.path
    }

    /**
     * 路径过长时截断中间部分（保留头尾），例如：
     * module-A/src/main/resources/.../demo/UserMapper.xml
     */
    private fun middleTruncate(path: String, maxLength: Int = 100): String {
        if (path.length <= maxLength) {
            return path
        }
        val keep = maxLength - 3
        val head = keep / 2
        val tail = keep - head
        return path.take(head) + "..." + path.takeLast(tail)
    }

    /** 组装语句（有表结构时预填 CRUD SQL）并追加到根标签**末尾**，返回新标签（用于生成后跳转） */
    private fun appendStatement(project: Project, method: PsiMethod, root: XmlTag): XmlTag? {
        val kind = statementKind(method.name)
        val resultType = if (kind == "select") selectResultType(method) else null
        val sql = MapperStatementSql.build(project, method, kind, root.containingFile as? XmlFile)
        val statementText = MapperSqlRules.statementXml(kind, method.name, resultType, sql)
        return MapperXmlStatementWriter.append(project, root, statementText, method.name)
    }

    /**
     * 预览只展示「将要追加的语句标签」：与 [appendStatement] 共用
     * [MapperSqlRules.statementXml] 造骨架，再用 PsiFileFactory 解析成临时 XmlFile 取回片段。
     *
     * invoke 还可能新建 XML 文件 / 目录、弹窗选落点、打开编辑器并跳转，这些副作用在后台
     * 预览上下文里既不安全也无法复现，因此不进入预览；SQL 预填依赖目标 XML 与表结构，
     * 预览统一用 TODO 骨架，不伪造成已经解析出的 CRUD SQL。
     */
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        val element = file.findElementAt(editor.caretModel.offset) ?: return IntentionPreviewInfo.EMPTY
        val method = PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)
            ?: return IntentionPreviewInfo.EMPTY
        val kind = statementKind(method.name)
        val resultType = if (kind == "select") selectResultType(method) else null
        val skeleton = MapperSqlRules.statementXml(kind, method.name, resultType, null)
        val temp = PsiFileFactory.getInstance(project).createFileFromText(
            "Preview.xml", XmlFileType.INSTANCE, "<mapper>$skeleton</mapper>"
        ) as? XmlFile
        val snippet = temp?.rootTag?.subTags?.firstOrNull()?.text ?: skeleton
        val name = "${method.containingClass?.name ?: "Mapper"}.xml"
        return IntentionPreviewInfo.CustomDiff(XmlFileType.INSTANCE, name, "", snippet)
    }

    /** 按生成器的路径规则创建空 XML（资源根 / mapperXmlDir / 方言 / 库名 / 类名.xml） */
    private fun createMapperXml(project: Project, clazz: PsiClass): XmlTag? {
        val module = ModuleUtilCore.findModuleForPsiElement(clazz) ?: return null
        val dir = ModuleRoots.ensureResourceRoot(project, module) ?: return null
        val config = GeneratorState.getInstance().state
        var current = dir
        val segments = buildList {
            add("mybatis")
            add(config.dialectEnum.name.lowercase())
            if (config.databaseName.isNotBlank()) {
                add(config.databaseName)
            }
        }
        segments.forEach { segment ->
            current = current.findSubdirectory(segment) ?: current.createSubdirectory(segment)
        }
        val name = (clazz.name ?: "Mapper") + ".xml"
        val fqn = clazz.qualifiedName ?: return null
        val text = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<!DOCTYPE mapper PUBLIC \"-//mybatis.org//DTD Mapper 3.0//EN\" \"http://mybatis.org/dtd/mybatis-3-mapper.dtd\">\n" +
            "<mapper namespace=\"$fqn\">\n</mapper>\n"
        val created = PsiFileFactory.getInstance(project)
            .createFileFromText(name, XmlFileType.INSTANCE, text)
        val file = current.add(created) as? XmlFile ?: return null
        return file.rootTag
    }
}
