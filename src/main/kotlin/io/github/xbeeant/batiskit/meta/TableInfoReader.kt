package io.github.xbeeant.batiskit.meta

import com.intellij.database.Dbms
import com.intellij.database.model.DasColumn
import com.intellij.database.psi.DbColumn
import com.intellij.database.psi.DbTable
import com.intellij.database.util.DasUtil
import io.github.xbeeant.batiskit.config.GenerationConfig
import io.github.xbeeant.batiskit.config.SqlDialect
import io.github.xbeeant.batiskit.config.dateKindEnum
import io.github.xbeeant.batiskit.model.ColumnInfo
import io.github.xbeeant.batiskit.model.TableInfo
import io.github.xbeeant.batiskit.support.Naming
import io.github.xbeeant.batiskit.support.TypeMapping

/**
 * 从 Database 插件的 PSI 元数据里读取表结构。
 *
 * 相比「自己连 JDBC 做 introspection」的好处：不需要再要一份账号密码，
 * 用户在 Database 视图里选中表时，表结构已经在 IDE 里了。
 */
object TableInfoReader {

    fun read(table: DbTable, config: GenerationConfig): TableInfo {
        val tableName = table.name
        // Search String / Replace String：先生成重命名后的表名，再推导类名
        val renamed = Naming.applyRename(tableName, config.searchString, config.replaceString)
        val entityName = Naming.entityName(renamed, config.tablePrefixes, config.entitySuffix)
        // 分层类名（Mapper / Service / ServiceImpl / Controller / XML）不带实体后缀
        val baseName = Naming.entityName(renamed, config.tablePrefixes, "")
        val pkNames = runCatching {
            DasUtil.getPrimaryKey(table)?.columnsRef?.names()?.toList().orEmpty()
        }.getOrDefault(emptyList())

        val columns = runCatching {
            DasUtil.getColumns(table)
                .filterIsInstance<DbColumn>()
                .map { toColumn(table, it, config, pkNames) }
        }.getOrDefault(emptyList())

        return TableInfo(
            tableName = tableName,
            entityName = entityName,
            comment = table.comment?.trim().orEmpty(),
            columns = columns,
            baseName = baseName
        )
    }

    /** 根据数据源推断 SQL 方言 */
    fun detectDialect(table: DbTable): SqlDialect? {
        val dbms: Dbms = runCatching { table.dataSource.dbms }.getOrNull() ?: return null
        return when {
            dbms.isOracle() -> SqlDialect.ORACLE
            dbms.isPostgres() -> SqlDialect.POSTGRESQL
            dbms.isMysql() -> SqlDialect.MYSQL
            dbms.name.contains("maria", ignoreCase = true) -> SqlDialect.MYSQL
            else -> null
        }
    }

    private fun toColumn(
        table: DbTable,
        column: DbColumn,
        config: GenerationConfig,
        pkNames: List<String>
    ): ColumnInfo {
        val dataType = runCatching { column.dasType.toDataType() }.getOrNull()
        val rawTypeName = dataType?.typeName?.takeIf { it.isNotBlank() && it != "unknown" }
            ?: runCatching { column.dasType.specification.substringBefore('(').trim() }.getOrDefault("varchar")
        val size = dataType?.size ?: -1
        val attrs = runCatching { table.getColumnAttrs(column) }.getOrDefault(emptySet())
        val default = runCatching { column.default }.getOrNull().orEmpty()

        val javaType = TypeMapping.map(rawTypeName, size, config.dateKindEnum)
        val primaryKey = attrs.contains(DasColumn.Attribute.PRIMARY_KEY) || pkNames.contains(column.name)
        val autoIncrement = attrs.contains(DasColumn.Attribute.AUTO_GENERATED) ||
            default.contains("nextval", ignoreCase = true) ||
            default.contains("auto_increment", ignoreCase = true)

        return ColumnInfo(
            columnName = column.name,
            fieldName = Naming.fieldName(column.name),
            dbType = runCatching { column.dasType.specification }.getOrDefault(rawTypeName),
            javaType = javaType,
            comment = column.comment?.trim().orEmpty(),
            primaryKey = primaryKey,
            autoIncrement = autoIncrement,
            nullable = !column.isNotNull
        )
    }
}
