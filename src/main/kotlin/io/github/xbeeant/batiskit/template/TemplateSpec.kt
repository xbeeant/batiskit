package io.github.xbeeant.batiskit.template

/**
 * 一个模板的头部声明 + 正文。
 *
 * 头部用 front-matter 的写法（`---` 包围的 `key: value`），刻意选了这种形态：
 * 一个 `.tpl` 文件自带「叫什么、生成到哪、怎么合并」，用户新增模板只需要新建一个文件，
 * 不用回到代码里改映射表 —— 这正是模板驱动要解决的问题。
 *
 * 头部里的值本身也是模板串，渲染后才生效，因此可以写
 * `output: ${pkg.entity}/${entity}.java`、`enabled: ${cfg.generateXxx}` 这种依赖配置的声明。
 */
data class TemplateSpec(
    /** 唯一标识，内置模板用它做覆盖与排序 */
    val id: String,
    /** 勾选框里显示的名字 */
    val name: String,
    /** 生成到哪：相对路径模板，如 `${pkg.entity}/${entity}.java` */
    val output: String,
    /** `java` 写到源码根、`resources` 写到资源根 */
    val root: String = ROOT_JAVA,
    /** 合并策略：`java`（按签名补缺）/ `xml`（按 id 补缺）/ `overwrite`（直接覆盖） */
    val merge: String = MERGE_JAVA,
    /** 是否需要生成：模板串，渲染结果按真值判定；不写则恒为 true */
    val enabled: String = "true",
    /**
     * 所属模板方案（套）。留空表示「按 [framework] 归入内置套」：
     * `framework: any` 的模板同时属于 MyBatis / MyBatis-Plus 两套，其余各归各套。
     * 写死一个自定义套名，则只在该套里产出（用于「一整套自己的模板」）。
     */
    val set: String = "",
    /** 适用框架：`any` / `mybatis` / `mybatis-plus`，套内不再二次过滤（归属见 [set]） */
    val framework: String = FRAMEWORK_ANY,
    /** 分组，弹窗里按它归类 */
    val group: String = "",
    /**
     * 依赖的模板模型版本（见 [TemplateMigration.CURRENT_VERSION]）。
     * 留空表示不声明；插件升级、模型删键后会据此提示作者核对模板。
     */
    val requires: String = "",
    /** 同级排序，小的在前 */
    val order: Int = 100,
    /** 界面上能否删除/编辑（内置模板只读，可被同名 id 的用户模板覆盖） */
    val builtin: Boolean = false,
    /** 来源文件名，报错时定位用 */
    val fileName: String = "template",
    /** 去掉头部之后的模板正文 */
    val body: String = "",
    /**
     * front-matter 的原始文本（不含首尾 `---`）。字段值仍解析进上面的字段供生成逻辑使用，
     * 这里额外留一份原文，导出模板方案时才能保住注释、空行与字段顺序。
     */
    val header: String = ""
) {

    /**
     * 还原成可再解析的模板全文。头部有原文时**逐字保留**（注释 / 空行 / 字段顺序 / group / order），
     * 只有纯代码构造、没有原文的 spec 才按字段重建。
     *
     * 内置模板经 [parse] 后同样带原文，因此导出方案不会再把头部压成固定几行。
     */
    fun rawText(): String = buildString {
        append(DELIMITER).append('\n')
        val head = if (header.isNotEmpty()) header else rebuiltHeader()
        append(head)
        if (!head.endsWith("\n")) append('\n')
        append(DELIMITER).append('\n')
        append(body)
    }

    private fun rebuiltHeader(): String = buildString {
        append("id: ").append(id).append('\n')
        append("name: ").append(name).append('\n')
        if (group.isNotBlank()) append("group: ").append(group).append('\n')
        if (order != 100) append("order: ").append(order).append('\n')
        append("output: ").append(output).append('\n')
        append("root: ").append(root).append('\n')
        append("merge: ").append(merge).append('\n')
        if (framework != FRAMEWORK_ANY) append("framework: ").append(framework).append('\n')
        if (enabled != "true") append("enabled: ").append(enabled).append('\n')
        if (set.isNotBlank()) append("set: ").append(set).append('\n')
        if (requires.isNotBlank()) append("requires: ").append(requires).append('\n')
    }.trimEnd('\n')

    companion object {

        const val ROOT_JAVA = "java"
        const val ROOT_RESOURCES = "resources"

        const val MERGE_JAVA = "java"
        const val MERGE_XML = "xml"
        const val MERGE_OVERWRITE = "overwrite"

        private const val DELIMITER = "---"

        const val FRAMEWORK_ANY = "any"
        const val FRAMEWORK_MYBATIS = "mybatis"
        const val FRAMEWORK_MYBATIS_PLUS = "mybatis-plus"

        private val KEYS = setOf(
            "id", "name", "output", "root", "merge", "enabled", "set", "framework",
            "group", "order", "requires"
        )

        /** 头部值允许带行内注释（`order: 10  # 小的在前`）；` #` 之后的内容不进字段值，但原文保留 */
        private val INLINE_COMMENT = Regex("""\s+#.*$""")

        /**
         * 解析模板文本。
         *
         * 头部缺失或格式不对时不抛异常，而是返回可见的默认值并给出一条错误——
         * 用户手写模板时最怕「保存了但没生效也不说为什么」。
         */
        fun parse(text: String, fileName: String = "template", builtin: Boolean = false): ParseResult {
            val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
            val lines = normalized.split("\n")
            if (lines.firstOrNull()?.trim() != DELIMITER) {
                return ParseResult(
                    spec = fallback(normalized, fileName, builtin),
                    error = "模板缺少头部声明：文件第一行必须是 ---"
                )
            }
            val header = mutableMapOf<String, String>()
            var end = -1
            for (index in 1 until lines.size) {
                val line = lines[index]
                if (line.trim() == DELIMITER) {
                    end = index
                    break
                }
                // 头部允许整行注释（# 开头）与空行：解析时跳过，原文照样留进 header
                if (line.isBlank() || line.trimStart().startsWith("#")) {
                    continue
                }
                val colon = line.indexOf(':')
                if (colon <= 0) {
                    return ParseResult(
                        spec = fallback(normalized, fileName, builtin),
                        error = "第 ${index + 1} 行的头部声明不是 key: value 形式"
                    )
                }
                val key = line.substring(0, colon).trim()
                if (key !in KEYS) {
                    return ParseResult(
                        spec = fallback(normalized, fileName, builtin),
                        error = "第 ${index + 1} 行出现未知的头部字段 $key（可用：${KEYS.joinToString(", ")}）"
                    )
                }
                header[key] = INLINE_COMMENT.replace(line.substring(colon + 1).trim(), "")
            }
            if (end < 0) {
                return ParseResult(
                    spec = fallback(normalized, fileName, builtin),
                    error = "模板头部没有结束标记 ---"
                )
            }
            val id = header["id"].orEmpty()
            if (id.isBlank()) {
                return ParseResult(
                    spec = fallback(normalized, fileName, builtin),
                    error = "模板头部缺少 id"
                )
            }
            val output = header["output"].orEmpty()
            if (output.isBlank()) {
                return ParseResult(
                    spec = fallback(normalized, fileName, builtin),
                    error = "模板头部缺少 output（生成到哪）"
                )
            }
            val root = header["root"]?.lowercase().orEmpty().ifBlank { ROOT_JAVA }
            if (root != ROOT_JAVA && root != ROOT_RESOURCES) {
                return ParseResult(
                    spec = fallback(normalized, fileName, builtin),
                    error = "root 只能是 java 或 resources，实际是 $root"
                )
            }
            val merge = header["merge"]?.lowercase().orEmpty().ifBlank { defaultMerge(root) }
            if (merge !in setOf(MERGE_JAVA, MERGE_XML, MERGE_OVERWRITE)) {
                return ParseResult(
                    spec = fallback(normalized, fileName, builtin),
                    error = "merge 只能是 java / xml / overwrite，实际是 $merge"
                )
            }
            val framework = header["framework"]?.lowercase().orEmpty().ifBlank { FRAMEWORK_ANY }
            if (framework !in setOf(FRAMEWORK_ANY, FRAMEWORK_MYBATIS, FRAMEWORK_MYBATIS_PLUS)) {
                return ParseResult(
                    spec = fallback(normalized, fileName, builtin),
                    error = "framework 只能是 any / mybatis / mybatis-plus，实际是 $framework"
                )
            }
            // set 不参与模板渲染（仓库分组发生在有模型之前），按字面量处理
            val set = header["set"].orEmpty().trim()
            if (set.contains(' ')) {
                return ParseResult(
                    spec = fallback(normalized, fileName, builtin),
                    error = "set 是模板方案名，不能包含空格（实际是 $set）"
                )
            }
            val spec = TemplateSpec(
                id = id,
                name = header["name"].orEmpty().ifBlank { id },
                output = output,
                root = root,
                merge = merge,
                enabled = header["enabled"].orEmpty().ifBlank { "true" },
                set = set,
                framework = framework,
                group = header["group"].orEmpty().ifBlank { "自定义" },
                requires = header["requires"].orEmpty().trim(),
                order = header["order"]?.toIntOrNull() ?: 100,
                builtin = builtin,
                fileName = fileName,
                body = lines.drop(end + 1).joinToString("\n"),
                header = lines.subList(1, end).joinToString("\n")
            )
            return ParseResult(spec, null)
        }

        private fun defaultMerge(root: String): String =
            if (root == ROOT_RESOURCES) MERGE_XML else MERGE_JAVA

        /** 头部解析失败时的兜底 spec：id 用文件名，保证列表里能看到它并显示错误 */
        private fun fallback(text: String, fileName: String, builtin: Boolean) = TemplateSpec(
            id = fileName.substringBeforeLast('.'),
            name = fileName,
            output = "",
            builtin = builtin,
            fileName = fileName,
            body = text
        )
    }

    /** 解析结果：spec 一定有值，error 非空表示这份模板不能用 */
    class ParseResult(val spec: TemplateSpec, val error: String?)
}
