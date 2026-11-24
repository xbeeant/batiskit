package io.github.xbeeant.batiskit.generate

import io.github.xbeeant.batiskit.model.ColumnInfo
import io.github.xbeeant.batiskit.model.TableInfo
import io.github.xbeeant.batiskit.support.ColumnDirectives

/**
 * Mapper 接口与 Mapper XML 共用的「操作方法清单」。
 * 两边的语句必须严格对应，所以集中在这里推导，避免接口与 XML 漂移。
 */
class MapperModel(val table: TableInfo, val ctx: GenContext) {

    val config = ctx.config

    /** MyBatis-Plus 模式下 BaseMapper 已提供基础 CRUD，接口里不再重复生成 */
    val generateCrud: Boolean = !ctx.isMybatisPlus

    val pkColumns: List<ColumnInfo> = table.primaryKeys

    val hasPk: Boolean = pkColumns.isNotEmpty()

    val singlePk: ColumnInfo? = table.singlePrimaryKey

    /**
     * 生成以实体为 example 的条件查询方法（selectByExample / countByExample / fuzzySearch），
     * 不生成独立的 Query 查询对象类
     */
    val hasQuery: Boolean = true

    /** 批量新增 / 批量删除：**恒生成**（原来的 generateBatch 开关已随「行为开关」一起删掉） */
    val hasBatch: Boolean = true

    /**
     * 配置里要求忽略的审计字段（列名或 Java 字段名均可），
     * 默认值来自 idea-batiskit-plugin 的 ignoreColumns（create_by / update_at 之类）
     */
    private val ignored: Set<String> = config.ignoreFields
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    /**
     * 参与实体 / 查询对象生成的列（已剔除忽略字段）。
     * 忽略字段只作用于 model：审计字段等由实体基类（如 BaseModelObject）提供。
     */
    val modelColumns: List<ColumnInfo> = table.columns.filterNot { it.fieldName in ignored || it.columnName in ignored }

    /**
     * 表的全部实际列：Mapper XML 按实际表字段生成（resultMap / where / insert / update），
     * 不受忽略字段影响——运行时实体通过基类继承仍持有这些属性
     */
    val tableColumns: List<ColumnInfo> = table.columns

    /**
     * 分层对象（DTO / VO / QueryDTO）的字段清单 = 全部实际列：
     * 它们没有基类，ignoreFields 忽略的审计字段（BO 由 BaseModelObject 继承提供）也必须保留
     */
    val layeredColumns: List<ColumnInfo> = tableColumns

    /**
     * 转换器中实体侧的有效字段 = 未忽略列 + 被忽略但基类提供的审计列（类型修正为基类类型）：
     * 保证实体侧 getter / setter 一定存在（声明或继承）；
     * 自定义忽略的非审计列（实体无字段、基类也没有）不映射，避免生成代码编译失败。
     * 注意：基类 createBy / updateBy / deleteBy 等是 String，与表列类型可能不同——
     * 类型修正后，转换器会对类型不一致的字段生成判空转换（String.valueOf / 包装类 valueOf）。
     */
    val entitySideColumns: List<ColumnInfo> = run {
        val declared = modelColumns.map { it.fieldName }.toSet()
        modelColumns + tableColumns.filter {
            it.fieldName in BaseModelFields.FIELD_NAMES && it.fieldName !in declared
        }.map { column ->
            val baseType = BaseModelFields.AUDIT[column.fieldName]!!.second
            column.copy(javaType = column.javaType.copy(simple = baseType, qualified = null))
        }
    }

    /** 全部实际列的非主键列，用于 mapper XML 的 update set */
    val tableUpdatableColumns: List<ColumnInfo> = tableColumns.filterNot { it.primaryKey }

    /** 自增主键不参与 insert 的列清单 */
    val insertColumns: List<ColumnInfo> = modelColumns.filterNot { it.autoIncrement }

    /** 非主键列（剔除忽略字段），用于实体相关场景 */
    val updatableColumns: List<ColumnInfo> = modelColumns.filterNot { it.primaryKey }

    val idType: String = singlePk?.javaType?.simple ?: pkColumns.firstOrNull()?.javaType?.simple ?: "Long"

    /** 主键方法的方法签名参数，例如 "@Param(\"id\") Long id" */
    fun pkParams(): String = pkColumns.joinToString(", ") { "@Param(\"${it.fieldName}\") ${it.javaType.simple} ${it.fieldName}" }

    /** 只保留类型与名字，例如 "Long id" */
    fun pkPlainParams(): String = pkColumns.joinToString(", ") { "${it.javaType.simple} ${it.fieldName}" }

    /** 主键参数名列表，例如 ["id", "tenantId"] */
    fun pkNames(): List<String> = pkColumns.map { it.fieldName }

    /** 主键 where 条件（无 jdbcType 的简写版） */
    fun pkWhere(): String = pkColumns.joinToString(" AND ") { "${it.columnName} = #{${it.fieldName}}" }

    /** 主键 where 条件（带 jdbcType，用于 XML 生成） */
    fun pkWhereTyped(): String = pkColumns.joinToString(" AND ") { "${it.columnName} = ${param(it)}" }

    /** 主键 where 条件（item 前缀，用于批量语句） */
    fun pkWhereItem(): String = pkColumns.joinToString(" AND ") { "${it.columnName} = ${param(it, "item.")}" }

    /** 主键值列表（用于 service 调用 mapper） */
    fun pkArgs(): String = pkColumns.map { it.fieldName }.joinToString(", ")

    /** 是否有批量按主键操作（需要单列主键） */
    val hasBatchByPk: Boolean = hasBatch && singlePk != null

    /**
     * #{xxx,jdbcType=TYPE[,typeHandler=Fqn]} 参数片段。
     * 对应 xstudio 插件 MyBatis3FormattingUtilities.getParameterClause 的产物。
     */
    fun param(column: ColumnInfo, prefix: String = "", suffix: String = ""): String = buildString {
        append("#{").append(prefix).append(column.fieldName).append(suffix)
        append(",jdbcType=").append(column.javaType.jdbcType)
        ColumnDirectives.typeHandler(column.comment)?.let { append(",typeHandler=").append(it) }
        append("}")
    }
}
