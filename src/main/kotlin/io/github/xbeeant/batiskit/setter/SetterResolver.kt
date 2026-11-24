package io.github.xbeeant.batiskit.setter

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAssignmentExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassInitializer
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiCodeBlock
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiEnumConstant
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiExpressionStatement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNewExpression
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiStatement
import com.intellij.psi.PsiThisExpression
import com.intellij.psi.PsiType
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil

/**
 * 一条 setter 调用：`对象.setXxx(默认值)`。
 * [value] 是短名写法（配合 [imports]），[qualifiedValue] 是补不上 import 时的全限定兜底。
 */
data class SetterCall(
    val name: String,
    val value: String,
    val qualifiedValue: String,
    val imports: List<String>
)

/**
 * 一次生成的全部信息（在 read action 里算好，写动作只按它落笔）。
 *
 * @param anchor      插到谁后面：一般是光标所在的语句，光标在方法签名 / 空代码块时是 `{`
 * @param replacement 非空时先把 anchor 这条语句替换成局部变量声明（裸 `new X();` 场景）
 */
data class SetterPlan(
    val receiver: String,
    val typeName: String,
    val calls: List<SetterCall>,
    val container: PsiCodeBlock,
    val anchor: PsiElement,
    val replacement: String? = null
)

/**
 * 从「光标 / 选区处的对象」解析出一份 [SetterPlan]。
 *
 * 支持的落点：
 * - 光标在局部变量 / 字段 / 参数名、`this`、引用表达式（`user`、`this.user`）上；
 * - 选中一段表达式（`order.getUser()`），按选区原文逐行复用；
 * - 光标在裸 `new X();` 语句上：把这条语句换成 `X x = new X();` 再补 setter；
 * - 光标在空行 / 方法签名上：取同一代码块里最近的一条局部变量声明。
 *
 * setter 清单来自 `PsiClass.allMethods`（含继承、链式 setter）与 Lombok 的 `@Data` / `@Setter` 字段，
 * 再按参数类型逐个套 [SetterDefaults] 的默认值。
 */
object SetterResolver {

    private val JAVA_KEYWORDS = setOf(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
        "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
        "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
        "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
        "volatile", "while", "true", "false", "null", "var", "record", "yield"
    )

    fun resolve(editor: Editor, file: PsiFile): SetterPlan? {
        if (file !is PsiJavaFile) {
            return null
        }
        val offset = editor.caretModel.offset
        val target = selectedTarget(editor, file)
            ?: caretTarget(file, offset)
            ?: return null

        val anchor = target.anchor
            ?: enclosingAnchor(target.origin)
            ?: enclosingBlock(target.origin)?.lBrace
            ?: return null
        // 语句或 `{` 的父节点一定是要插入的代码块，不能用 origin 猜（嵌套 if / lambda 会猜错）
        val container = anchor.parent as? PsiCodeBlock ?: return null

        val calls = settersOf(target.type, file.packageName)
        if (calls.isEmpty()) {
            return null
        }
        return SetterPlan(
            receiver = target.receiver,
            typeName = (target.type as? PsiClassType)?.className ?: target.type.presentableText,
            calls = calls,
            container = container,
            anchor = anchor,
            replacement = target.replacement
        )
    }

    // ------------------------------------------------------------------ 目标定位

    private data class RawTarget(
        val receiver: String,
        val type: PsiType,
        val origin: PsiElement,
        val anchor: PsiElement? = null,
        val replacement: String? = null
    )

    /** 选区模式：整段表达式原样当接收者（用户明确选了它，重复调用由用户自己负责） */
    private fun selectedTarget(editor: Editor, file: PsiJavaFile): RawTarget? {
        val raw = editor.selectionModel.selectedText ?: return null
        val selection = raw.trim()
        if (selection.isEmpty() || selection.contains('\n')) {
            return null
        }
        val start = editor.selectionModel.selectionStart
        val end = editor.selectionModel.selectionEnd
        val leaf = file.findElementAt(start) ?: return null

        var current: PsiElement? = leaf
        var expression: PsiExpression? = null
        while (current != null && current !is PsiFile) {
            if (current.textRange.startOffset < start || current.textRange.endOffset > end) {
                break
            }
            if (current is PsiExpression) {
                expression = current
            }
            current = current.parent
        }
        val expr = expression?.takeIf { it.text.trim() == selection } ?: return null
        if (expr is PsiNewExpression) {
            return fromNew(expr)
        }
        val type = expr.type ?: return null
        return RawTarget(expr.text, type, expr)
    }

    /** 光标模式：标识符 / 引用 / this / 变量声明 / new 表达式 */
    private fun caretTarget(file: PsiJavaFile, offset: Int): RawTarget? {
        val leaves = listOfNotNull(
            file.findElementAt(offset),
            file.findElementAt((offset - 1).coerceAtLeast(0))
        )
        leaves.forEach { leaf ->
            targetFromLeaf(leaf)?.let { return it }
        }
        return nearestVariable(file, offset)
    }

    private fun targetFromLeaf(leaf: PsiElement): RawTarget? {
        var current: PsiElement? = leaf
        while (current != null && current !is PsiFile) {
            when (current) {
                is PsiNewExpression -> return fromNew(current)
                is PsiVariable -> return variableTarget(current, current)
                is PsiThisExpression -> {
                    val type = current.type ?: return null
                    return RawTarget("this", type, current)
                }
                is PsiReferenceExpression -> {
                    val resolved = current.resolve()
                    if (resolved is PsiVariable) {
                        val type = current.type ?: resolved.type
                        return RawTarget(current.text, type, current)
                    }
                }
            }
            current = current.parent
        }
        return null
    }

    private fun variableTarget(variable: PsiVariable, origin: PsiElement): RawTarget? {
        val name = variable.name ?: return null
        return RawTarget(name, variable.type, origin)
    }

    /** `new X()` 的三种落点：变量初始化 / 赋值语句 / 裸语句 */
    private fun fromNew(expression: PsiNewExpression): RawTarget? {
        val parent = expression.parent
        when {
            parent is PsiLocalVariable -> return variableTarget(parent, parent)
            parent is PsiAssignmentExpression -> {
                val variable = (parent.lExpression as? PsiReferenceExpression)?.resolve() as? PsiVariable
                if (variable != null) {
                    return variableTarget(variable, parent)
                }
            }
            parent is PsiExpressionStatement && parent.parent is PsiCodeBlock -> {
                val type = expression.type ?: return null
                val className = expression.classReference?.referenceName
                    ?: (type as? PsiClassType)?.className
                    ?: return null
                val name = uniqueName(uncap(className), parent)
                val declaration = "${type.presentableText} $name = ${expression.text.trim()};"
                return RawTarget(name, type, parent, anchor = parent, replacement = declaration)
            }
        }
        return null
    }

    /** 光标停在空行 / 方法签名（参数名）上时，取同一代码块里最近的一条局部变量声明 */
    private fun nearestVariable(file: PsiJavaFile, offset: Int): RawTarget? {
        val leaf = file.findElementAt(offset) ?: return null
        val block = enclosingBlock(leaf) ?: return null
        val statement = block.statements.lastOrNull { it.textRange.endOffset <= offset }
        if (statement != null) {
            val variable = PsiTreeUtil.findChildOfType(statement, PsiLocalVariable::class.java)
            if (variable != null) {
                return variableTarget(variable, statement)?.copy(anchor = statement)
            }
        }
        // 空方法体里没有局部变量时，退到方法参数（测试方法 `void t(UserDTO dto) { <caret> }` 很常见）
        val method = PsiTreeUtil.getParentOfType(leaf, PsiMethod::class.java) ?: return null
        val parameter = method.parameterList.parameters.lastOrNull() ?: return null
        return variableTarget(parameter, parameter)
    }

    /** 唯一变量名：同方法里已有同名变量时追加序号 */
    private fun uniqueName(base: String, place: PsiElement): String {
        val scope = PsiTreeUtil.getParentOfType(place, PsiMethod::class.java) ?: place.containingFile
        val used = PsiTreeUtil.findChildrenOfType(scope, PsiVariable::class.java)
            .mapNotNull { it.name }
            .toMutableSet()
        val safeBase = if (base in JAVA_KEYWORDS || base.isEmpty()) "value" else base
        var name = safeBase
        var index = 1
        while (name in used) {
            name = "$safeBase${index++}"
        }
        return name
    }

    private fun uncap(text: String): String =
        if (text.isEmpty()) text else text.replaceFirstChar { it.lowercaseChar() }

    // ------------------------------------------------------------------ 插入位置

    private fun enclosingBlock(origin: PsiElement): PsiCodeBlock? =
        PsiTreeUtil.getParentOfType(origin, PsiCodeBlock::class.java, false)
            ?: PsiTreeUtil.getParentOfType(origin, PsiMethod::class.java, false)?.body
            ?: PsiTreeUtil.getParentOfType(origin, PsiClassInitializer::class.java, false)?.body

    /** 最内层、且直接挂在代码块下的语句；`if (x) foo();` 这类无花括号结构回退到外层 if */
    private fun enclosingAnchor(origin: PsiElement): PsiStatement? {
        var statement = PsiTreeUtil.getParentOfType(origin, PsiStatement::class.java, false)
        while (statement != null && statement.parent !is PsiCodeBlock) {
            statement = PsiTreeUtil.getParentOfType(statement, PsiStatement::class.java)
        }
        return statement
    }

    // ------------------------------------------------------------------ setter 清单

    private fun settersOf(type: PsiType, currentPackage: String): List<SetterCall> {
        val clazz = (type as? PsiClassType)?.resolve() ?: return emptyList()
        val entries = LinkedHashMap<String, PsiType>()
        // 本类先放：同名 setter 被子类重写时按子类的参数类型取默认值
        (clazz.methods.asList() + clazz.allMethods.asList()).forEach { method ->
            if (isSetter(method, currentPackage)) {
                entries.putIfAbsent(method.name, method.parameterList.parameters[0].type)
            }
        }
        lombokFields(clazz, entries)
        return entries.map { (name, paramType) ->
            val value = SetterDefaults.defaultFor(paramType.canonicalText, hintOf(paramType))
            SetterCall(name, value.text, value.qualifiedText, value.imports)
        }
    }

    private fun isSetter(method: PsiMethod, currentPackage: String): Boolean {
        val name = method.name
        if (name.length <= 3 || !name.startsWith("set") || !name[3].isUpperCase()) {
            return false
        }
        if (method.isConstructor || method.isDeprecated ||
            method.modifierList.annotations.orEmpty().any { shortName(it) == "Deprecated" }
        ) {
            return false
        }
        val modifiers = setOf(PsiModifier.STATIC, PsiModifier.PRIVATE, PsiModifier.PROTECTED)
        if (modifiers.any { method.hasModifierProperty(it) }) {
            return false
        }
        if (method.parameterList.parameters.size != 1) {
            return false
        }
        val owner = method.containingClass ?: return false
        if (owner.qualifiedName == "java.lang.Object") {
            return false
        }
        // 包级私有 setter 只在同一个包里可直接调用；接口方法隐式 public
        return method.hasModifierProperty(PsiModifier.PUBLIC) || owner.isInterface ||
            owner.qualifiedName?.substringBeforeLast('.', "") == currentPackage
    }

    /** Lombok 的 @Data / 类级或字段级 @Setter：PSI 里没有 setter 方法，要从字段补 */
    private fun lombokFields(clazz: PsiClass, entries: MutableMap<String, PsiType>) {
        val classAnnotations = clazz.modifierList?.annotations.orEmpty()
        val classNames = classAnnotations.map(::shortName).toSet()
        if ("Value" in classNames) {
            return // @Value 生成的是全 final 字段，没有 setter
        }
        if (classAnnotations.any { shortName(it) == "Setter" && it.text.contains("NONE") }) {
            return
        }
        val classSetter = "Data" in classNames || "Setter" in classNames
        clazz.fields.forEach { field ->
            if (field.hasModifierProperty(PsiModifier.STATIC) ||
                field.hasModifierProperty(PsiModifier.FINAL)
            ) {
                return@forEach
            }
            val annotations = field.modifierList?.annotations.orEmpty()
            if (!classSetter && annotations.none { shortName(it) == "Setter" }) {
                return@forEach
            }
            if (annotations.any { shortName(it) == "Setter" && it.text.contains("NONE") }) {
                return@forEach
            }
            val fieldName = field.name
            val setterName = "set" + fieldName.replaceFirstChar { it.uppercaseChar() }
            entries.putIfAbsent(setterName, field.type)
        }
    }

    /** 注解短名：能解析用 qualifiedName，解析不到（没有 lombok 依赖）按文本兜底 */
    private fun shortName(annotation: PsiAnnotation): String =
        annotation.qualifiedName?.substringAfterLast('.')
            ?: annotation.text.removePrefix("@").substringBefore('(').trim().substringAfterLast('.')

    // ------------------------------------------------------------------ 默认值上下文

    private fun hintOf(type: PsiType): TypeHint {
        val clazz = (type as? PsiClassType)?.resolve() ?: return TypeHint()
        val constants = if (clazz.isEnum) {
            clazz.fields.filterIsInstance<PsiEnumConstant>().mapNotNull { it.name }
        } else {
            emptyList()
        }
        return TypeHint(clazz.qualifiedName, constants, instantiable(clazz))
    }

    /** 只有「一定能 new 出来」的类才敢生成 `new X()`，否则宁可给 null */
    private fun instantiable(clazz: PsiClass): Boolean {
        if (clazz.isInterface || clazz.isEnum || clazz.isAnnotationType || clazz.isRecord ||
            clazz.hasModifierProperty(PsiModifier.ABSTRACT)
        ) {
            return false
        }
        // 非静态内部类需要外层实例，`new Inner()` 不能直接编译
        val outer = clazz.containingClass
        if (outer != null && !clazz.hasModifierProperty(PsiModifier.STATIC)) {
            return false
        }
        val constructors = clazz.constructors
        if (constructors.isEmpty()) {
            return !clazz.hasModifierProperty(PsiModifier.PRIVATE)
        }
        return constructors.any {
            it.parameterList.parameters.isEmpty() &&
                it.hasModifierProperty(PsiModifier.PUBLIC)
        }
    }
}
