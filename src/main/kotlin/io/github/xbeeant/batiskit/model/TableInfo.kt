package io.github.xbeeant.batiskit.model

/**
 * Java 类型描述。
 *
 * @param simple 源码里写的类型名，例如 String / byte[]
 * @param qualified 全限定名，java.lang 包下的类型为 null（无需 import）
 * @param jdbcType 写入 Mapper XML 的 jdbcType，例如 VARCHAR / TIMESTAMP
 */
data class JavaType(
    val simple: String,
    val qualified: String?,
    val jdbcType: String
)

/** 数据库列信息（已按生成配置完成类型与命名转换） */
data class ColumnInfo(
    /** 数据库列名 */
    val columnName: String,
    /** Java 字段名 */
    val fieldName: String,
    /** 数据库类型，例如 varchar(64) */
    val dbType: String,
    /** 映射后的 Java 类型 */
    val javaType: JavaType,
    /** 列注释 */
    val comment: String,
    /** 是否主键 */
    val primaryKey: Boolean,
    /** 是否自增 */
    val autoIncrement: Boolean,
    /** 是否允许为空 */
    val nullable: Boolean
) {
    val isString: Boolean
        get() = javaType.simple == "String"

    val isDateLike: Boolean
        get() = javaType.simple.endsWith("Date") || javaType.simple.endsWith("Time")
}

/** 表信息 */
data class TableInfo(
    /** 数据库表名 */
    val tableName: String,
    /** 实体类名（含实体后缀，如 UserDO） */
    val entityName: String,
    /** 表注释 */
    val comment: String,
    val columns: List<ColumnInfo>,
    /**
     * 不带实体后缀的基础名（UserDO -> User）。
     * Mapper / Service / ServiceImpl / Controller / XML 等类名由它派生，
     * 只有实体类本身带后缀；为空时回退 entityName（保持既有行为）
     */
    val baseName: String? = null
) {
    val primaryKeys: List<ColumnInfo> = columns.filter { it.primaryKey }

    val autoIncrementKey: ColumnInfo? = primaryKeys.firstOrNull { it.autoIncrement }

    /** 单列主键（多列主键时为 null） */
    val singlePrimaryKey: ColumnInfo? = primaryKeys.singleOrNull()

    /** 除主键外的普通列 */
    val normalColumns: List<ColumnInfo> = columns.filterNot { it.primaryKey }

    /** 派生一切产物名用的基础名（不带实体后缀） */
    val namingBase: String = baseName?.takeIf { it.isNotBlank() } ?: entityName
}
