package io.github.xbeeant.batiskit.completion

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbService
import com.intellij.psi.ElementManipulators
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText
import io.github.xbeeant.batiskit.config.MapperAssistSettings
import io.github.xbeeant.batiskit.meta.MapperSqlRules
import io.github.xbeeant.batiskit.meta.SqlSemantics
import io.github.xbeeant.batiskit.meta.TableMappingResolver
import io.github.xbeeant.batiskit.meta.TableMetadata
import io.github.xbeeant.batiskit.navigation.MapperXmlResolver

/**
 * Mapper XML 的表字段补全，覆盖两类位置：
 *
 *  - `column="…"` 属性（`<resultMap>` 的 `<id>` / `<result>`、以及动态 SQL 的属性）；
 *  - 语句正文里的 SQL 标识符：已经输入至少一个字符才弹候选，避免空前缀刷屏。
 *
 * 列清单来自 [TableMetadata] 的四路合并（IDE 已配置数据源的实时表结构 / 离线列快照 /
 * 工程实体类 / 当前 resultMap），表名来自 [TableMappingResolver]。只要 Database 视图里有数据源
 * 就能自动按表名找到列，首次使用无需先手动选表。没有推断出表名时仍会用 resultMap 里已知的列，
 * 一条都没有就安静退出，不影响平台原有补全。
 *
 * `#{` / `${` 内部不归这里管：那属于 [MapperParamCompletionContributor] 的参数补全
 * （本轮起它会按 SQL 上下文追加列名候选）。
 */
class MapperColumnCompletionContributor : CompletionContributor() {

    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val file = parameters.originalFile as? XmlFile ?: return
        val root = file.rootTag ?: return
        if (root.name != "mapper") return
        val state = MapperAssistSettings.getInstance().state
        if (!state.columnCompletionEnabled || DumbService.isDumb(file.project)) return
        val position = parameters.originalPosition ?: return

        val attributeValue = PsiTreeUtil.getParentOfType(position, XmlAttributeValue::class.java, false)
        if (attributeValue != null) {
            val attribute = attributeValue.parent as? XmlAttribute ?: return
            if (attribute.localName != "column") return
            val typed = typedInValue(attributeValue, parameters.offset)
            addColumns(result, typed, file, null)
            return
        }

        val text = PsiTreeUtil.getParentOfType(position, XmlText::class.java, false) ?: return
        val statement = MapperXmlResolver.statementOf(text)
            ?: PsiTreeUtil.getParentOfType(text, XmlTag::class.java)?.takeIf { it.name == "sql" }
            ?: return
        val caret = (parameters.offset - text.textRange.startOffset).coerceIn(0, text.textLength)
        val before = text.text.substring(0, caret)
        if (openExpression(before) != null) return
        val typed = WORD_AT_END.find(before)?.value.orEmpty()
        // 「别名.」之后才启用语义层：用别名解析出的表名取列，其余位置逐字沿用原逻辑
        val aliasPrefix = SqlSemantics.aliasPrefixAt(before, before.length)
        if (typed.isEmpty() && aliasPrefix == null) return
        val ownerTable = aliasPrefix?.let { alias ->
            val sqlText = statement.text.ifBlank { TableMappingResolver.sqlTextOf(file) }
            SqlSemantics.ownerOf(alias, SqlSemantics.parse(sqlText))
        }
        addColumns(result, typed, file, statement, ownerTable)
    }

    private fun addColumns(
        result: CompletionResultSet,
        typed: String,
        file: XmlFile,
        statement: XmlTag?,
        ownerTable: String? = null,
    ) {
        val namespace = file.rootTag?.getAttributeValue("namespace").orEmpty()
        val mappedTable = TableMappingResolver.tableNameFor(file.project, file, namespace)
        // 别名解析出的表名优先；解析不到时安静回落到默认映射表（原行为）
        val tableName = ownerTable?.takeIf { it.isNotBlank() } ?: mappedTable
        val columns = TableMetadata.columns(file.project, tableName.orEmpty(), file)
        if (columns.isEmpty()) return
        val context = MapperSqlRules.parseSql(statement?.text ?: TableMappingResolver.sqlTextOf(file))
        val ordered = MapperSqlRules.columnOrder(columns, context)
        val source = tableName?.takeIf { it.isNotEmpty() } ?: "resultMap"
        val matcher = result.withPrefixMatcher(typed)
        matcher.addAllElements(
            ordered.map { column ->
                LookupElementBuilder.create(column.name)
                    .withIcon(if (column.primaryKey) AllIcons.Nodes.FieldPK else AllIcons.Nodes.Field)
                    .withTypeText(column.jdbcType.ifBlank { source }, true)
                    .withTailText(column.comment.take(32).let { if (it.isBlank()) "" else "  $it" }, true)
            }
        )
    }

    /** 引号内已输入的前缀（与 MapperSqlRefidCompletionContributor 同款截法） */
    private fun typedInValue(value: XmlAttributeValue, offset: Int): String {
        val inner = ElementManipulators.getValueTextRange(value)
        val relativeEnd = (offset - value.textRange.startOffset)
            .coerceAtLeast(inner.startOffset)
            .coerceAtMost(inner.endOffset)
        return if (relativeEnd > inner.startOffset) value.text.substring(inner.startOffset, relativeEnd) else ""
    }

    /** 光标前最近的未闭合 `#{` / `${`：非空说明在占位符内部，列补全要让位 */
    private fun openExpression(before: String): Int? {
        var index = before.length - 1
        while (index >= 0) {
            val ch = before[index]
            if (ch == '}') return null
            if (ch == '{' && index > 0 && (before[index - 1] == '#' || before[index - 1] == '$')) return index + 1
            index--
        }
        return null
    }

    private companion object {
        val WORD_AT_END = Regex("""[A-Za-z_][A-Za-z0-9_$]*$""")
    }
}
