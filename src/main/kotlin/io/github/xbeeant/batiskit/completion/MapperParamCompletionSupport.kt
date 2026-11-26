package io.github.xbeeant.batiskit.completion

import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.PrioritizedLookupElement
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementManipulators
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiArrayType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiType
import com.intellij.psi.PsiTypes
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiTypesUtil
import com.intellij.psi.util.TypeConversionUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import io.github.xbeeant.batiskit.config.MapperAssistSettings
import io.github.xbeeant.batiskit.inspect.MapperParam
import io.github.xbeeant.batiskit.inspect.ParamAnnotations
import io.github.xbeeant.batiskit.inspect.ParamBinding
import io.github.xbeeant.batiskit.meta.MapperSqlRules
import io.github.xbeeant.batiskit.meta.TableMappingResolver
import io.github.xbeeant.batiskit.meta.TableMetadata
import io.github.xbeeant.batiskit.navigation.MapperXmlResolver
import javax.swing.Icon

/**
 * Mapper XML 参数提示的公共实现，XML 文本与注入的 SQL 片段共用。
 *
 * 相比旧的「只认识 `#{` 且只看 XmlText」实现，这里补齐了四类真实工程里最常见的缺口：
 *  - `#` / `$` 一敲出来就给候选（不必等到 `{`），并兼容 IntelliJ 的 SQL 注入；
 *  - `@Param` / 多参数的 `paramN` / `argN` 与真正的方法参数排在内置变量之前；
 *  - `<foreach item/index>` 按 `collection` 的泛型推导元素类型，`#{item.prop}` 逐段提示；
 *  - OGNL 属性（`test` / `collection` / `bind value`）与 `<bind>` 声明的变量也能提示。
 *
 * 解析规则与 [MapperXmlResolver]、[ParamBinding] 同源，避免补全和检查各说各话。
 */
internal object MapperParamCompletionSupport {

    private const val PRIORITY_PARAM = 100.0
    private const val PRIORITY_LOCAL = 90.0
    private const val PRIORITY_BUILTIN = 20.0
    private const val PRIORITY_CONTEXT = 10.0

    private val SQL_MARKERS = charArrayOf('#', '$')

    /** 自动弹窗判断：光标位置（可能来自注入 SQL）是否落在能给出参数候选的上下文 */
    fun isContext(position: PsiElement, offset: Int): Boolean {
        val file = position.containingFile as? XmlFile ?: return false
        if (file.rootTag?.name != "mapper") return false
        if (offset < 0 || offset > file.textLength) return false
        val anchor = file.findElementAt(offset) ?: position
        return placeOf(anchor, offset) != null
    }

    /** 真正添加候选。返回是否命中了参数上下文（供 contributor 决定是否保留平台候选） */
    fun complete(position: PsiElement, offset: Int, result: CompletionResultSet): Boolean {
        val file = position.containingFile as? XmlFile ?: return false
        if (file.rootTag?.name != "mapper") return false
        val place = placeOf(position, offset) ?: return false
        val method = MapperXmlResolver.method(place.statement) ?: return false
        val scope = Scope(file.project, method, place.statement, offset)
        val elements = if ('.' in place.typed) {
            scope.propertyVariants(place.typed)
        } else {
            scope.rootVariants(place, file)
        }
        if (elements.isEmpty()) return false
        // XML 默认前缀规则不认识 `#{abc` / OGNL 表达式，按已输入路径的最后一段过滤
        result.withPrefixMatcher(place.typed.substringAfterLast('.').trim()).addAllElements(elements)
        return true
    }

    // ------------------------------------------------------------------ 上下文定位

    private fun placeOf(position: PsiElement, offset: Int): Place? {
        val value = PsiTreeUtil.getParentOfType(position, XmlAttributeValue::class.java, false)
        if (value != null && offset in value.textRange.startOffset..value.textRange.endOffset) {
            val attribute = value.parent as? XmlAttribute ?: return null
            val tag = attribute.parent ?: return null
            val kind = when {
                attribute.localName == "test" -> Kind.OGNL
                attribute.localName == "collection" && tag.name == "foreach" -> Kind.COLLECTION
                attribute.localName == "value" && tag.name == "bind" -> Kind.OGNL
                attribute.localName == "item" && tag.name == "foreach" -> Kind.ITEM
                attribute.localName == "index" && tag.name == "foreach" -> Kind.INDEX
                else -> return null
            }
            val statement = statementOf(tag) ?: return null
            val typed = typedInValue(value, offset).trim()
            return Place(kind, typed, statement, before = tag.text.substringBefore(value.text))
        }

        val text = PsiTreeUtil.getParentOfType(position, XmlText::class.java, false) ?: return null
        val caret = (offset - text.textRange.startOffset).coerceIn(0, text.textLength)
        val statement = statementOf(text) ?: return null
        val before = text.text.substring(0, caret)
        val typed = expressionBefore(before)?.trim()?.removePrefix("{")?.trimStart() ?: return null
        // `#{id,jdbcType=...}` 的附加属性、`#{list[0]}` 的下标访问都不是参数路径
        if (typed.contains(',') || typed.contains('[')) return null
        return Place(Kind.SQL, typed, statement, before)
    }

    /** 光标所在的语句标签；`<sql>` 片段的参数由 include 它的语句决定，静态判断不了，放弃 */
    private fun statementOf(element: PsiElement): XmlTag? {
        var tag = PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false) ?: return null
        while (true) {
            if (MapperXmlResolver.isStatementTag(tag)) return tag
            if (tag.name == "mapper") return null
            tag = PsiTreeUtil.getParentOfType(tag, XmlTag::class.java) ?: return null
        }
    }

    /**
     * 光标前最近的 `#` / `$`，返回其后已输入的内容（可含 `{`）。
     * 遇到 `}` 或换行说明光标不在未完成的占位符里；遇到 SQL 运算符说明 `#` 是文字的一部分。
     */
    private fun expressionBefore(before: String): String? {
        var index = before.length - 1
        while (index >= 0) {
            val c = before[index]
            if (c == '}' || c == '\n' || c == '\r') return null
            if (c in SQL_MARKERS) {
                var start = index + 1
                if (start < before.length && before[start] == '{') start++
                return before.substring(start)
            }
            if (!(c.isLetterOrDigit() || c == '_' || c == '.' || c == '{' || c.isWhitespace())) return null
            index--
        }
        return null
    }

    /** 引号内已输入的前缀，与引用补全的同款截法 */
    private fun typedInValue(value: XmlAttributeValue, offset: Int): String {
        val inner = ElementManipulators.getValueTextRange(value)
        val relativeEnd = (offset - value.textRange.startOffset)
            .coerceAtLeast(inner.startOffset).coerceAtMost(inner.endOffset)
        return if (relativeEnd > inner.startOffset) value.text.substring(inner.startOffset, relativeEnd) else ""
    }

    // ------------------------------------------------------------------ 候选

    private enum class Kind { SQL, OGNL, COLLECTION, ITEM, INDEX }

    private data class Place(
        val kind: Kind,
        val typed: String,
        val statement: XmlTag,
        val before: String,
    )

    private data class Member(val name: String, val typeText: String)

    private data class Local(val typeText: String)

    /** 一次补全的解析范围：方法参数 + foreach / bind 局部变量的类型 */
    private class Scope(
        private val project: Project,
        val method: PsiMethod,
        private val statement: XmlTag,
        private val caretOffset: Int,
    ) {
        val params: Array<PsiParameter> = method.parameterList.parameters
        private val types = LinkedHashMap<String, PsiType?>()
        private val locals = LinkedHashMap<String, Local>()

        init {
            params.forEachIndexed { index, parameter ->
                ParamAnnotations.of(parameter)?.let { types[it] = parameter.type }
                val parameterName = parameter.name
                if (!parameterName.isNullOrBlank()) {
                    types[parameterName] = parameter.type
                }
                types["param${index + 1}"] = parameter.type
                types["arg$index"] = parameter.type
            }
            if (params.size == 1) {
                types["value"] = params[0].type
                types["_parameter"] = params[0].type
            }
            collectLocals()
        }

        fun rootVariants(place: Place, file: XmlFile): List<LookupElement> {
            val elements = mutableListOf<LookupElement>()
            // 单参数未注解：XML 里写的其实是 POJO 属性，参数名反而可能绑不上——属性排最前
            if (params.size == 1 && !ParamAnnotations.has(params[0]) && place.kind != Kind.COLLECTION) {
                val members = membersOf(params[0].type)
                members.forEach { elements += variant(it.name, it.typeText, AllIcons.Nodes.Field, PRIORITY_PARAM) }
                if (members.isEmpty()) {
                    elements += variant("value", params[0].type.presentableText, AllIcons.Nodes.Parameter, PRIORITY_PARAM)
                }
            }
            params.forEachIndexed { index, parameter ->
                val name = ParamAnnotations.of(parameter) ?: parameter.name
                if (name.isNullOrBlank()) return@forEachIndexed
                val typeText = parameter.type.presentableText
                elements += variant(name, typeText, AllIcons.Nodes.Parameter, PRIORITY_PARAM)
                elements += variant("param${index + 1}", typeText, AllIcons.Nodes.Parameter, PRIORITY_PARAM)
                elements += variant("arg$index", typeText, AllIcons.Nodes.Parameter, PRIORITY_PARAM)
            }
            locals.forEach { (name, local) ->
                elements += variant(name, local.typeText, AllIcons.Nodes.Variable, PRIORITY_LOCAL)
            }
            when (place.kind) {
                Kind.COLLECTION -> if (params.size == 1) {
                    listOf("list", "array", "collection").forEach { name ->
                        elements += variant(name, params[0].type.presentableText, AllIcons.Nodes.Variable, PRIORITY_LOCAL)
                    }
                }
                Kind.ITEM -> listOf("item", "row", "e", "entity").forEach { name ->
                    elements += variant(name, "foreach", AllIcons.Nodes.Variable, PRIORITY_LOCAL)
                }
                Kind.INDEX -> listOf("index", "i", "idx", "key").forEach { name ->
                    elements += variant(name, "foreach", AllIcons.Nodes.Variable, PRIORITY_LOCAL)
                }
                else -> Unit
            }
            ParamBinding.BUILTIN_NAMES.sorted().forEach { name ->
                elements += variant(name, "MyBatis", AllIcons.Nodes.Parameter, PRIORITY_BUILTIN)
            }
            if (place.kind == Kind.SQL && MapperAssistSettings.getInstance().state.sqlContextCompletionEnabled) {
                val namespace = file.rootTag?.getAttributeValue("namespace").orEmpty()
                val tableName = TableMappingResolver.tableNameFor(project, file, namespace)
                val columns = TableMetadata.columns(project, tableName.orEmpty(), file)
                MapperSqlRules.paramHints(place.before, columns).forEach { hint ->
                    elements += variant(hint, "SQL 上下文", AllIcons.Nodes.Field, PRIORITY_CONTEXT)
                }
            }
            return dedup(elements)
        }

        /** `user.addr.` → 逐段解析类型后列出最后一层的字段 / getter / 集合伪属性 */
        fun propertyVariants(typed: String): List<LookupElement> {
            val segments = typed.substringBeforeLast('.').split('.').filter { it.isNotBlank() }
            if (segments.isEmpty()) return emptyList()
            var type = types[segments[0]]
            segments.drop(1).forEach { segment ->
                type = type?.let { memberType(it, segment) }
            }
            val current = type ?: return emptyList()
            return membersOf(current).map { variant(it.name, it.typeText, AllIcons.Nodes.Field, PRIORITY_PARAM) }
                .let(::dedup)
        }

        private fun collectLocals() {
            val elementAtCaret = statement.containingFile.findElementAt(caretOffset) ?: statement
            val chain = mutableListOf<XmlTag>()
            var tag = PsiTreeUtil.getParentOfType(elementAtCaret, XmlTag::class.java, false)
            while (tag != null && tag !== statement) {
                chain += tag
                tag = PsiTreeUtil.getParentOfType(tag, XmlTag::class.java)
            }
            // 外层 foreach 先声明，里层才能引用它的 item
            chain.asReversed().forEach { foreach ->
                if (foreach.name != "foreach") return@forEach
                val collectionType = foreach.getAttributeValue("collection")?.trim()?.let(::resolveType)
                foreach.getAttributeValue("item")?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                    val type = componentType(collectionType, key = false)
                    types[name] = type
                    locals[name] = Local(type?.presentableText ?: "foreach")
                }
                foreach.getAttributeValue("index")?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                    val type = if (isMap(collectionType)) {
                        componentType(collectionType, key = true) ?: PsiTypes.intType()
                    } else {
                        PsiTypes.intType()
                    }
                    types[name] = type
                    locals[name] = Local(type.presentableText)
                }
            }
            // <bind> 是同级空标签，不会成为光标的祖先；只收集光标之前的声明
            PsiTreeUtil.findChildrenOfType(statement, XmlTag::class.java)
                .filter { it.name == "bind" && it.textRange.endOffset <= caretOffset }
                .forEach { bind ->
                    val name = bind.getAttributeValue("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return@forEach
                    val value = bind.getAttributeValue("value")?.trim()?.trim('"', '\'')
                    val type = value?.let(::resolveType)
                    types[name] = type
                    locals[name] = Local(type?.presentableText ?: "bind")
                }
        }

        /** 解析 OGNL 路径（支持 `list[0].name` 这种下标访问）到类型 */
        private fun resolveType(expression: String): PsiType? {
            val segments = expression.substringBefore('[').trim()
                .split('.').filter { it.isNotBlank() }
            if (segments.isEmpty()) return null
            var type: PsiType? = types[segments[0]] ?: return null
            segments.drop(1).forEach { segment ->
                type = type?.let { memberType(it, segment) }
            }
            return type
        }

        private fun memberType(type: PsiType, name: String): PsiType? {
            fieldsOf(type).firstOrNull { it.name == name }?.let { return it.type }
            val aClass = PsiTypesUtil.getPsiClass(type) ?: return null
            return aClass.allMethods.firstOrNull { method ->
                !method.hasModifierProperty(PsiModifier.STATIC) &&
                    method.parameterList.parametersCount == 0 &&
                    (method.name == name || getterName(method) == name)
            }?.returnType
        }

        private fun membersOf(type: PsiType?): List<Member> {
            if (type == null) return emptyList()
            val result = LinkedHashMap<String, Member>()
            fieldsOf(type).forEach { result.putIfAbsent(it.name, Member(it.name, it.type.presentableText)) }
            PsiTypesUtil.getPsiClass(type)?.allMethods?.forEach { method ->
                if (method.hasModifierProperty(PsiModifier.STATIC) || method.parameterList.parametersCount != 0) {
                    return@forEach
                }
                method.returnType?.let { returnType ->
                    getterName(method)?.let { name ->
                        result.putIfAbsent(name, Member(name, returnType.presentableText))
                    }
                }
            }
            // OGNL 对集合 / Map 的常用伪属性，参考 MybatisX 的 OgnlUtils
            if (isInheritor(type, "java.util.Map")) {
                result.putIfAbsent("size", Member("size", "int"))
                result.putIfAbsent("isEmpty", Member("isEmpty", "boolean"))
                result.putIfAbsent("keys", Member("keys", "Set"))
                result.putIfAbsent("values", Member("values", "Collection"))
            } else if (isInheritor(type, "java.util.Collection")) {
                result.putIfAbsent("size", Member("size", "int"))
                result.putIfAbsent("isEmpty", Member("isEmpty", "boolean"))
            }
            return result.values.toList()
        }

        private fun fieldsOf(type: PsiType): List<PsiField> {
            val aClass = PsiTypesUtil.getPsiClass(type) ?: return emptyList()
            return aClass.allFields.filter {
                !it.hasModifierProperty(PsiModifier.STATIC) && !it.name.contains('$')
            }
        }

        private fun getterName(method: PsiMethod): String? {
            val name = method.name
            return when {
                name.startsWith("get") && name.length > 3 -> decapitalize(name.substring(3))
                name.startsWith("is") && name.length > 2 && method.returnType == PsiTypes.booleanType() ->
                    decapitalize(name.substring(2))
                else -> null
            }
        }

        private fun decapitalize(name: String): String =
            if (name.length > 1 && name[0].isUpperCase() && name[1].isUpperCase()) name
            else name.replaceFirstChar { it.lowercase() }

        /** 取集合元素 / Map 值的泛型；index 变量取 Map 的 key 类型或数组下标 */
        private fun componentType(type: PsiType?, key: Boolean): PsiType? {
            if (type == null) return null
            if (type is PsiArrayType) return if (key) PsiTypes.intType() else type.componentType
            val classType = type as? PsiClassType ?: return null
            val resolved = classType.resolveGenerics()
            val aClass = resolved.element ?: return null
            fun argumentOf(fqn: String, index: Int): PsiType? {
                val iface = findClass(fqn) ?: return null
                if (!aClass.isEquivalentTo(iface) && !aClass.isInheritor(iface, true)) return null
                val substitutor = TypeConversionUtil.getSuperClassSubstitutor(iface, aClass, resolved.substitutor)
                return substitutor.substitute(iface.typeParameters[index])
            }
            return if (key) {
                argumentOf("java.util.Map", 0)
            } else {
                argumentOf("java.util.Collection", 0)
                    ?: argumentOf("java.lang.Iterable", 0)
                    ?: argumentOf("java.util.Map", 1)
            }
        }

        private fun isMap(type: PsiType?): Boolean = type != null && isInheritor(type, "java.util.Map")

        private fun isInheritor(type: PsiType, fqn: String): Boolean {
            val aClass = (type as? PsiClassType)?.resolve() ?: return false
            val parent = findClass(fqn) ?: return false
            return aClass.isEquivalentTo(parent) || aClass.isInheritor(parent, true)
        }

        private fun findClass(fqn: String): PsiClass? =
            JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
    }

    private fun variant(name: String, typeText: String, icon: Icon, priority: Double): LookupElement =
        PrioritizedLookupElement.withPriority(
            LookupElementBuilder.create(name).withIcon(icon).withTypeText(typeText, true),
            priority,
        )

    private fun dedup(elements: List<LookupElement>): List<LookupElement> =
        elements.distinctBy { it.lookupString }
}
