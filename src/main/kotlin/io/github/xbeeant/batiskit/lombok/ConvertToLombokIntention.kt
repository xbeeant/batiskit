package io.github.xbeeant.batiskit.lombok

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.ide.highlighter.JavaFileType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionStatement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiImportStatement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiModifierList
import com.intellij.psi.PsiReturnStatement
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTreeUtil

/**
 * 一键转成 Lombok：类级加 `@Getter` / `@Setter`，删掉手写访问器与 `serialVersionUID`。
 *
 * - 只删**规范形态**的访问器：getter 方法体恰好是 `return field;` / `return this.field;`，
 *   setter 方法体恰好是 `this.field = param;` / `field = param;`，且签名与字段类型一致；
 *   带业务逻辑的 getter / setter 原样保留——Lombok 对已有同名方法的字段会自动跳过，不会生成重复访问器；
 * - `serialVersionUID` 只删 `private static final long serialVersionUID = …` 这一规范形态，
 *   其余静态字段不动；
 * - 已有 `@Getter` / `@Setter` 时不重复添加；
 * - 注解写短名并显式 `import lombok.Getter` / `import lombok.Setter`，不依赖
 *   `shortenClassReferences`（工程里没有 lombok 依赖时它不会补 import，注解就会一直停在
 *   `@lombok.Getter` 这种全限定写法上）；短名有冲突 / 补不上 import 才退回全限定名。
 */
class ConvertToLombokIntention : PsiElementBaseIntentionAction() {

    override fun getFamilyName(): String = "Lombok"

    override fun getText(): String = "转成 Lombok（@Getter / @Setter，移除 getter / setter 与 serialVersionUID）"

    override fun isAvailable(project: Project, editor: Editor?, element: PsiElement): Boolean {
        val clazz = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false) ?: return false
        if (clazz.isInterface || clazz.isAnnotationType || clazz.isEnum) {
            return false
        }
        return removableAccessors(clazz).isNotEmpty() || serialVersionUIDOf(clazz) != null
    }

    override fun invoke(project: Project, editor: Editor?, element: PsiElement) {
        val clazz = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false) ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            addAnnotations(project, clazz)
            removableAccessors(clazz).forEach { it.delete() }
            serialVersionUIDOf(clazz)?.delete()
        }
    }

    /**
     * 预览展示「改动后的类片段」：按 invoke 的同一套判定，用文本范围删掉规范访问器与
     * serialVersionUID、在类声明前补 @Getter / @Setter，再经 PsiFileFactory 解析成临时 Java 文件。
     *
     * invoke 里的 WriteCommandAction / 删 PSI / 补 import 不能在后台预览上下文执行
     * （会死锁或被命令校验拦下），这里只读 PSI、纯文本变换，绝不回写物理文件；
     * 短名冲突时 invoke 会退回全限定名，预览统一按短名展示，仅示意改动范围。
     */
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        val element = file.findElementAt(editor.caretModel.offset) ?: return IntentionPreviewInfo.EMPTY
        val clazz = PsiTreeUtil.getParentOfType(element, PsiClass::class.java, false)
            ?: return IntentionPreviewInfo.EMPTY
        if (clazz.isInterface || clazz.isAnnotationType || clazz.isEnum) return IntentionPreviewInfo.EMPTY
        val removed: List<PsiElement> = buildList {
            addAll(removableAccessors(clazz))
            serialVersionUIDOf(clazz)?.let { add(it) }
        }
        if (removed.isEmpty()) return IntentionPreviewInfo.EMPTY

        val base = clazz.textRange.startOffset
        var text = clazz.text
        removed.map { (it.textRange.startOffset - base) until (it.textRange.endOffset - base) }
            .sortedByDescending { it.first }
            .forEach { range -> text = text.removeRange(range) }

        val list = clazz.modifierList
        val annotations = buildList {
            if (list == null || list.annotations.none { isLombokAnnotation(it, "lombok.Getter", "Getter") }) {
                add("@Getter")
            }
            if (list == null || list.annotations.none { isLombokAnnotation(it, "lombok.Setter", "Setter") }) {
                add("@Setter")
            }
        }
        val snippet = (annotations + text).joinToString("\n")
        val temp = PsiFileFactory.getInstance(project)
            .createFileFromText("Preview.java", JavaFileType.INSTANCE, snippet)
        return IntentionPreviewInfo.CustomDiff(JavaFileType.INSTANCE, file.name, "", temp.text)
    }

    // ------------------------------------------------------------------ 改写

    /**
     * 类级补 `@Getter` / `@Setter`（已有则跳过），import 由 [ensureImport] 显式补。
     * 两个注解独立判定：只有其中一个短名冲突时，另一个仍然用短名 + import。
     */
    private fun addAnnotations(project: Project, clazz: PsiClass) {
        val list = clazz.modifierList ?: return
        val file = clazz.containingFile as? PsiJavaFile
        val getter = ensureAnnotation(project, clazz, list, file, "lombok.Getter", "Getter", null)
        if (getter != null) {
            ensureAnnotation(project, clazz, list, file, "lombok.Setter", "Setter", getter)
        }
    }

    /** 已有则原样保留（全限定写法顺手规范化），没有就补上；返回值作为下一个注解的插入锚点 */
    private fun ensureAnnotation(
        project: Project,
        clazz: PsiClass,
        list: PsiModifierList,
        file: PsiJavaFile?,
        fqn: String,
        simple: String,
        after: PsiAnnotation?
    ): PsiAnnotation? {
        val factory = JavaPsiFacade.getElementFactory(project)
        val existing = list.annotations.firstOrNull { isLombokAnnotation(it, fqn, simple) }
        val shortNameUsable = ensureImport(project, file, fqn)
        val text = if (shortNameUsable) "@$simple" else "@$fqn"
        if (existing != null) {
            return if (shortNameUsable && existing.text.startsWith("@$fqn")) {
                existing.replace(factory.createAnnotationFromText(text, clazz)) as? PsiAnnotation
            } else {
                existing
            }
        }
        val created = factory.createAnnotationFromText(text, clazz)
        return if (after != null) {
            list.addAfter(created, after) as? PsiAnnotation
        } else {
            list.addBefore(created, list.firstChild) as? PsiAnnotation
        }
    }

    /** 注解是否已存在：能解析按全限定名比，解析不到按文本兜底（带参数的 `@Getter(...)` 也算） */
    private fun isLombokAnnotation(annotation: PsiAnnotation, fqn: String, simple: String): Boolean =
        annotation.qualifiedName == fqn ||
            annotation.text.startsWith("@$fqn") ||
            Regex("^@$simple\\b").containsMatchIn(annotation.text)

    /**
     * @return 能否安全地以短名使用（同包 / 已 import / 补 import 成功）。
     * 补不上就退回全限定名，保证改完的代码一定能编译。用临时 Java 文件造 import 语句，
     * 不依赖 lombok 类能否被索引到。
     */
    private fun ensureImport(project: Project, file: PsiJavaFile?, fqn: String): Boolean {
        if (file == null) {
            return false
        }
        val packageName = fqn.substringBeforeLast('.')
        if (packageName == file.packageName) {
            return true
        }
        val list = file.importList ?: return false
        val simple = fqn.substringAfterLast('.')
        val statements = list.importStatements
        if (statements.any { it.qualifiedName == fqn || (it.isOnDemand && it.qualifiedName == packageName) }) {
            return true
        }
        // 已有别的同名类（import com.foo.Getter;）：短名会指错类，只能写全限定名
        if (statements.any { it.qualifiedName?.substringAfterLast('.') == simple }) {
            return false
        }
        return runCatching {
            val dummy = PsiFileFactory.getInstance(project).createFileFromText(
                "Dummy.java", JavaFileType.INSTANCE, "import $fqn;\nclass Dummy {}"
            ) as? PsiJavaFile ?: return false
            val statement = dummy.importList?.importStatements?.firstOrNull()?.copy() as? PsiImportStatement
                ?: return false
            list.add(statement)
            true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------ 识别

    /** 各字段规范形态的访问器（getter / isGetter / setter），每类最多一个 */
    private fun removableAccessors(clazz: PsiClass): List<PsiMethod> {
        val result = mutableListOf<PsiMethod>()
        clazz.fields
            .filterNot { it.hasModifierProperty(PsiModifier.STATIC) }
            .forEach { field ->
                val name = field.name ?: return@forEach
                val cap = name.replaceFirstChar { it.uppercaseChar() }
                clazz.findMethodsByName("get$cap", false)
                    .firstOrNull { isCanonicalGetter(it, field) }
                    ?.let { result += it }
                if (field.type == PsiType.BOOLEAN) {
                    clazz.findMethodsByName("is$cap", false)
                        .firstOrNull { isCanonicalGetter(it, field) }
                        ?.let { result += it }
                }
                clazz.findMethodsByName("set$cap", false)
                    .firstOrNull { isCanonicalSetter(it, field) }
                    ?.let { result += it }
            }
        return result
    }

    private fun isCanonicalGetter(method: PsiMethod, field: PsiField): Boolean {
        if (method.isConstructor || method.hasParameters()) {
            return false
        }
        if (method.returnType != field.type) {
            return false
        }
        val statements = method.body?.statements ?: return false
        if (statements.size != 1) {
            return false
        }
        val returned = (statements[0] as? PsiReturnStatement)?.returnValue ?: return false
        return isFieldAccess(returned, field)
    }

    private fun isCanonicalSetter(method: PsiMethod, field: PsiField): Boolean {
        if (method.returnType != PsiType.VOID) {
            return false
        }
        val parameters = method.parameterList.parameters
        if (parameters.size != 1 || parameters[0].type != field.type) {
            return false
        }
        val statements = method.body?.statements ?: return false
        if (statements.size != 1) {
            return false
        }
        val assignment = (statements[0] as? PsiExpressionStatement)?.expression as? PsiAssignmentExpression
            ?: return false
        if (assignment.operationTokenType !== JavaTokenType.EQ) {
            return false
        }
        return isFieldAccess(assignment.lExpression, field) &&
            assignment.rExpression?.text?.trim() == parameters[0].name
    }

    /** 表达式是否为 `field` 或 `this.field` */
    private fun isFieldAccess(expression: PsiExpression?, field: PsiField): Boolean {
        val text = expression?.text?.trim() ?: return false
        return text == field.name || text == "this.${field.name}"
    }

    /** 规范形态的 `serialVersionUID`（static final long），其余同名静态字段不动 */
    private fun serialVersionUIDOf(clazz: PsiClass): PsiField? =
        clazz.findFieldByName("serialVersionUID", false)?.takeIf { field ->
            field.hasModifierProperty(PsiModifier.STATIC) &&
                field.hasModifierProperty(PsiModifier.FINAL) &&
                field.type == PsiType.LONG
        }
}
