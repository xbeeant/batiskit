package io.github.xbeeant.batiskit.template

import java.util.concurrent.ConcurrentHashMap

/**
 * 大括号占位符：`{Model}` 等价于 `${entity}`。
 *
 * 模板作者想要的是「填空题」而不是模板语言——绝大多数产物（Controller / Service / DTO…）
 * 只是把表名、类名、注释、作者这些数据库相关的值嵌进一段固定文本里，
 * 写 `{Model}` 比写 `${entity}` 少打两个字符、也不用记住模型里那堆驼峰键名。
 *
 * ## 为什么是编译前降级，而不是引擎里加一种节点
 *
 * 降级成 `${...}` 之后，大括号占位符能和 `$` 写法出现在任何位置（正文、`$util.javaDoc(...)` 的实参、
 * `#if(...)` 的条件），引擎只需要认一种引用语法，不需要为大括号再写一套求值分支。
 *
 * ## 认哪些名字
 *
 * 只降低**模型里真的有的顶层键**（外加 [NAMES] 这份兜底词表）。这样模板里写什么、
 * 这里就认什么，模型加一个键不用回来改词表；反过来，模型里没有的 `{id}`（
 * `@DeleteMapping("/{id}")` 的路径变量）、`{record.userName}` 原样留在大括号里。
 *
 * 另外 `$`、`#`、`\` 后面的 `{` 一律不降级——`${...}` / `#{...}` / `\{...}` 各有各的含义。
 */
object TemplatePlaceholders {

    /**
     * 与模型无关的兜底词表。
     *
     * 大多数对应 [TemplateModel] 里的顶层键（可带 `.` 继续取值，如 `{table.name}`）；
     * 前六个是与数据库表格直接对应的短名字，`fields` / `accessors` / `mapperMethods`
     * 是排版块（见 `blocks/`）。
     *
     * 真正渲染时还会并入模型自己的键（见 [namesOf]），所以这份表只需要覆盖
     * 「只拿到模板文本、还没建模型」的场景（引擎单测、模板校验）。
     */
    val NAMES: Set<String> = linkedSetOf(
        // —— 表的短名字（示例里用的就是这套）
        "Model",        // 实体类名：AccessLog
        "model",        // 实体变量名：accessLog
        "TableComment", // 表注释：访问日志表（无主键）
        "tableName",    // 去表前缀的蛇形表名：access_log
        "tableNameKebab", // 去表前缀的连字符名：access-log
        "key",          // 主键 Java 类型：Long

        // —— 表与类型的核心名字（产物类名不在这里：模板自己拼 `${table.namingBase}Mapper`）
        "table", "entity", "keyType", "controllerPath", "cfg",
        // 包：`{package}` 是本模板自己的包（按 output 路径推导），`{basePackageDir}` 是基础包名的目录形式
        "package", "basePackageDir",

        // —— 常用开关与列清单（想在大括号里写 #if / #foreach 时用）
        "columns", "tableColumns", "pkColumns", "singlePk",
        "hasPk", "hasQuery", "hasBatch", "generateCrud", "isMybatisPlus",

        // —— 杂项
        "author", "date", "api", "apiShort", "injectionAnnotation", "injectionImport",

        // —— 排版块（由 blocks/*.tpl 渲染，与模板方案目录并列在资源根下）
        "fields", "accessors", "mapperMethods"
    )

    /** 按长度倒序，避免 `{table}` 抢在 `{tableName}` 前面匹配；正则按词表缓存 */
    private val CACHE = ConcurrentHashMap<Set<String>, Regex>()

    private fun braceOf(names: Set<String>): Regex = CACHE.getOrPut(names) {
        val roots = names.filter { it.isNotEmpty() }.sortedByDescending { it.length }.joinToString("|")
        if (roots.isEmpty()) {
            NO_MATCH
        } else {
            Regex("(?<![\\$#\\\\])\\{(($roots)(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)\\}")
        }
    }

    /** 词表为空时用的正则：永不匹配，`lower` 直接返回原文 */
    private val NO_MATCH = Regex("(?!)")

    /** 模型顶层键 ∪ [NAMES]：模板能写的大括号名字就是它们 */
    fun namesOf(model: Map<String, Any?>): Set<String> =
        if (model.isEmpty()) {
            NAMES
        } else {
            NAMES + model.keys
        }

    /**
     * 把正文里的大括号占位符降级成 `${...}`；`#[[ ... ]]#` 原样输出区整段跳过。
     *
     * @param names 本次认可的根名字，通常传 [namesOf] 的结果
     */
    fun lower(source: String, names: Set<String> = NAMES): String {
        if (source.indexOf('{') < 0) {
            return source
        }
        val brace = braceOf(names)
        val sb = StringBuilder(source.length + 32)
        var index = 0
        while (index < source.length) {
            val rawStart = source.indexOf("#[[", index)
            if (rawStart < 0) {
                sb.append(lowerChunk(source, index, source.length, brace))
                break
            }
            val rawEnd = source.indexOf("]]#", rawStart + 3)
            if (rawEnd < 0) {
                sb.append(lowerChunk(source, index, source.length, brace))
                break
            }
            sb.append(lowerChunk(source, index, rawStart, brace))
            sb.append(source, rawStart, rawEnd + 3)
            index = rawEnd + 3
        }
        return sb.toString()
    }

    private fun lowerChunk(source: String, from: Int, to: Int, brace: Regex): String {
        if (from >= to) {
            return ""
        }
        val chunk = source.substring(from, to)
        if (chunk.indexOf('{') < 0) {
            return chunk
        }
        return brace.replace(chunk) { match ->
            "\${" + match.groupValues[1] + "}"
        }
    }
}
