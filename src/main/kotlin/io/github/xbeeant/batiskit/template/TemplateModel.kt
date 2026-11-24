package io.github.xbeeant.batiskit.template

import io.github.xbeeant.batiskit.generate.BaseModelFields
import io.github.xbeeant.batiskit.generate.GenContext
import io.github.xbeeant.batiskit.generate.MapperModel
import io.github.xbeeant.batiskit.model.ColumnInfo
import io.github.xbeeant.batiskit.model.TableInfo
import io.github.xbeeant.batiskit.support.JavaText
import io.github.xbeeant.batiskit.support.Naming
import io.github.xbeeant.batiskit.support.ColumnDirectives
import io.github.xbeeant.batiskit.string.stringTemplateFunctions

/**
 * 把「表结构 + 生成配置」摊平成模板能直接消费的 Map 树。
 *
 * 只有 Map / List / String / Boolean / Number 这几种值，模板引擎不需要反射；
 * 需要推导的东西（jdbcType 拼接、typeHandler 注入、getter/setter 文本、import 归并）
 * 都以 [TemplateFunction] 的形式放在 `util` 下，模板只负责排版 —— 这是「模板好写」与
 * 「模板不用重复实现业务规则」之间的那条线。
 */
object TemplateModel {

    /** 列 Map 里藏原始对象的键：模板用不到，`util.*` 靠它取回 [ColumnInfo] */
    private const val SOURCE = "_src"

    fun build(table: TableInfo, ctx: GenContext): Map<String, Any?> {
        val model = MapperModel(table, ctx)
        val cfg = ctx.config

        val columns = model.modelColumns.map { column(it, ctx) }
        val tableColumns = model.tableColumns.map { column(it, ctx) }
        val layeredColumns = model.layeredColumns.map { column(it, ctx) }
        val queryColumns = buildList {
            addAll(layeredColumns)
            // 日期列一律附区间字段（xxxBegin / xxxEnd）：条件查询靠它们表达 >= / <=
            model.layeredColumns.filter { it.isDateLike }.forEach { source ->
                val label = source.comment.ifBlank { source.fieldName }
                add(column(source.copy(fieldName = "${source.fieldName}Begin", comment = "${label}开始"), ctx))
                add(column(source.copy(fieldName = "${source.fieldName}End", comment = "${label}结束"), ctx))
            }
        }

        /** 去表前缀的原始表名：t_access_log -> access_log */
        val baseTableName = Naming.baseTableName(table.tableName, cfg.tablePrefixes)

        val root = linkedMapOf<String, Any?>(
            "table" to mapOf(
                "name" to table.tableName,
                "comment" to table.comment,
                "namingBase" to table.namingBase
            ),
            /** 表 → 实体的唯一映射（它把 `entitySuffix` 拼上去，模板自己拼不出配置后缀） */
            "entity" to table.entityName,
            "keyType" to ctx.keyType(table),
            "pkParameterType" to ctx.pkParameterType(table),
            "controllerPath" to ctx.controllerPath(table),
            /** 基础包名的目录形式：io.github.xbeeant -> io/github/xbeeant（模板 output 用它拼路径） */
            "basePackageDir" to ctx.basePackage.replace('.', '/'),
            /** Mapper XML 的输出目录：mybatis/数据库类型/库名（库名为空时省略该层级） */
            "mapperXmlDir" to ctx.mapperXmlDir(),
            /** 当前模板自己的包名，渲染正文前由 [TemplateRegistry.render] 按 output 路径注入 */
            "package" to "",
            "cfg" to configMap(ctx),
            "columns" to columns,
            "tableColumns" to tableColumns,
            /** 大字段列与非大字段列：XML 的 resultMap / insert 按这个分组 */
            "baseColumns" to model.tableColumns.filterNot { it.javaType.jdbcType in BLOB_JDBC }.map { column(it, ctx) },
            "blobColumns" to model.tableColumns.filter { it.javaType.jdbcType in BLOB_JDBC }.map { column(it, ctx) },
            "hasBlobColumns" to model.tableColumns.any { it.javaType.jdbcType in BLOB_JDBC },
            /** insert 全量列 = 非大字段 + 大字段（顺序与原生成器一致） */
            "insertAllColumns" to (model.tableColumns.filterNot { it.javaType.jdbcType in BLOB_JDBC } +
                model.tableColumns.filter { it.javaType.jdbcType in BLOB_JDBC }).map { column(it, ctx) },
            "layeredColumns" to layeredColumns,
            "queryColumns" to queryColumns,
            "entitySideColumns" to model.entitySideColumns.map { column(it, ctx) },
            "insertColumns" to model.insertColumns.map { column(it, ctx) },
            "updatableColumns" to model.updatableColumns.map { column(it, ctx) },
            "tableUpdatableColumns" to model.tableUpdatableColumns.map { column(it, ctx) },
            "pkColumns" to model.pkColumns.map { column(it, ctx) },
            "hasPk" to model.hasPk,
            "singlePk" to model.singlePk?.let { column(it, ctx) },
            /** XML 里「按主键」语句用的那一列：单列主键就是它，复合主键取第一列（与原生成器一致） */
            "pkColumn" to (model.singlePk ?: model.pkColumns.firstOrNull())?.let { column(it, ctx) },
            "hasBatch" to model.hasBatch,
            "hasBatchByPk" to model.hasBatchByPk,
            "hasQuery" to model.hasQuery,
            "empty" to emptyList<Any?>(),
            "excludedBaseFields" to excludedBaseFields(model),
            /** 表里有 version 列（MyBatis-Plus 需要 @Version） */
            "hasVersion" to model.modelColumns.any { isVersionColumn(it) },
            /** 实体里有 byte[] 列（ServiceImpl 需要声明 isWithBlobs） */
            "hasBlobs" to model.modelColumns.any { it.javaType.simple == "byte[]" },
            /** 列注释含 noTransfer 的列（ServiceImpl 生成转义重写用） */
            "noTransferColumns" to model.modelColumns
                .filter { it.comment.replace(" ", "").contains("noTransfer") }
                .map { column(it, ctx) },
            "api" to API_RESPONSE,
            "apiShort" to JavaText.stripPackage(API_RESPONSE),
            "injectionAnnotation" to injectionAnnotation(ctx),
            "injectionImport" to injectionImport(ctx),
            "idType" to model.idType,
            "generateCrud" to model.generateCrud,
            "isMybatisPlus" to ctx.isMybatisPlus,
            "author" to ctx.author,
            "date" to ctx.date,
            "dialect" to ctx.dialect.name,
            "framework" to ctx.framework.name,
            "util" to util(model, ctx),
            /** 字符串处理函数（$str.camel / snake / urlEncode / join …），与编辑器右键共用一套算法 */
            "str" to stringTemplateFunctions()
        )

        // 大括号占位符的短名字（见 TemplatePlaceholders.NAMES）：
        // 只在这里给别名，模板里写 `{Model}` 全靠编译前降级成 `${entity}`，引擎不认识这些名字
        root["Model"] = table.entityName
        root["model"] = table.entityName.replaceFirstChar { it.lowercaseChar() }
        root["TableComment"] = table.comment
        root["tableName"] = baseTableName
        root["tableNameKebab"] = Naming.kebab(baseTableName)
        root["key"] = ctx.keyType(table)

        // 排版块：内容本身就是模板（资源根的 blocks/*.tpl），这里只是把它们渲染好放进模型，
        // 模板里写 `{fields}` 就等于把整段字段声明搬过来
        TemplateBlocks.apply(root)
        return root
    }

    /**
     * 基类审计字段里「本表没有对应列、实体也没声明同名普通字段」的那些。
     *
     * 只对 MyBatis-Plus 有意义：基类字段会被 MP 当成表列自动映射，
     * 表里没这列时必须在子类重新声明 `@TableField(exist = false)` 显式排除。
     * 判定规则与 `MpEntityGenerator` 一致，改这里等于改两处，所以只留这一处。
     */
    private fun excludedBaseFields(model: MapperModel): List<Map<String, Any?>> {
        val tableColumnNames = model.tableColumns.map { it.columnName }.toSet()
        val declaredFieldNames = model.modelColumns.map { it.fieldName }.toSet()
        return BaseModelFields.AUDIT.mapNotNull { (field, meta) ->
            val (columnName, javaType) = meta
            if (columnName in tableColumnNames || field in declaredFieldNames) {
                null
            } else {
                mapOf("field" to field, "column" to columnName, "type" to javaType)
            }
        }
    }

    private fun column(column: ColumnInfo, ctx: GenContext): Map<String, Any?> = mapOf(
        "column" to column.columnName,
        "field" to column.fieldName,
        "dbType" to column.dbType,
        "type" to column.javaType.simple,
        "typeFqn" to column.javaType.qualified,
        "jdbcType" to column.javaType.jdbcType,
        "comment" to column.comment,
        "label" to column.comment.ifBlank { column.fieldName },
        "pk" to column.primaryKey,
        "auto" to column.autoIncrement,
        "nullable" to column.nullable,
        "dateLike" to column.isDateLike,
        "string" to column.isString,
        "cap" to column.fieldName.replaceFirstChar { it.uppercaseChar() },
        "version" to isVersionColumn(column),
        "sqlName" to ctx.sqlColumn(column),
        SOURCE to column
    )

    /** 乐观锁版本号字段：字段名或列名为 version（不区分大小写） */
    private fun isVersionColumn(column: ColumnInfo): Boolean =
        column.fieldName.equals("version", ignoreCase = true) || column.columnName.equals("version", ignoreCase = true)

    /** Controller 的返回包装类型：写死在模板里的常量，不再是配置项 */
    private const val API_RESPONSE = "io.github.xbeeant.ApiResponse"

    /** 注入注解：AUTOWIRED 用 @Autowired，其余按 javax / jakarta 分 */
    private fun injectionAnnotation(ctx: GenContext): String =
        if (ctx.config.injectionStyle.uppercase() == "AUTOWIRED") "@Autowired" else "@Resource"

    private fun injectionImport(ctx: GenContext): String = when (ctx.config.injectionStyle.uppercase()) {
        "AUTOWIRED" -> "org.springframework.beans.factory.annotation.Autowired"
        "JAVAX_RESOURCE" -> "javax.annotation.Resource"
        else -> "jakarta.annotation.Resource"
    }

    /** 大字段（jdbcType 命中这些的列）单独分组，XML 里要生成 Blob_Column_List 与 ResultMapWithBLOBs */
    private val BLOB_JDBC = setOf("LONGVARCHAR", "LONGVARBINARY", "CLOB", "BLOB")

    /** String 可解析的包装类型（Boolean.valueOf / Long.valueOf / …） */
    private val STRING_PARSEABLE = setOf("Long", "Integer", "Short", "Byte", "Double", "Float", "Boolean")

    /** 配置项白名单：模板只需要读写这些，保持显式比反射一份 config 更可控 */
    private fun configMap(ctx: GenContext): Map<String, Any?> {
        val cfg = ctx.config
        return mapOf(
            "basePackage" to cfg.basePackage,
            "tablePrefixes" to cfg.tablePrefixes,
            "entitySuffix" to cfg.entitySuffix,
            "author" to cfg.author,
            "databaseName" to cfg.databaseName,
            "injectionStyle" to cfg.injectionStyle,
            "dateKind" to cfg.dateKind,
            "useLombok" to cfg.useLombok,
            "dialect" to cfg.dialect,
            "targetFramework" to cfg.targetFramework,
            "ignoreFields" to cfg.ignoreFields,
            "nonFuzzySearchColumns" to cfg.nonFuzzySearchColumns,
            "overwriteMethods" to cfg.overwriteMethods
        )
    }

    // ------------------------------------------------------------------ util 函数

    private fun util(model: MapperModel, ctx: GenContext): Map<String, Any?> = mapOf(
        "imports" to TemplateFunction { args -> imports(args) },
        "simplify" to TemplateFunction { args -> JavaText.simplifyQualified(text(args, 0)).first },
        /** 两个参数：表注释、后缀（后缀恒追加，例如 " Mapper"） */
        "javaDoc" to TemplateFunction { args -> javaDoc(ctx, text(args, 0), text(args, 1)) },
        "escape" to TemplateFunction { args -> JavaText.escape(text(args, 0)) },
        "stripPackage" to TemplateFunction { args -> JavaText.stripPackage(text(args, 0)) },
        "accessors" to TemplateFunction { args -> accessors(source(args, 0)) },
        "param" to TemplateFunction { args ->
            model.param(source(args, 0), text(args, 1), text(args, 2))
        },
        "sqlColumn" to TemplateFunction { args -> ctx.sqlColumn(source(args, 0)) },
        "typeHandler" to TemplateFunction { args -> ColumnDirectives.typeHandler(source(args, 0).comment) },
        /** 列清单 -> 列名清单（MySQL 反引号 / 保留字双引号，规则见 GenContext.sqlColumn） */
        "columnNames" to TemplateFunction { args -> columnsOf(args, 0).map { ctx.sqlColumn(it) } },
        /** 列清单 -> #{...} 参数清单（按前缀取用） */
        "columnParams" to TemplateFunction { args ->
            val prefix = text(args, 1)
            columnsOf(args, 0).map { model.param(it, prefix) }
        },
        /** 每 3 个元素换行，分隔符 ", "——与真实产物的折行风格一致 */
        "wrapJoin" to TemplateFunction { args -> wrapJoin(args.getOrNull(0)) },
        /** 同名字段配对后的转换动作清单，模板只负责把它们排成 setter 调用 */
        "pairs" to TemplateFunction { args -> pairs(columnsOf(args, 0), columnsOf(args, 1)) },
        "pkParams" to TemplateFunction { model.pkParams() },
        "pkPlainParams" to TemplateFunction { model.pkPlainParams() },
        "pkWhere" to TemplateFunction { model.pkWhere() },
        "pkWhereTyped" to TemplateFunction { model.pkWhereTyped() },
        "pkWhereItem" to TemplateFunction { model.pkWhereItem() },
        "pkArgs" to TemplateFunction { model.pkArgs() },
        "pkNames" to TemplateFunction { model.pkNames() },
        "like" to TemplateFunction { args -> ctx.likeExpression(text(args, 0)) },
        "likeStartsWith" to TemplateFunction { args -> ctx.likeStartsWith(text(args, 0)) },
        "likeEndsWith" to TemplateFunction { args -> ctx.likeEndsWith(text(args, 0)) },
        "pagination" to TemplateFunction { ctx.pagination() },
        "isNonFuzzy" to TemplateFunction { args -> ctx.isNonFuzzy(source(args, 0)) }
    )

    /**
     * 生成 import 清单。参数可以混着给：
     *
     * - **列清单**（List 里的 Map 带 `typeFqn`）→ 该类型进 import
     * - **类型清单**（List 里的 String）→ 字符串本身就是一个全限定名
     * - **文本**（String）→ 从里面抽出全限定类型，所以 `$base.mapper` 这种带泛型的声明可以直接传
     * - **字面量**（String，逗号分隔）→ 每段单独处理，允许 `lombok.*` 这种通配
     *
     * 结果去重排序并丢掉 `java.lang.`——与 `JavaSource` 的过滤规则一致，
     * 这样模板产物才能和旧生成器逐字相同。
     */
    private fun imports(args: List<Any?>): List<String> {
        val result = sortedSetOf<String>()
        args.forEach { arg ->
            when (arg) {
                is List<*> -> arg.forEach { item ->
                    when (item) {
                        is Map<*, *> -> (item["typeFqn"] as? String)?.takeIf { it.isNotBlank() }?.let { result += it }
                        is String -> item.trim().takeIf { it.isNotEmpty() }?.let { result += it }
                    }
                }

                is String -> arg.split(',').forEach { piece ->
                    val part = piece.trim()
                    if (part.isEmpty()) {
                        return@forEach
                    }
                    if (part.endsWith(".*")) {
                        result += part
                    } else {
                        JavaText.qualifiedTypesIn(part).forEach { result += it }
                    }
                }
            }
        }
        return result.filterNot { it.startsWith("java.lang.") }
    }

    /**
     * 标准类级 JavaDoc：表注释 + 固定后缀 + 作者 / 日期。
     *
     * 第二个参数是**恒追加**的后缀，不是「注释为空时的兜底」——写 `$util.javaDoc($table.comment, " Mapper")`
     * 得到的是 `* 访问日志表 Mapper`，表注释为空时则是 `* Mapper`。想在没注释时改用表名，模板里写
     * `#if($table.comment)…#else…$table.name…#end`，生成器不替模板猜这条规则。
     */
    private fun javaDoc(ctx: GenContext, comment: String, suffix: String = ""): String {
        // 两头 trim 后用单个空格连接：注释为空时不会留下前导空格，后缀为空时不会留下尾随空格
        val text = (comment.trim() + " " + suffix.trim()).trim()
        return buildString {
            append("/**\n")
            if (text.isNotBlank()) {
                append(" * ").append(text.replace("\n", " ")).append("\n")
                append(" *\n")
            }
            append(" * @author ").append(ctx.author).append("\n")
            append(" * @since ").append(ctx.date).append("\n")
            // 结尾不带换行：模板里这个函数单独占一行，换行由模板那一行提供，
            // 否则会多出一个空行（JavaSource.javaDoc 的结尾是 "*/\n"，两者产物本来就不同）
            append(" */")
        }
    }

    /** getter / setter 文本（Boolean 也用 getXxx，与既有产物一致） */
    private fun accessors(column: ColumnInfo): String {
        val type = column.javaType.simple
        val field = column.fieldName
        val cap = field.replaceFirstChar { it.uppercaseChar() }
        return buildString {
            append("    public ").append(type).append(' ').append("get").append(cap).append("() {\n")
            append("        return ").append(field).append(";\n")
            append("    }\n\n")
            append("    public void set").append(cap).append('(').append(type).append(' ').append(field).append(") {\n")
            append("        this.").append(field).append(" = ").append(field).append(";\n")
            append("    }\n\n")
        }
    }

    /** 从列 Map 里取回原始 [ColumnInfo]；直接传字符串时按字段名兜底（模板写错不至于崩） */
    private fun source(value: Any?): ColumnInfo {
        if (value is Map<*, *>) {
            (value[SOURCE] as? ColumnInfo)?.let { return it }
        }
        if (value is ColumnInfo) {
            return value
        }
        val name = stringify(value)
        return ColumnInfo(
            columnName = name,
            fieldName = name,
            dbType = "",
            javaType = io.github.xbeeant.batiskit.model.JavaType("String", null, "VARCHAR"),
            comment = "",
            primaryKey = false,
            autoIncrement = false,
            nullable = true
        )
    }

    private fun source(args: List<Any?>, index: Int): ColumnInfo = source(args.getOrNull(index))

    /** 取第 index 个参数里的列清单，逐个还原成 [ColumnInfo] */
    private fun columnsOf(args: List<Any?>, index: Int): List<ColumnInfo> =
        (args.getOrNull(index) as? List<*>)?.map { source(it) } ?: emptyList()

    private fun wrapJoin(value: Any?): String {
        val items = (value as? List<*>)?.map { stringify(it) } ?: emptyList()
        return items.chunked(3).joinToString(", \n$CONTINUATION") { it.joinToString(", ") }
    }

    /**
     * 同名字段配对 + 转换动作判定。规则与 `ConverterGenerator.mapping` 一致：
     *  - `same`：同类型，直接 get/set；
     *  - `toStr`：目标为 String（源不是），`String.valueOf` 且判空；
     *  - `parse`：源为 String、目标是可解析包装类，`Xxx.valueOf` 且判空；
     *  - 其余组合安全转换不了，直接不配对（宁缺勿错，否则生成的代码编译不过）。
     */
    private fun pairs(sources: List<ColumnInfo>, targets: List<ColumnInfo>): List<Map<String, Any?>> {
        val byName = targets.associateBy { it.fieldName }
        return sources.mapNotNull { source -> byName[source.fieldName]?.let { source to it } }
            .distinctBy { it.first.fieldName }
            .mapNotNull { (from, to) -> pair(from, to) }
    }

    private fun pair(from: ColumnInfo, to: ColumnInfo): Map<String, Any?>? {
        val kind = when {
            from.javaType.simple == to.javaType.simple -> "same"
            to.javaType.simple == "String" -> "toStr"
            from.javaType.simple == "String" && to.javaType.simple in STRING_PARSEABLE -> "parse"
            else -> return null
        }
        return mapOf(
            "kind" to kind,
            "getter" to "get" + from.fieldName.replaceFirstChar { it.uppercaseChar() },
            "setter" to "set" + to.fieldName.replaceFirstChar { it.uppercaseChar() },
            "targetType" to to.javaType.simple
        )
    }

    /** XML 里续行的缩进：与原生成器的 CONT 常量保持一致 */
    private const val CONTINUATION = "      "

    private fun text(args: List<Any?>, index: Int): String = stringify(args.getOrNull(index))

    private fun stringify(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        else -> value.toString()
    }
}
