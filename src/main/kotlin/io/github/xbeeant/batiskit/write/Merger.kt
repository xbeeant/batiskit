package io.github.xbeeant.batiskit.write

import com.intellij.ide.highlighter.JavaFileType
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * Java 文件合并：把新生成的成员「补」进已有文件，而不是整文件覆盖。
 *
 *  - 缺的 import 补上
 *  - 缺的类注解（如 @Data）补上
 *  - 缺的字段（按字段名判断）补上
 *  - 缺的方法（按 方法名 + 参数类型列表 判断）补上
 *  - 同签名方法实现有差异时：覆盖开关打开则用新实现替换旧方法（含文档），关闭则保留旧实现
 */
object JavaMerger {

    fun merge(target: PsiJavaFile, generatedText: String, overwriteMethods: Boolean = true): MergeOutcome {
        val project = target.project
        val genFile = PsiFileFactory.getInstance(project)
            .createFileFromText("Generated.java", JavaFileType.INSTANCE, generatedText) as? PsiJavaFile
            ?: return MergeOutcome(0, "无法解析生成内容")
        val genClass = genFile.classes.firstOrNull() ?: return MergeOutcome(0, "生成内容里没有类声明")
        val targetClass = target.classes.firstOrNull() ?: return MergeOutcome(0, "目标文件里没有类声明")

        var added = 0

        // 1. import（先改文档，再改 PSI，避免文档与 PSI 不一致）
        val imports = generatedText.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("import ") && it.endsWith(";") }
            .map { it.removePrefix("import ").removeSuffix(";").trim() }
            .toList()
        if (imports.isNotEmpty()) {
            addMissingImports(project, target, imports)
        }

        // 2. 类注解
        genClass.modifierList?.annotations?.forEach { annotation ->
            val qualified = annotation.qualifiedName ?: return@forEach
            if (targetClass.modifierList?.findAnnotation(qualified) == null) {
                targetClass.modifierList?.addAnnotation(qualified)
                added++
            }
        }

        // 3. 字段
        genClass.fields.forEach { field ->
            val name = field.name
            if (targetClass.findFieldByName(name, false) == null) {
                targetClass.add(field.copy())
                added++
            }
        }

        // 4. 方法：同签名（方法名 + 参数类型列表）时，实现有差异按覆盖开关决定替换或保留
        genClass.methods.forEach { method ->
            val signature = signatureOf(method)
            val existing = targetClass.findMethodsByName(method.name, false)
                .filter { signatureOf(it) == signature }
            when {
                existing.isEmpty() -> {
                    targetClass.add(method.copy())
                    added++
                }

                overwriteMethods && normalize(existing.first().text) != normalize(method.copy().text) -> {
                    existing.first().replace(method.copy())
                    added++
                }
            }
        }

        PsiDocumentManager.getInstance(project).commitAllDocuments()
        CodeStyleManager.getInstance(project).reformat(target)
        return MergeOutcome(added, null)
    }

    /** 方法签名：名字 + 参数类型（去掉泛型与空白，避免 List<User> 与原始 List 误判为不同） */
    private fun signatureOf(method: com.intellij.psi.PsiMethod): String =
        method.name + "(" + method.parameterList.parameters.joinToString(",") {
            it.type.presentableText.replace(Regex("<.*>"), "").replace(" ", "")
        } + ")"

    /** 归一化空白后比较，避免排版差异造成无谓替换 */
    private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun addMissingImports(project: Project, file: PsiJavaFile, imports: List<String>) {
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
        val text = document.text
        val missing = imports.filter { !text.contains("import $it;") }
        if (missing.isEmpty()) {
            return
        }

        val insertText = missing.joinToString("") { "import $it;\n" }
        val lastImportIndex = text.lastIndexOf("\nimport ")
        val offset = when {
            lastImportIndex >= 0 -> {
                val semicolon = text.indexOf(';', lastImportIndex)
                if (semicolon >= 0) semicolon + 1 else text.length
            }

            text.contains("package ") -> {
                val semicolon = text.indexOf(';', text.indexOf("package "))
                if (semicolon >= 0) semicolon + 1 else 0
            }

            else -> 0
        }
        val prefix = if (offset == 0) "" else "\n"
        document.insertString(offset, prefix + insertText)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }
}

data class MergeOutcome(val added: Int, val error: String?)

/** XML 文件合并：语句按 id 只补缺；<sql> 片段（where / column list）内容有差异时整体替换 */
object XmlMerger {

    fun merge(target: XmlFile, generatedText: String): MergeOutcome {
        val project = target.project
        val genFile = PsiFileFactory.getInstance(project)
            .createFileFromText("Generated.xml", XmlFileType.INSTANCE, generatedText)
                as? XmlFile
            ?: return MergeOutcome(0, "无法解析生成内容")
        val genRoot = genFile.rootTag ?: return MergeOutcome(0, "生成内容没有根节点")
        val root = target.rootTag ?: return MergeOutcome(0, "目标文件没有根节点")

        var added = 0
        val touched = mutableListOf<XmlTag>()
        val namespace = genRoot.getAttributeValue("namespace")
        if (!namespace.isNullOrBlank() && root.getAttributeValue("namespace").isNullOrBlank()) {
            root.setAttribute("namespace", namespace)
            added++
        }

        genRoot.subTags.forEach { tag ->
            val id = tag.getAttributeValue("id")
            val existing = root.subTags.firstOrNull { candidate ->
                candidate.name == tag.name && (id == null || candidate.getAttributeValue("id") == id)
            }
            when {
                existing == null -> {
                    touched += root.add(tag.copy()).asTag(tag)
                    added++
                }
                // <sql> 片段（Example_Where_Clause / Base_Column_List 等基础设施）由生成器维护：
                // 内容有差异（如新增 Begin/End 区间条件）时整体替换，保证片段级变更同步到已有文件；
                // select/insert 等语句仍只补缺，保护人工修改
                tag.name == "sql" && normalize(existing.text) != normalize(tag.text) -> {
                    touched += existing.replace(tag.copy()).asTag(tag)
                    added++
                }
            }
        }

        PsiDocumentManager.getInstance(project).commitAllDocuments()
        // 只格式化本次动过的标签：整文件 reformat 会把全量空白重建一遍，大 mapper 上既慢，
        // 又容易在文档型虚拟文件上触发 PSI 失效告警
        val manager = CodeStyleManager.getInstance(project)
        touched.forEach { tag -> runCatching { manager.reformat(tag) } }
        return MergeOutcome(added, null)
    }

    /** 归一化空白后比较，避免排版差异造成无谓替换 */
    private fun normalize(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** add / replace 的返回值是 PsiElement：不是标签时退回生成侧的标签（格式化它无害） */
    private fun PsiElement.asTag(fallback: XmlTag): XmlTag = this as? XmlTag ?: fallback
}
