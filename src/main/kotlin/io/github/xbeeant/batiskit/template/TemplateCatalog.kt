package io.github.xbeeant.batiskit.template

/**
 * 模板作者能用到的名字清单（工作台补全、未定义变量检查共用）。
 *
 * 这些键来自 [TemplateModel.build] 的顶层 Map；模型加键时只要同步这里，
 * 工作台的补全和校验立刻跟上。大括号占位符的降级规则见 [TemplatePlaceholders]。
 */
object TemplateCatalog {

    /** 顶层模型键 → 说明 */
    val TOP_LEVEL_KEYS: List<Pair<String, String>> = listOf(
        "table" to "表信息：name / comment / namingBase",
        "entity" to "实体类名（含 entitySuffix）",
        "keyType" to "主键 Java 类型",
        "pkParameterType" to "主键参数类型",
        "controllerPath" to "REST 路径",
        "basePackageDir" to "基础包名的目录形式",
        "mapperXmlDir" to "Mapper XML 输出目录",
        "package" to "当前模板自己的包名（由 output 推导）",
        "cfg" to "白名单配置",
        "columns" to "实体列清单",
        "tableColumns" to "表全量列清单",
        "baseColumns" to "非大字段列",
        "blobColumns" to "大字段列",
        "hasBlobColumns" to "是否有大字段列",
        "insertAllColumns" to "insert 全量列",
        "layeredColumns" to "分层对象列",
        "queryColumns" to "查询对象列（含日期区间）",
        "entitySideColumns" to "实体侧列",
        "insertColumns" to "insert 列",
        "updatableColumns" to "可更新列",
        "tableUpdatableColumns" to "表侧可更新列",
        "pkColumns" to "主键列",
        "hasPk" to "是否有主键",
        "singlePk" to "单列主键（复合主键为 null）",
        "pkColumn" to "按主键语句用的那一列",
        "hasBatch" to "结构开关",
        "hasBatchByPk" to "结构开关",
        "hasQuery" to "结构开关",
        "empty" to "空列表",
        "excludedBaseFields" to "被忽略的基类审计字段",
        "hasVersion" to "是否有 version 列",
        "hasBlobs" to "实体是否有 byte[] 列",
        "noTransferColumns" to "列注释含 noTransfer 的列",
        "api" to "响应类型全限定名",
        "apiShort" to "响应类型短名",
        "injectionAnnotation" to "注入注解",
        "injectionImport" to "注入注解的 import",
        "idType" to "主键生成策略",
        "generateCrud" to "是否生成 CRUD",
        "isMybatisPlus" to "是否 MyBatis-Plus 方案",
        "author" to "作者",
        "date" to "生成日期",
        "dialect" to "SQL 方言",
        "framework" to "目标框架",
        "util" to "排版工具函数",
        "str" to "字符串处理函数",
        "Model" to "大括号别名：实体类名",
        "model" to "大括号别名：实体变量名",
        "TableComment" to "大括号别名：表注释",
        "tableName" to "大括号别名：去前缀表名",
        "tableNameKebab" to "大括号别名：连字符表名",
        "key" to "大括号别名：主键类型"
    )

    val TABLE_KEYS = listOf("name" to "表名", "comment" to "表注释", "namingBase" to "不带后缀的实体基础名")

    val COLUMN_KEYS = listOf(
        "fieldName" to "Java 字段名", "columnName" to "列名", "dbType" to "数据库类型",
        "javaType" to "Java 类型", "typeFqn" to "类型全限定名", "jdbcType" to "JDBC 类型",
        "comment" to "列注释", "nullable" to "是否可空", "isDateLike" to "是否日期列"
    )

    val UTIL_METHODS = listOf(
        "imports" to "归并 import 清单", "simplify" to "简化全限定类名",
        "javaDoc" to "生成 JavaDoc（表注释, 后缀）", "escape" to "转义字符串",
        "stripPackage" to "去掉包名", "accessors" to "生成列访问器",
        "param" to "生成 #{...} 参数", "sqlColumn" to "按方言引用列名",
        "typeHandler" to "读取列注释里的 TypeHandler", "columnNames" to "列清单 → 列名清单",
        "columnParams" to "列清单 → 参数清单", "wrapJoin" to "每 3 个元素折行拼接",
        "pairs" to "同名字段配对", "pkParams" to "主键参数", "pkPlainParams" to "主键裸参数",
        "pkWhere" to "主键 where", "pkWhereTyped" to "带 jdbcType 的主键 where",
        "pkWhereItem" to "单条主键 where", "pkArgs" to "主键实参", "pkNames" to "主键列名",
        "like" to "模糊查询表达式", "likeStartsWith" to "前缀匹配",
        "likeEndsWith" to "后缀匹配", "pagination" to "分页 SQL 片段",
        "isNonFuzzy" to "是否为非模糊列"
    )

    val STR_METHODS = listOf(
        "camel" to "驼峰", "pascal" to "帕斯卡", "snake" to "下划线",
        "kebab" to "连字符", "uncap" to "首字母小写", "cap" to "首字母大写",
        "lower" to "全小写", "upper" to "全大写", "urlEncode" to "URL 编码", "join" to "拼接"
    )

    val DIRECTIVES = listOf(
        "#if(...)" to "条件分支", "#elseif(...)" to "否则如果", "#else" to "否则",
        "#end" to "结束分支", "#foreach(\$x in \$list)" to "循环",
        "#set(\$x = ...)" to "声明中间变量", "##" to "行注释",
        "#* ... *#" to "块注释", "#[[ ... ]]#" to "原样输出（不做替换）"
    )

    val HEADER_FIELDS = listOf(
        "id" to "模板唯一 id（必填）", "name" to "显示名", "output" to "产物相对路径（必填）",
        "root" to "java / resources", "merge" to "java / xml / overwrite",
        "enabled" to "是否启用（模板串）", "set" to "所属方案（内置模板不需要）",
        "framework" to "any / mybatis / mybatis-plus", "group" to "分组",
        "order" to "排序", "requires" to "依赖的模型版本"
    )

    /** 已知的根名字：顶层键 + 大括号别名 + 指令里的循环变量 */
    private val KNOWN_ROOTS: Set<String> = TOP_LEVEL_KEYS.mapTo(HashSet()) { it.first } +
        setOf("foreach", "true", "false", "null", "package")

    private val ROOT_REFERENCE = Regex("""\$\{?([A-Za-z_][A-Za-z0-9_]*)(?:\.|\}|\b)|\{([A-Za-z_][A-Za-z0-9_]*)\}""")

    /**
     * 检查正文里的变量根名字是否有拼写错误。
     * 嵌套属性（`$table.name`、``$util.javaDoc(...)`）只看根名字，避免把合法子键误报成未定义。
     */
    fun unknownRoots(body: String): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        val seen = HashSet<String>()
        body.lines().forEachIndexed { index, line ->
            ROOT_REFERENCE.findAll(line).forEach { match ->
                val name = match.groupValues[1].ifBlank { match.groupValues[2] }
                if (name.isBlank() || name in KNOWN_ROOTS) return@forEach
                // 已下线的键由 TemplateMigration 给出更准确的迁移建议，这里不重复报
                if (name in TemplateMigration.DEPRECATED_KEYS) return@forEach
                if (seen.add(name)) out += (index + 1) to name
            }
        }
        return out
    }

    /** 工作台补全候选：按前缀过滤，返回「插入文本 + 说明」 */
    fun candidates(prefix: String): List<Pair<String, String>> {
        val p = prefix.trimStart('$', '{', '}')
        return buildList {
            TOP_LEVEL_KEYS.forEach { (key, desc) -> if (key.startsWith(p, true)) add("\${$key}" to desc) }
            UTIL_METHODS.forEach { (key, desc) -> if (key.startsWith(p, true)) add("\$util.$key" to desc) }
            STR_METHODS.forEach { (key, desc) -> if (key.startsWith(p, true)) add("\$str.$key" to desc) }
            DIRECTIVES.forEach { (key, desc) -> if (key.startsWith(p, true)) add(key to desc) }
        }.take(40)
    }
}
