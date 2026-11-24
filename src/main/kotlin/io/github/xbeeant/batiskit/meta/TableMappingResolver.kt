package io.github.xbeeant.batiskit.meta

import com.intellij.openapi.project.Project
import com.intellij.psi.xml.XmlFile
import io.github.xbeeant.batiskit.config.MapperAssistSettings

/**
 * XML ↔ 表 的映射推断（优先级从高到低）：
 *
 *  1. 设置页里手写的 `namespace / Mapper 简单名 = 表名`（推断不准时的最终手段）；
 *  2. 当前 Mapper XML 里已有 SQL 的 `from / into / update / join` 表名；
 *  3. XML 的 `resultType` / `parameterType` / `<resultMap type>` 指向的实体类上的 `@TableName`；
 *  4. 调用方额外给的实体 FQN（例如接口方法返回类型）上的 `@TableName`；
 *  5. namespace 简单名去掉 Mapper / Dao 后缀后转下划线。
 *
 * 全程只读索引与 PSI，不连数据库；推断不出来返回 null，调用方退化成旧行为。
 */
object TableMappingResolver {

    fun tableNameFor(
        project: Project,
        file: XmlFile?,
        namespace: String,
        extraHints: List<String> = emptyList(),
    ): String? {
        val state = MapperAssistSettings.getInstance().state
        val fileName = file?.name.orEmpty()
        MapperSqlRules.mappingFor(namespace, fileName, MapperSqlRules.parseMappings(state.tableMappings))
            ?.let { return it }

        MapperSqlRules.parseSql(sqlTextOf(file)).tables.firstOrNull()?.let { return it }

        val typeNames = mutableListOf<String>()
        file?.rootTag?.subTags?.forEach { tag ->
            listOf("resultType", "parameterType", "type").forEach { attribute ->
                tag.getAttributeValue(attribute)?.trim()?.takeIf { it.isNotEmpty() }?.let { typeNames += it }
            }
            tag.subTags.forEach { child ->
                child.getAttributeValue("javaType")?.trim()?.takeIf { it.isNotEmpty() }?.let { typeNames += it }
            }
        }
        typeNames += extraHints
        typeNames.distinct().forEach { fqn ->
            val annotated = TableMetadata.annotatedTableName(project, fqn) ?: return@forEach
            val safe = MapperSqlRules.sanitizeIdentifier(annotated)
            if (safe.isNotEmpty()) return safe
        }

        val simple = namespace.substringAfterLast('.')
        return MapperSqlRules.inferTableName(simple)
    }

    /** 当前 XML 里所有增删改查语句与 `<sql>` 片段的正文（含标签，交由 parseSql 清洗） */
    fun sqlTextOf(file: XmlFile?): String {
        val root = file?.rootTag ?: return ""
        if (root.name != "mapper") return ""
        return buildString {
            root.subTags.forEach { tag ->
                if (tag.name in setOf("select", "insert", "update", "delete", "sql")) {
                    append(tag.text).append('\n')
                }
            }
        }
    }

    /** 最近使用的表名（设置页展示、无映射时的兜底候选） */
    fun recentTables(): List<String> = MapperAssistSettings.getInstance().state.recentTables.toList()
}
