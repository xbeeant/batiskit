package io.github.xbeeant.batiskit.meta

import com.intellij.database.psi.DbTable
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import io.github.xbeeant.batiskit.config.ColumnSnapshot
import io.github.xbeeant.batiskit.config.MapperAssistSettings
import io.github.xbeeant.batiskit.config.TableColumnSnapshot
import io.github.xbeeant.batiskit.model.TableInfo

/**
 * 表字段元数据的统一入口，按「可信度从高到低」合并四路来源：
 *
 *  1. **IDE 已配置数据源里的实时表结构**（[DbTableLocator]）：只要 Database 视图里有数据源就按表名
 *     主动查找，**不需要用户先点生成代码 / 比对代码选表**；不连 JDBC、不读配置文件、不存凭据；
 *  2. **列结构快照**：用户在 Database 视图里选中表时记下的离线副本，数据源不可用或断开时兜底；
 *  3. **工程内实体类**：按表名找同名 Java 类，字段按 `@TableField` / 驼峰转下划线还原列名；
 *  4. **当前 Mapper XML 的 `<resultMap>`**：即便一个数据源都没配、也没找到实体类，也能给出已知列。
 *
 * 四路都没有时返回空列表，调用方退化成不弹字段候选，绝不阻塞补全。
 */
object TableMetadata {

    /** 记录一次「用户选中表」事件：刷新列快照、最近使用表与数据源 / schema 记忆 */
    fun remember(info: TableInfo, dataSource: String = "", schema: String = "") {
        val name = MapperSqlRules.sanitizeIdentifier(info.tableName)
        if (name.isEmpty()) return
        val state = MapperAssistSettings.getInstance().state
        val snapshot = TableColumnSnapshot().apply {
            this.name = name
            this.dataSource = dataSource.take(120)
            this.schema = schema.take(120)
            columns = info.columns.map { column ->
                ColumnSnapshot().apply {
                    this.name = MapperSqlRules.sanitizeIdentifier(column.columnName)
                    jdbcType = column.javaType.jdbcType
                    comment = column.comment.take(120)
                    primaryKey = column.primaryKey
                }
            }.filter { it.name.isNotEmpty() }.take(MapperSqlRules.MAX_COLUMNS).toMutableList()
        }
        state.tableSnapshots = (
            listOf(snapshot) + state.tableSnapshots.filterNot { it.name.equals(name, ignoreCase = true) }
            ).take(MapperSqlRules.MAX_SNAPSHOTS).toMutableList()
        state.recentTables = MapperSqlRules.remember(state.recentTables, name).toMutableList()
        if (dataSource.isNotBlank()) state.lastDataSource = dataSource.take(120)
        if (schema.isNotBlank()) state.lastSchema = schema.take(120)
        state.normalize()
    }

    /** 从 Database 视图元素上取数据源名与 schema 名（PSI 版本差异大，读不到就留空） */
    fun namesOf(table: DbTable): Pair<String, String> {
        val dataSource = runCatching { table.dataSource.name }.getOrNull().orEmpty()
        val schema = runCatching { (table.parent as? PsiNamedElement)?.name }.getOrNull().orEmpty()
        return dataSource to schema
    }

    /** 批量记录（代码生成 / 表结构比对入口共用），顺序与选中的表一致 */
    fun rememberAll(tables: List<DbTable>, infos: List<TableInfo>) {
        tables.zip(infos).forEach { (table, info) ->
            val (dataSource, schema) = namesOf(table)
            remember(info, dataSource, schema)
        }
    }

    /** 合并后的列清单；tableName 为空时仍可用 resultMap 里的已知列 */
    fun columns(project: Project, tableName: String, file: XmlFile?): List<MapperSqlRules.Column> {
        val key = MapperSqlRules.sanitizeIdentifier(tableName)
        val merged = LinkedHashMap<String, MapperSqlRules.Column>()
        fun add(column: MapperSqlRules.Column) {
            val name = MapperSqlRules.sanitizeIdentifier(column.name)
            if (name.isNotEmpty()) merged.putIfAbsent(name.lowercase(), column.copy(name = name))
        }
        // 实时数据源优先：零前置条件，且比任何离线副本都新
        if (key.isNotEmpty() && MapperAssistSettings.getInstance().state.autoDiscoverTables) {
            DbTableLocator.columns(project, key)?.forEach(::add)
        }
        snapshotColumns(key).forEach(::add)
        if (key.isNotEmpty()) entityColumns(project, key).forEach(::add)
        file?.let { resultMapColumns(it).forEach(::add) }
        return merged.values.toList()
    }

    /** 表名字段匹配：精确 > 忽略大小写 > 最近使用表里同前缀 */
    private fun snapshotColumns(tableName: String): List<MapperSqlRules.Column> {
        if (tableName.isEmpty()) return emptyList()
        val state = MapperAssistSettings.getInstance().state
        val snapshot = state.tableSnapshots.firstOrNull { it.name.equals(tableName, ignoreCase = true) }
            ?: return emptyList()
        return snapshot.columns.map {
            MapperSqlRules.Column(
                name = it.name,
                jdbcType = it.jdbcType,
                comment = it.comment,
                primaryKey = it.primaryKey,
                source = snapshot.dataSource.ifBlank { "snapshot" }
            )
        }
    }

    /** 按表名推实体类名，从工程索引里找类并读字段；`@TableField("col")` 优先于命名推断 */
    private fun entityColumns(project: Project, tableName: String): List<MapperSqlRules.Column> {
        val camel = MapperSqlRules.toCamelCase(tableName)
        if (camel.isEmpty()) return emptyList()
        val pascal = camel.replaceFirstChar { it.uppercaseChar() }
        val candidates = listOf(pascal, "${pascal}DO", "${pascal}PO", "${pascal}Entity", "${pascal}Model")
        val cache = runCatching { PsiShortNamesCache.getInstance(project) }.getOrNull() ?: return emptyList()
        val scope = GlobalSearchScope.projectScope(project)
        for (candidate in candidates) {
            val classes = runCatching { cache.getClassesByName(candidate, scope) }.getOrNull().orEmpty()
            val owner = classes.firstOrNull { !it.isInterface && !it.isEnum } ?: continue
            return owner.allFields
                .filterNot { it.hasModifierProperty(PsiModifier.STATIC) || it.name.contains('$') }
                .map { field ->
                    MapperSqlRules.Column(
                        name = tableFieldOf(field) ?: MapperSqlRules.toSnakeCase(field.name),
                        jdbcType = field.type.presentableText,
                        comment = "",
                        primaryKey = field.name.equals("id", ignoreCase = true),
                        source = owner.name ?: candidate
                    )
                }
        }
        return emptyList()
    }

    /** 当前 XML 的 resultMap 里已声明的列名（`<id column>` 视为主键） */
    private fun resultMapColumns(file: XmlFile): List<MapperSqlRules.Column> {
        val root = file.rootTag ?: return emptyList()
        if (root.name != "mapper") return emptyList()
        val result = LinkedHashMap<String, MapperSqlRules.Column>()
        fun walk(tag: XmlTag) {
            if (tag.name == "id" || tag.name == "result") {
                val raw = tag.getAttributeValue("column").orEmpty()
                val name = MapperSqlRules.sanitizeIdentifier(raw)
                if (name.isNotEmpty()) {
                    result.putIfAbsent(
                        name.lowercase(),
                        MapperSqlRules.Column(name, primaryKey = tag.name == "id", source = "resultMap")
                    )
                }
            }
            tag.subTags.forEach(::walk)
        }
        root.subTags.forEach(::walk)
        return result.values.toList()
    }

    /** 读 MyBatis-Plus `@TableName("t_user")`，用于 XML → 表 的自动映射 */
    fun annotatedTableName(clazz: PsiClass): String? =
        annotationValue("TableName", clazz.modifierList?.annotations.orEmpty())

    /** 按 resultType / parameterType 的 FQN 找类，再读它的 @TableName */
    fun annotatedTableName(project: Project, qualifiedName: String?): String? {
        val fqn = qualifiedName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val clazz = runCatching {
            JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.projectScope(project))
        }.getOrNull() ?: return null
        return annotatedTableName(clazz)
    }

    private fun tableFieldOf(field: PsiField): String? =
        annotationValue("TableField", field.modifierList?.annotations.orEmpty())

    /** 注解值读取同时兼容 `@X("v")` 与 `@X(value = "v")`；解析不到返回 null */
    private fun annotationValue(
        simpleName: String,
        annotations: Array<out com.intellij.psi.PsiAnnotation>
    ): String? {
        val annotation = annotations.firstOrNull {
            it.qualifiedName?.substringAfterLast('.') == simpleName ||
                it.nameReferenceElement?.text == simpleName
        } ?: return null
        val value = annotation.findAttributeValue("value")
            ?: annotation.parameterList.attributes.firstOrNull()?.value
        return value?.text?.trim()?.trim('"', '\'')?.takeIf { it.isNotBlank() }
    }
}
