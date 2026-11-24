package io.github.xbeeant.batiskit.template

/**
 * 模板模型的版本与废弃键。
 *
 * 模型删键之后，旧的自建模板里写的 `$mapper` / `{entityFqn}` 不会报错——引擎把未定义变量
 * 渲染成空串，于是产物静默少了一段（CHANGELOG 里好几轮「产物名退出模型」都踩过这个坑）。
 * 这里把历次下线过的键记成一张表：生成预览、模板编辑器标注、设置页检查共用同一份扫描逻辑，
 * 升级后能直接告诉用户「第几行用了哪个已下线的键、应该改成什么」。
 */
object TemplateMigration {

    /** 当前模型版本；模板头部可写 `requires: 3` 声明自己依赖的模型 */
    const val CURRENT_VERSION = "3"

    data class Finding(
        val line: Int,
        val key: String,
        val hint: String
    ) {
        val message: String get() = "第 $line 行：模型键 $key 已下线 —— $hint"
    }

    /** 已下线的模型键 → 迁移建议 */
    val DEPRECATED_KEYS: Map<String, String> = linkedMapOf(
        "mapper" to "在头部用 #set 自己拼：`#set(\$mapper = \"\${table.namingBase}Mapper\")`",
        "mapperField" to "用 `\${mapper.uncap()}` 之类的字符串方法派生",
        "mapperXmlName" to "在头部 #set 自己拼 Mapper XML 文件名",
        "service" to "在头部 #set 自己拼：`#set(\$service = \"\${cfg.serviceInterfacePrefix}\${table.namingBase}Service\")`",
        "serviceField" to "用 `\${service.uncap()}` 派生",
        "serviceImpl" to "在头部 #set 自己拼 XxxServiceImpl",
        "controller" to "在头部 #set 自己拼 XxxRestController",
        "dto" to "写作 `\${table.namingBase}DTO`（产物名由模板自己拼）",
        "vo" to "写作 `\${table.namingBase}VO`",
        "queryDto" to "写作 `\${table.namingBase}QueryDTO`",
        "converter" to "写作 `\${table.namingBase}Converter`",
        "entityFqn" to "在头部声明：`#set(\$entityFqn = \"\${cfg.basePackage}.model.\${entity}\")`",
        "mapperFqn" to "在头部 #set 自己声明全限定名",
        "serviceFqn" to "在头部 #set 自己声明全限定名",
        "dtoFqn" to "在头部 #set 自己声明全限定名",
        "voFqn" to "在头部 #set 自己声明全限定名",
        "queryDtoFqn" to "在头部 #set 自己声明全限定名",
        "converterFqn" to "在头部 #set 自己声明全限定名",
        "queryType" to "QueryDTO 已是固定产物，直接删掉这个引用",
        "base" to "基类改由模板里一行 `#set(\$extends = \"全限定类名\")` 决定",
        "converterBase" to "转换器基类写在模板正文里",
        "repositoryDaoImports" to "import 归并交给 \$util，模型不再提供该键",
        "cfg.idGeneratorClass" to "ID 生成器写在模板正文里",
        "cfg.generateEntity" to "生成哪些产物由模板方案决定，配置项已下线",
        "cfg.generateMapper" to "生成哪些产物由模板方案决定，配置项已下线",
        "cfg.generateService" to "生成哪些产物由模板方案决定，配置项已下线",
        "cfg.generateController" to "生成哪些产物由模板方案决定，配置项已下线"
    )

    /** 变量引用：`$x` / `${x}` / `{x}`，也捕获 `cfg.xxx` 这类带点号的路径 */
    private val REFERENCE = Regex(
        """\$\{?([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\}?|\{([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\}"""
    )

    /** 扫描模板正文（不含头部），返回按行号排序、去重后的废弃键命中 */
    fun scan(body: String): List<Finding> {
        val out = mutableListOf<Finding>()
        val seen = HashSet<String>()
        body.lines().forEachIndexed { index, line ->
            REFERENCE.findAll(line).forEach { match ->
                val key = match.groupValues[1].ifBlank { match.groupValues[2] }
                val hint = DEPRECATED_KEYS[key] ?: return@forEach
                if (seen.add("$index:$key")) {
                    out += Finding(index + 1, key, hint)
                }
            }
        }
        return out
    }

    /** 头部 `requires:` 与当前模型版本不符时的提示 */
    fun versionWarning(spec: TemplateSpec): String? {
        val required = spec.requires.trim()
        if (required.isEmpty() || required == CURRENT_VERSION) return null
        return "模板 ${spec.fileName} 声明 requires: $required，当前模型版本是 $CURRENT_VERSION，" +
            "请核对是否用到已下线的模型键"
    }

    /** 生成预览 / 设置页检查用：一个模板的全部问题 */
    fun inspect(spec: TemplateSpec): List<String> = buildList {
        versionWarning(spec)?.let { add(it) }
        scan(spec.body).take(6).forEach { add("${spec.fileName} ${it.message}") }
        val total = scan(spec.body).size
        if (total > 6) add("${spec.fileName} 另有 ${total - 6} 处废弃键，可在模板编辑器里查看")
    }
}
