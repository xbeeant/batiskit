package io.github.xbeeant.batiskit.completion

import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.ElementManipulators
import com.intellij.psi.PsiElement
import com.intellij.psi.impl.search.AllClassesSearchExecutor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlComment
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import io.github.xbeeant.batiskit.inspect.ParamAnnotations
import io.github.xbeeant.batiskit.navigation.MapperXmlResolver
import io.github.xbeeant.batiskit.util.MapperTypeHints

/**
 * MyBatis 专属语法补全。
 *
 *  - `#{prop,` → `jdbcType=` / `mode=` / `typeHandler=` / `numericScale=` / `resultMap=`；
 *    `jdbcType=` / `mode=` 继续给枚举值，`typeHandler=` 给 Java 类名，`resultMap=` 给本文件的 resultMap id；
 *  - 语句标签上的枚举属性（`statementType` / `resultSetType` / `<selectKey order>`）；
 *  - `<result property>` / `keyProperty` 按 resultMap 的 `type`、方法参数类型提示 Java 属性；
 *  - 输入 `<` 时提示 MyBatis 动态标签，并直接补全开闭标签。
 *
 * 所有判断都基于宿主 XML 的偏移，注入 SQL 场景先由 [MapperCompletionHost] 映射回来。
 */
class MapperSyntaxCompletionContributor : CompletionContributor() {

    @Suppress("OVERRIDE_DEPRECATION")
    override fun invokeAutoPopup(position: PsiElement, typeChar: Char): Boolean {
        if (typeChar !in charArrayOf(',', '=', '"', '<')) {
            return false
        }
        val (host, offset) = MapperCompletionHost.positionOf(position, position.textOffset)
        return contextOf(host, offset) != null
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (DumbService.isDumb(parameters.originalFile.project)) {
            return
        }
        val position = parameters.originalPosition ?: parameters.position
        val (host, offset) = MapperCompletionHost.positionOf(position, parameters.offset)
        val context = contextOf(host, offset) ?: return
        val elements = when (context) {
            is Context.OptionKey -> PARAM_OPTIONS.map { (key, values) ->
                LookupElementBuilder.create("$key=")
                    .withIcon(AllIcons.Nodes.Property)
                    .withTypeText(if (values.isEmpty()) "MyBatis" else values.joinToString(" / "), true)
                    .withInsertHandler(if (values.isEmpty()) null else RE_COMPLETE)
            }
            is Context.OptionValue -> optionValueVariants(parameters.originalFile.project, context)
            is Context.AttributeValue -> attributeVariants(parameters, context)
            is Context.TagName -> DYNAMIC_TAGS.map { name ->
                LookupElementBuilder.create(name)
                    .withTypeText("MyBatis 动态标签", true)
                    .withInsertHandler(tagInsertHandler(context.startOffset))
            }
        }
        val prefix = if (context is Context.OptionValue && context.key == "typeHandler") {
            context.prefix.substringAfterLast('.')
        } else {
            context.prefix
        }
        result.withPrefixMatcher(prefix).addAllElements(elements)
    }

    // ------------------------------------------------------------------ 上下文识别

    private sealed interface Context {
        val prefix: String

        /** `#{prop,` 之后正在输入选项名 */
        data class OptionKey(override val prefix: String) : Context

        /** `#{prop,key=value` 之后正在输入值 */
        data class OptionValue(
            val key: String,
            override val prefix: String,
            val anchor: XmlTag,
        ) : Context

        /** XML 属性引号内正在输入值 */
        data class AttributeValue(val name: String, override val prefix: String, val tag: XmlTag) : Context

        /** 输入 `<` 或 `<部分标签名` */
        data class TagName(override val prefix: String, val startOffset: Int) : Context
    }

    private fun contextOf(position: PsiElement, offset: Int): Context? {
        val file = position.containingFile as? XmlFile ?: return null
        if (file.rootTag?.name != "mapper") return null

        val value = PsiTreeUtil.getParentOfType(position, XmlAttributeValue::class.java, false)
        if (value == null) {
            tagContext(file, position, offset)?.let { return it }
        }
        if (value != null && offset in value.textRange.startOffset..value.textRange.endOffset) {
            val attribute = value.parent as? XmlAttribute ?: return null
            val tag = attribute.parent ?: return null
            return Context.AttributeValue(attribute.localName, typedInValue(value, offset), tag)
        }

        val text = PsiTreeUtil.getParentOfType(position, XmlText::class.java, false) ?: return null
        val caret = (offset - text.textRange.startOffset).coerceIn(0, text.textLength)
        val body = openParameterBody(text.text.substring(0, caret)) ?: return null
        val comma = body.lastIndexOf(',')
        if (comma < 0) return null
        val tail = body.substring(comma + 1).trimStart()
        val equals = tail.indexOf('=')
        if (equals < 0) {
            return Context.OptionKey(tail.trim())
        }
        val key = tail.substring(0, equals).trim()
        if (key !in PARAM_OPTIONS) return null
        val anchor = PsiTreeUtil.getParentOfType(text, XmlTag::class.java, false) ?: return null
        return Context.OptionValue(key, tail.substring(equals + 1).trim(), anchor)
    }

    /** `<` 起手：只处理 mapper / 语句内部的开标签，注释里的 `<` 不算 */
    private fun tagContext(file: XmlFile, position: PsiElement, offset: Int): Context.TagName? {
        if (PsiTreeUtil.getParentOfType(position, XmlComment::class.java, false) != null) {
            return null
        }
        val raw = file.text
        var start = offset - 1
        while (start >= 0 && (raw[start].isLetterOrDigit() || raw[start] == '_')) {
            start--
        }
        if (start < 0 || raw[start] != '<' || raw.getOrNull(start + 1) == '/') {
            return null
        }
        var tag = PsiTreeUtil.getParentOfType(position, XmlTag::class.java, false)
        while (tag != null && !MapperXmlResolver.isStatementTag(tag) && tag.name != "mapper") {
            tag = PsiTreeUtil.getParentOfType(tag, XmlTag::class.java)
        }
        return tag?.let { Context.TagName(raw.substring(start + 1, offset), start) }
    }

    /** 光标前未闭合的 `#{...}` 体；遇到 `}` / 换行即认为不在占位符里 */
    private fun openParameterBody(before: String): String? {
        var index = before.length - 1
        while (index >= 0) {
            val c = before[index]
            if (c == '}' || c == '\n' || c == '\r') return null
            if (c == '{' && index > 0 && before[index - 1] == '#') {
                return before.substring(index + 1)
            }
            index--
        }
        return null
    }

    private fun typedInValue(value: XmlAttributeValue, offset: Int): String {
        val inner = ElementManipulators.getValueTextRange(value)
        val relative = offset - value.textRange.startOffset
        return if (relative > inner.startOffset && relative <= inner.endOffset) {
            value.text.substring(inner.startOffset, relative).trim()
        } else {
            ""
        }
    }

    // ------------------------------------------------------------------ 候选生成

    private fun optionValueVariants(
        project: Project,
        context: Context.OptionValue,
    ): List<LookupElement> = when (context.key) {
        "typeHandler" -> classVariants(project, context.prefix)
        "resultMap" -> MapperXmlResolver.resultMapIdVariants(context.anchor).map(::enumVariant)
        else -> PARAM_OPTIONS[context.key].orEmpty().map(::enumVariant)
    }

    /** `typeHandler=` 的类候选：短类名作为 lookupString，FQN 作为附加匹配串 */
    private fun classVariants(project: Project, prefix: String): List<LookupElement> {
        val elements = mutableListOf<LookupElement>()
        val simplePrefix = prefix.substringAfterLast('.')
        AllClassesSearchExecutor.processClassNames(project, GlobalSearchScope.allScope(project)) { fqn ->
            if (elements.size >= MAX_CLASS_VARIANTS) {
                return@processClassNames false
            }
            if (prefix.isEmpty() || fqn.startsWith(prefix, true) ||
                fqn.substringAfterLast('.').startsWith(simplePrefix, true)
            ) {
                elements += LookupElementBuilder.create(fqn.substringAfterLast('.'))
                    .withLookupString(fqn)
                    .withIcon(AllIcons.Nodes.Class)
                    .withTypeText(fqn.substringBeforeLast('.', ""), true)
            }
            true
        }
        return elements
    }

    private fun attributeVariants(parameters: CompletionParameters, context: Context.AttributeValue): List<LookupElement> =
        when (context.name) {
            "property" -> resultPropertyVariants(parameters.originalFile.project, context.tag)
            "keyProperty" -> keyPropertyVariants(parameters.originalFile.project, context.tag)
            else -> ATTRIBUTE_VALUES[context.name].orEmpty().map(::enumVariant)
        }

    /** `<result property>`：取最近的 resultMap 的 type，嵌套 association/collection 优先取自身 javaType */
    private fun resultPropertyVariants(project: Project, tag: XmlTag): List<LookupElement> {
        var current: XmlTag? = tag
        while (current != null) {
            val typeName = when (current.name) {
                "association", "collection" -> current.getAttributeValue("javaType")
                "resultMap" -> current.getAttributeValue("type")
                else -> null
            }
            if (!typeName.isNullOrBlank()) {
                return MapperTypeHints.members(MapperTypeHints.typeOf(project, typeName)).map(::memberVariant)
            }
            current = PsiTreeUtil.getParentOfType(current, XmlTag::class.java)
        }
        return emptyList()
    }

    /** `keyProperty`：按方法参数类型给出字段；多参数时用 @Param / paramN 前缀 */
    private fun keyPropertyVariants(project: Project, tag: XmlTag): List<LookupElement> {
        var statement: XmlTag? = tag
        while (statement != null && !MapperXmlResolver.isStatementTag(statement)) {
            statement = PsiTreeUtil.getParentOfType(statement, XmlTag::class.java)
        }
        val method = statement?.let(MapperXmlResolver::method) ?: return emptyList()
        val params = method.parameterList.parameters
        val elements = mutableListOf<LookupElement>()
        params.forEachIndexed { index, parameter ->
            val annotated = ParamAnnotations.of(parameter)
            val members = MapperTypeHints.members(parameter.type)
            if (params.size == 1 && annotated == null) {
                members.forEach { elements += memberVariant(it) }
            } else {
                val prefix = annotated ?: "param${index + 1}"
                elements += LookupElementBuilder.create(prefix)
                    .withIcon(AllIcons.Nodes.Parameter)
                    .withTypeText(parameter.type.presentableText, true)
                members.forEach { member ->
                    elements += memberVariant(MapperTypeHints.Member("$prefix.${member.name}", member.typeText))
                }
            }
        }
        return elements
    }

    private fun enumVariant(value: String): LookupElement =
        LookupElementBuilder.create(value).withIcon(AllIcons.Nodes.Enum)

    private fun memberVariant(member: MapperTypeHints.Member): LookupElement =
        LookupElementBuilder.create(member.name)
            .withIcon(AllIcons.Nodes.Field)
            .withTypeText(member.typeText, true)

    /** 把 `<fo` 替换为完整的 `<foreach></foreach>`，光标落在标签内部 */
    private fun tagInsertHandler(startOffset: Int): InsertHandler<LookupElement> = InsertHandler { context, item ->
        val name = item.lookupString
        val (replacement, caretShift) = when (name) {
            "bind" -> "<bind name=\"\" value=\"\"/>" to "<bind name=\"".length
            "include" -> "<include refid=\"\"/>" to "<include refid=\"".length
            else -> "<$name></$name>" to (name.length + 2)
        }
        WriteCommandAction.runWriteCommandAction(context.project) {
            context.document.replaceString(startOffset, context.tailOffset, replacement)
            context.editor.caretModel.moveToOffset(startOffset + caretShift)
        }
    }

    companion object {
        /** 选中 `jdbcType=` 后自动再弹一次，直接给出候选值 */
        private val RE_COMPLETE = InsertHandler<LookupElement> { context, _ ->
            context.setLaterRunnable {
                CodeCompletionHandlerBase.createHandler(CompletionType.BASIC)
                    .invokeCompletion(context.project, context.editor, 1, true)
            }
        }

        /** 与 MyBatis `org.apache.ibatis.type.JdbcType` 一致；顺序保持枚举声明顺序 */
        private val JDBC_TYPES = listOf(
            "BIT", "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "FLOAT", "DOUBLE", "NUMERIC", "DECIMAL",
            "DATE", "TIME", "TIMESTAMP", "VARCHAR", "CHAR", "LONGVARCHAR", "NULL", "OTHER", "BLOB", "CLOB",
            "BOOLEAN", "CURSOR", "UNDEFINED", "NVARCHAR", "NCHAR", "NCLOB", "ARRAY", "STRUCT", "REF",
            "JAVA_OBJECT", "DISTINCT",
        )

        /** 键 → 可选枚举值；空列表的选项名仍可补全，只是值由类 / resultMap 引用决定 */
        private val PARAM_OPTIONS = linkedMapOf(
            "jdbcType" to JDBC_TYPES,
            "mode" to listOf("IN", "OUT", "INOUT"),
            "typeHandler" to emptyList(),
            "numericScale" to emptyList(),
            "resultMap" to emptyList(),
        )

        private val ATTRIBUTE_VALUES = mapOf(
            "statementType" to listOf("STATEMENT", "PREPARED", "CALLABLE"),
            "resultSetType" to listOf("FORWARD_ONLY", "SCROLL_INSENSITIVE", "SCROLL_SENSITIVE"),
            "order" to listOf("BEFORE", "AFTER"),
        )

        private const val MAX_CLASS_VARIANTS = 200

        private val DYNAMIC_TAGS = listOf(
            "if", "choose", "when", "otherwise", "foreach", "where", "set", "trim", "bind", "include",
            "sql", "selectKey",
        )
    }
}
