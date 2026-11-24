package io.github.xbeeant.batiskit.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbService
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.PsiTypesUtil
import com.intellij.psi.xml.XmlFile
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
 * Mapper XML 里 `#{}` / `${}` 的参数补全：输入 `#{` / `${`（或 `.` 之后继续输入）时，
 * 把当前语句对应接口方法支持的参数列出来，与 [io.github.xbeeant.batiskit.inspect.MapperParameterInspection]
 * 的判定口径完全同源（同一套 [MapperXmlResolver] 定位 + [ParamBinding] 命名规则）。
 *
 * 候选分两层：
 *  - **根名字**：`@Param` 值 / 参数名、`param1..N` / `arg0..N-1`、`_parameter` / `_databaseId`、
 *    `<foreach item|index>` 与 `<bind name>` 声明的局部变量；方法只有一个参数且没写 `@Param` 时
 *    （检查里静态判断不了的那种），把该参数类型的字段（POJO 属性）也列出来——这正是这种场景下
 *    XML 里实际会写的名字；单个简单类型参数则给 `#{value}`；
 *  - **属性路径**：`#{user.` 之后列出该参数类型的实例字段（含继承来的），`#{param1.addr.city}`
 *    这类多层路径逐段解析。
 *
 * 弹出时机：在语句文本里输入 `{` 时自动弹出（[invokeAutoPopup]）——`#` / `$` 之后跟上 `{`
 * 即 `#{` / `${` 就能看到参数清单；`#{us` 这类已输入前缀按普通前缀匹配过滤。
 */
class MapperParamCompletionContributor : CompletionContributor() {

    /** 在 Mapper 语句文本里输入 `{` 时自动弹出参数清单（跟在 `#` / `$` 后面就是 `#{` / `${`） */
    override fun invokeAutoPopup(position: PsiElement, typeChar: Char): Boolean {
        if (typeChar != '{') {
            return false
        }
        val text = PsiTreeUtil.getParentOfType(position, XmlText::class.java, false) ?: return false
        return MapperXmlResolver.statementOf(text) != null
    }

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        if (DumbService.isDumb(parameters.originalFile.project)) {
            return
        }
        val text = parameters.originalPosition
            ?.let { PsiTreeUtil.getParentOfType(it, XmlText::class.java, false) }
            ?: return
        val statement = MapperXmlResolver.statementOf(text) ?: return
        val method = MapperXmlResolver.method(statement) ?: return

        val caret = parameters.offset - text.textRange.startOffset
        if (caret < 0 || caret > text.textLength) {
            return
        }
        val bodyStart = openExpression(text.text, caret)?.bodyStart ?: return
        val typed = text.text.substring(bodyStart, caret)
        // `#{id,jdbcType=...}` 的附加属性与 `#{list[0]}` 的下标访问不属于参数路径，不在补全范围
        if (typed.contains(',') || typed.contains('[')) {
            return
        }

        val elements = if (typed.contains('.')) {
            propertyVariants(method, typed)
        } else {
            rootVariants(
                method,
                MapperXmlResolver.eachVarsOf(text, statement),
                parameters.originalFile as? XmlFile,
                text.text.substring(0, caret)
            )
        }
        if (elements.isNotEmpty()) {
            // 用表达式内已输入的前缀做匹配：XML 默认前缀规则不认识 `#{` 里的内容
            result.withPrefixMatcher(typed.substringAfterLast('.')).addAllElements(elements)
        }
    }

    // ------------------------------------------------------------------ 候选构造

    /** 根名字候选：参数名（含 @Param 值）、paramN / argN、内置变量、foreach / bind 局部变量、单参数 POJO 的属性 */
    private fun rootVariants(
        method: PsiMethod,
        eachVars: Set<String>,
        xmlFile: XmlFile?,
        sqlBeforeCaret: String,
    ): List<LookupElement> {
        val parameters = method.parameterList.parameters
        val mapperParams = parameters.map { MapperParam(it.name.orEmpty(), ParamAnnotations.of(it)) }
        val context = ParamBinding.context(mapperParams, eachVars)

        val elements = mutableListOf<LookupElement>()
        // 单参数未注解：XML 里写的其实是 POJO 属性，参数名反而可能绑不上——属性排最前
        val single = if (!context.checkable && parameters.size == 1) parameters[0] else null
        single?.let { parameter ->
            val properties = propertiesOf(parameter.type)
            properties.forEach { (name, typeText) ->
                elements += variant(name, typeText, AllIcons.Nodes.Field)
            }
            if (properties.isEmpty()) {
                // 单个简单类型参数：MyBatis 约定 #{value} 即取参数本身
                elements += variant("value", parameter.type.presentableText, AllIcons.Nodes.Parameter)
            }
        }
        context.suggestionNames.forEach { name ->
            val index = mapperParams.indexOfFirst { it.xmlName == name }
            val typeText = parameters.getOrNull(index)?.type?.presentableText.orEmpty()
            elements += variant(name, typeText, AllIcons.Nodes.Parameter)
        }
        eachVars.sorted().forEach { name ->
            elements += variant(name, "foreach / bind", AllIcons.Nodes.Variable)
        }
        context.displayNames.filterNot { it in context.suggestionNames }.forEach { name ->
            // paramN / argN 按下标对回参数，把类型展示出来
            val index = paramIndexOf(name)
            val typeText = index?.let { parameters.getOrNull(it)?.type?.presentableText }.orEmpty()
            elements += variant(name, typeText, AllIcons.Nodes.Parameter)
        }
        ParamBinding.BUILTIN_NAMES.sorted().forEach { name ->
            elements += variant(name, "MyBatis", AllIcons.Nodes.Parameter)
        }
        // SQL 上下文：例如 `where user_name = #{` 时把 userName 顶上来；与上面的参数候选按名字去重，
        // 只在参数补全没覆盖到时额外出现，不会让同一个名字列两条。
        if (MapperAssistSettings.getInstance().state.sqlContextCompletionEnabled && xmlFile != null) {
            val namespace = xmlFile.rootTag?.getAttributeValue("namespace").orEmpty()
            val tableName = TableMappingResolver.tableNameFor(method.project, xmlFile, namespace)
            val columns = TableMetadata.columns(method.project, tableName.orEmpty(), xmlFile)
            MapperSqlRules.paramHints(sqlBeforeCaret, columns).forEach { hint ->
                elements += variant(hint, "SQL 上下文", AllIcons.Nodes.Field)
            }
        }
        return dedup(elements)
    }

    /** 属性路径候选：`#{user.addr.` → 逐段解析字段类型，列出最后一段类型的实例字段 */
    private fun propertyVariants(method: PsiMethod, typed: String): List<LookupElement> {
        val path = typed.substringBeforeLast('.')
        val segments = path.split('.').filter { it.isNotBlank() }
        if (segments.isEmpty()) {
            return emptyList()
        }
        var type: PsiType? = resolveRoot(segments[0], method)
        segments.drop(1).forEach { segment ->
            type = type?.let { current -> fieldOf(current, segment)?.type }
        }
        val current = type ?: return emptyList()
        return propertiesOf(current).map { (name, typeText) ->
            variant(name, typeText, AllIcons.Nodes.Field)
        }
    }

    /** 根名字 → 参数类型（paramN / argN / xmlName / 单参数的 `_parameter` 与属性名都能对上） */
    private fun resolveRoot(root: String, method: PsiMethod): PsiType? {
        val parameters = method.parameterList.parameters
        parameters.forEachIndexed { index, parameter ->
            if (ParamAnnotations.of(parameter) == root || root == "param${index + 1}" || root == "arg$index") {
                return parameter.type
            }
        }
        if (parameters.size == 1) {
            val parameter = parameters[0]
            if (root == "_parameter") {
                return parameter.type
            }
            if (!ParamAnnotations.has(parameter)) {
                // 单参数未注解：根名字是它的属性
                return fieldOf(parameter.type, root)?.type
            }
        }
        return null
    }

    // ------------------------------------------------------------------ PSI 辅助

    /**
     * 光标前最近的未闭合 `#{` / `${`：返回其正文起点（`{` 之后）。
     * 中间出现 `}` 说明最近的表达式已闭合，光标不在占位符里，返回 null。
     */
    private fun openExpression(text: String, caret: Int): OpenExpr? {
        var index = caret - 1
        while (index >= 0) {
            val c = text[index]
            if (c == '}') {
                return null
            }
            if (c == '{' && index > 0 && (text[index - 1] == '#' || text[index - 1] == '$')) {
                return OpenExpr(index + 1)
            }
            index--
        }
        return null
    }

    /** 类型里的实例字段（含继承；跳过静态与合成字段），配字段类型文本 */
    private fun propertiesOf(type: PsiType): List<Pair<String, String>> =
        instanceFields(type).map { it.name to it.type.presentableText }

    private fun fieldOf(type: PsiType, name: String): PsiField? =
        instanceFields(type).firstOrNull { it.name == name }

    private fun instanceFields(type: PsiType): List<PsiField> {
        val aClass = PsiTypesUtil.getPsiClass(type) ?: return emptyList()
        return aClass.allFields
            .filter { !it.hasModifierProperty(PsiModifier.STATIC) && !it.name.contains('$') }
            .toList()
    }

    private fun variant(name: String, typeText: String, icon: Icon): LookupElement =
        LookupElementBuilder.create(name)
            .withIcon(icon)
            .withTypeText(typeText, true)

    private fun dedup(elements: List<LookupElement>): List<LookupElement> =
        elements.distinctBy { it.lookupString }

    /** "param2" / "arg1" → 参数下标，其余 null */
    private fun paramIndexOf(name: String): Int? = when {
        name.startsWith("param") -> name.removePrefix("param").toIntOrNull()?.let { if (it >= 1) it - 1 else null }
        name.startsWith("arg") -> name.removePrefix("arg").toIntOrNull()
        else -> null
    }

    /** 一个尚未写完的 `#{` 表达式：bodyStart 是正文起点（光标之前的部分就是已输入内容） */
    private data class OpenExpr(val bodyStart: Int)
}
