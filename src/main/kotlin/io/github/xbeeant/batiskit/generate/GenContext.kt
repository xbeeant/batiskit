package io.github.xbeeant.batiskit.generate

import io.github.xbeeant.batiskit.config.DEFAULT_SQL_RESERVED_KEYWORDS
import io.github.xbeeant.batiskit.config.DateKind
import io.github.xbeeant.batiskit.config.GenerationConfig
import io.github.xbeeant.batiskit.config.SqlDialect
import io.github.xbeeant.batiskit.config.TargetFramework
import io.github.xbeeant.batiskit.config.dateKindEnum
import io.github.xbeeant.batiskit.config.dialectEnum
import io.github.xbeeant.batiskit.config.frameworkEnum
import io.github.xbeeant.batiskit.model.ColumnInfo
import io.github.xbeeant.batiskit.model.TableInfo
import io.github.xbeeant.batiskit.support.JavaText
import io.github.xbeeant.batiskit.support.Naming
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 内置 SQL 保留字兜底集合（配置为空时使用）：来自 [io.github.xbeeant.batiskit.config.DEFAULT_SQL_RESERVED_KEYWORDS]
 */
private val BUILTIN_SQL_RESERVED_KEYWORDS: Set<String> =
    DEFAULT_SQL_RESERVED_KEYWORDS.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

/** 解析「SQL 保留字」配置：空值回退内置默认；统一小写，命中判定不区分大小写 */
private fun parseSqlReservedKeywords(value: String): Set<String> {
    val source = value.trim()
    val words = if (source.isEmpty()) {
        BUILTIN_SQL_RESERVED_KEYWORDS
    } else {
        source.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }
    return words.toSet()
}

/**
 * 一次生成任务的上下文：配置 + 方言。
 *
 * 这里**不解析包名、不解析类名、不解析基类**——包由模板的 `output` 路径决定（见 `TemplateRegistry.packageOf`），
 * 产物叫什么名字由模板自己拼（`${table.namingBase}Mapper` 等），基类写在模板正文里。
 * 上下文只负责「表结构之外、模板自己算不出来」的那部分：SQL 片段、路径推导、配置项取值。
 */
class GenContext(val config: GenerationConfig) {

    val basePackage: String = config.basePackage.trim().trim('.')

    val dateKind: DateKind = config.dateKindEnum
    val dialect: SqlDialect = config.dialectEnum
    val framework: TargetFramework = config.frameworkEnum

    /** 是否生成 MyBatis-Plus 风格的代码 */
    val isMybatisPlus: Boolean = framework == TargetFramework.MYBATIS_PLUS

    val author: String = config.author.ifBlank { "generator" }
    val date: String = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

    /**
     * Mapper XML 输出目录：`mybatis/数据库类型/库名`，例如 mybatis/mysql/demo_db。
     * 库名来自生成页的「库名」配置项，为空时省略该层级；`mybatis` 这一级是固定的资源目录约定。
     */
    fun mapperXmlDir(): String =
        listOf("mybatis", dialect.name.lowercase(), config.databaseName.trim())
            .filter { it.isNotBlank() }
            .joinToString("/")

    /** Rest 路径：/基础名 kebab（User -> /user，AuditLog -> /audit-log；不带实体后缀） */
    fun controllerPath(table: TableInfo): String = "/" + Naming.kebab(table.namingBase)

    /** 该列是否被配置为「不参与模糊查询」（对应参考插件 nonFuzzySearchColumn） */
    fun isNonFuzzy(column: ColumnInfo): Boolean = nonFuzzySet.contains(column.columnName) ||
        nonFuzzySet.contains(column.fieldName)

    private val nonFuzzySet: Set<String> by lazy {
        config.nonFuzzySearchColumns.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * 主键 Java 类型：单列主键取该列类型，无主键或多列主键回退 Long
     * （对应 xstudio 插件 PluginUtil.getKeyFqjt 的行为）
     */
    fun keyType(table: TableInfo): String =
        table.singlePrimaryKey?.javaType?.simple ?: "Long"

    /** 主键的 java.lang 包装类型（如 java.lang.Long），用于 parameterType / resultType */
    fun pkParameterType(table: TableInfo): String = when (val simple = keyType(table)) {
        "Long" -> "java.lang.Long"
        "Integer" -> "java.lang.Integer"
        "Short" -> "java.lang.Short"
        "String" -> "java.lang.String"
        "Boolean" -> "java.lang.Boolean"
        else -> simple
    }

    /**
     * SQL 语句里的列名引用（避免与数据库关键字冲突）：
     * MySQL 一律反引号包裹（与真实产物一致）；其它方言命中保留字或含特殊字符时用双引号，
     * 普通标识符保持原样（避免 PostgreSQL 等引号引入大小写敏感问题）
     */
    fun sqlColumn(column: ColumnInfo): String = when (dialect) {
        SqlDialect.MYSQL -> "`${column.columnName}`"
        else -> if (needsQuote(column.columnName)) "\"${column.columnName}\"" else column.columnName
    }

    /** 命中判定用的保留字集合：读「SQL 保留字」配置，空时回退内置默认 */
    private val sqlReservedSet: Set<String> by lazy { parseSqlReservedKeywords(config.sqlReservedKeywords) }

    private fun needsQuote(name: String): Boolean =
        name.lowercase() in sqlReservedSet || !Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)

    /** 抽取模板里用到的全限定类型，便于生成 import */
    fun importsOf(template: String): List<String> = JavaText.qualifiedTypesIn(template)

    /** 模糊查询的字符串拼接写法，MySQL 与其它方言不同 */
    fun likeExpression(placeholder: String): String = when (dialect) {
        SqlDialect.MYSQL -> "CONCAT('%', $placeholder, '%')"
        else -> "'%' || $placeholder || '%'"
    }

    /** 前缀模糊匹配 x%（对应 xstudio 的 fuzzy:startWidth） */
    fun likeStartsWith(placeholder: String): String = when (dialect) {
        SqlDialect.MYSQL -> "CONCAT($placeholder, '%')"
        else -> "$placeholder || '%'"
    }

    /** 后缀模糊匹配 %x（对应 xstudio 的 fuzzy:endWidth） */
    fun likeEndsWith(placeholder: String): String = when (dialect) {
        SqlDialect.MYSQL -> "CONCAT('%', $placeholder)"
        else -> "'%' || $placeholder"
    }

    /** 分页片段 */
    fun pagination(): String = when (dialect) {
        SqlDialect.ORACLE -> "OFFSET #{offset} ROWS FETCH NEXT #{pageSize} ROWS ONLY"
        else -> "LIMIT #{pageSize} OFFSET #{offset}"
    }
}
