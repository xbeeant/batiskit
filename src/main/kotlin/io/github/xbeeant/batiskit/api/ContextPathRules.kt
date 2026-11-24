package io.github.xbeeant.batiskit.api

/**
 * Spring `context path` 静态探测的纯逻辑（不依赖 IntelliJ 平台，便于单测）。
 *
 * 覆盖的写法：
 *  - Spring Boot 1.x：`server.context-path` / `server.contextPath`；
 *  - Spring Boot 2.x / 3.x（Servlet）：`server.servlet.context-path` / `server.servlet.contextPath`；
 *  - Spring WebFlux：`spring.webflux.base-path`；
 *  - relaxed binding：大小写、`-` / `_`、环境变量形式 `SERVER_SERVLET_CONTEXT_PATH` 全部归一化后匹配。
 *
 * `.properties` / `.yml` / `.yaml` 都读，多个 profile 文档、多模块会各自留一个候选；
 * 当前激活的 profile 无法静态确定，因此候选冲突时给出提示而不是假装知道。
 *
 * 配置中心（Nacos / Spring Cloud Config）里的值静态读不到：这里只负责识别到配置中心引用并打标记，
 * 由 [ContextPathResolver] 与面板提供工程级手动覆盖。
 */
object ContextPathRules {

    const val DEFAULT_BASE_URL = "http://localhost:8080"

    /** 归一化后的键 -> (说明, 优先级)。数字越大越优先（同份配置里同时写了 Boot 1 / Boot 2 键时） */
    private val KEYS = listOf(
        Key("serverservletcontextpath", "server.servlet.context-path（Boot 2.x / 3.x Servlet）", 100),
        Key("servercontextpath", "server.context-path（Boot 1.x）", 80),
        Key("springwebfluxbasepath", "spring.webflux.base-path（WebFlux）", 60)
    )

    private data class Key(val canonical: String, val label: String, val priority: Int)

    private val CONFIG_PROFILE = Regex("""^(?:application|bootstrap)-(.+)\.(?:properties|ya?ml)$""", RegexOption.IGNORE_CASE)

    // 注意不要收 spring.profiles.active：它常写在主配置里，只是声明激活哪个 profile，
    // 不代表「当前文档只对某个 profile 生效」
    private val PROFILE_KEYS = setOf(
        canonicalKey("spring.config.activate.on-profile"),
        canonicalKey("spring.profiles")
    )

    /** 一份待解析的配置文件（[path] 用于区分主源码 / 测试资源与多模块） */
    data class ConfigFile(val name: String, val text: String, val path: String = name) {
        private val extension: String get() = name.substringAfterLast('.', "").lowercase()
        val isProperties: Boolean get() = extension == "properties"
        val isYaml: Boolean get() = extension == "yml" || extension == "yaml"
        val isBootstrap: Boolean get() = name.startsWith("bootstrap", ignoreCase = true)
        val isTest: Boolean get() = path.contains("/src/test/") || path.contains("/test/")
        val profile: String? get() = CONFIG_PROFILE.find(name)?.groupValues?.get(1)
    }

    /** 一份配置里读到的候选值 */
    private data class Hit(
        val value: String,
        val keyLabel: String,
        val source: String,
        val profile: String?,
        val rank: Int,
        val unresolved: Boolean
    )

    /**
     * 探测结果。
     *
     * @param contextPath 归一化后的 context path（`""` 表示根上下文或没探到）
     * @param source 人类可读的来源说明
     * @param nacos 是否检测到配置中心引用（远端值静态读不到）
     * @param note 需要用户确认的提示；null 表示没有歧义
     * @param candidates 所有探测到的非空 context path，供 UI 展示
     */
    data class Detection(
        val contextPath: String = "",
        val source: String = "未检测到 context path 配置，按根上下文处理",
        val nacos: Boolean = false,
        val note: String? = null,
        val candidates: List<String> = emptyList()
    )

    // ------------------------------------------------------------------ 对外主入口

    /**
     * 汇总所有配置文件的候选值。
     *
     * 排序思路（越靠前越可信）：
     *  非 profile 的 `application.*` > profile-specific > `bootstrap.*` > 测试资源；
     *  同目录 `.properties` 略优于 `.yml`；同一候选在多处出现会小幅加分。
     * 由于无法知道运行时激活的 profile，候选不一致时只给提示，不擅自猜。
     *
     * @param buildTexts 构建脚本（pom.xml / build.gradle*）文本，只用来识别 Nacos 依赖
     */
    fun resolve(files: List<ConfigFile>, buildTexts: List<String> = emptyList()): Detection {
        val hits = mutableListOf<Hit>()
        var nacos = buildTexts.any { hasNacosDependency(it) }
        var bootstrap = false

        files.forEach { file ->
            if (hasNacosReference(file.text)) {
                nacos = true
            }
            if (file.isBootstrap) {
                bootstrap = true
            }
            hits += parseHits(file)
        }
        val unresolved = hits.any { it.unresolved }

        val usable = hits.filter { !it.unresolved && it.value.isNotEmpty() }
        val candidates = usable.map { it.value }.distinct()
        val groups = usable.groupBy { it.value }
        val best = groups.entries.maxWithOrNull(
            compareBy<Map.Entry<String, List<Hit>>> { score(it.value) }
                .thenBy { it.value.maxOf { hit -> hit.rank } }
                .thenByDescending { it.value.size }
        )
        val bestHit = best?.value?.maxByOrNull { it.rank }
        val contextPath = best?.key.orEmpty()
        val source = if (bestHit == null) {
            if (hits.any { !it.unresolved && it.value.isEmpty() }) {
                "配置文件里显式把 context path 设为空（根上下文）"
            } else {
                "未检测到 context path 配置，按根上下文处理"
            }
        } else {
            "${bestHit.source} · ${bestHit.keyLabel}"
        }

        val notes = mutableListOf<String>()
        when {
            nacos && contextPath.isNotEmpty() ->
                notes += "检测到 Nacos / Spring Cloud 配置中心引用：远端配置可能覆盖本地 context path，请确认"
            nacos ->
                notes += "检测到 Nacos / Spring Cloud 配置中心引用：context path 可能配置在远端，静态读取不到，请手动确认"
            bootstrap ->
                notes += "检测到 bootstrap 配置（Spring Cloud）：context path 可能在配置中心，请手动确认"
        }
        if (candidates.size > 1) {
            notes += "探测到多个不同的 context path（可能来自不同 profile / 模块）：" +
                candidates.joinToString("、") + "；已采用 " + (contextPath.ifEmpty { "根上下文" })
        }
        if (unresolved) {
            notes += "有配置使用 \${…} 占位符且无法静态解析，已忽略"
        }

        return Detection(contextPath, source, nacos, notes.takeIf { it.isNotEmpty() }?.joinToString("；"), candidates)
    }

    private fun score(hits: List<Hit>): Int = hits.maxOf { it.rank } + (hits.size - 1) * 3

    // ------------------------------------------------------------------ 单文件解析

    private fun parseHits(file: ConfigFile): List<Hit> {
        val documents: List<Map<String, String>> = when {
            file.isProperties -> listOf(parseProperties(file.text))
            file.isYaml -> parseYamlDocuments(file.text)
            else -> return emptyList()
        }
        val out = mutableListOf<Hit>()
        documents.forEachIndexed { index, doc ->
            val key = KEYS.firstOrNull { doc.containsKey(it.canonical) } ?: return@forEachIndexed
            val raw = doc.getValue(key.canonical)
            val resolved = resolvePlaceholders(raw, doc)
            val profile = profileOf(doc) ?: file.profile
            val source = file.name + if (documents.size > 1) "#${index + 1}" else ""
            out += Hit(
                value = resolved?.let(::normalize).orEmpty(),
                keyLabel = key.label,
                source = source,
                profile = profile,
                rank = rankOf(file, profile, key),
                unresolved = resolved == null
            )
        }
        return out
    }

    private fun rankOf(file: ConfigFile, profile: String?, key: Key): Int {
        var rank = key.priority
        if (profile == null) rank += 40
        if (!file.isBootstrap) rank += 30
        if (file.isProperties) rank += 10
        if (file.isTest) rank -= 60
        return rank
    }

    private fun profileOf(doc: Map<String, String>): String? =
        PROFILE_KEYS.firstNotNullOfOrNull { doc[it]?.takeIf(String::isNotBlank) }?.let(::unquote)

    /** `.properties` 解析：规范化键 -> 原始值（重复键后者覆盖前者，与 Spring 一致） */
    private fun parseProperties(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trimStart('\uFEFF').trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return@forEach
            val pair = splitProperty(line) ?: return@forEach
            out[canonicalKey(pair.first)] = unquote(pair.second)
        }
        return out
    }

    private fun splitProperty(line: String): Pair<String, String>? {
        var escaped = false
        for (i in line.indices) {
            val c = line[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\') {
                escaped = true
                continue
            }
            if (c == '=' || c == ':') {
                return line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
        }
        val ws = line.indexOfFirst { it.isWhitespace() }
        return if (ws < 0) null else line.substring(0, ws).trim() to line.substring(ws).trim()
    }

    /**
     * YAML 按 `---` 拆文档，缩进栈拼出点分全键。
     * 只处理映射（含 `{a: {b: c}}` 流式写法），序列 / 锚点 / 多行块不参与 context path 判定。
     */
    private fun parseYamlDocuments(text: String): List<Map<String, String>> {
        val documents = mutableListOf<Map<String, String>>()
        var current = LinkedHashMap<String, String>()
        val stack = mutableListOf<Pair<Int, String>>()

        text.lineSequence().forEach { raw ->
            val line = stripYamlComment(raw)
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            if (trimmed == "---") {
                if (current.isNotEmpty()) documents += current
                current = LinkedHashMap()
                stack.clear()
                return@forEach
            }
            if (trimmed == "..." || trimmed.startsWith("- ")) return@forEach

            val indent = line.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
            val colon = findYamlColon(trimmed)
            if (colon < 0) return@forEach
            val key = unquote(trimmed.substring(0, colon).trim())
            val value = trimmed.substring(colon + 1).trim()
            while (stack.isNotEmpty() && stack.last().first >= indent) {
                stack.removeAt(stack.lastIndex)
            }
            val full = (stack.map { it.second } + key).joinToString(".")
            if (value.isEmpty()) {
                stack += indent to key
            } else if (value.startsWith("{")) {
                parseFlowMap(value, full, current)
            } else {
                current[canonicalKey(full)] = unquote(value)
            }
        }
        if (current.isNotEmpty()) documents += current
        return documents
    }

    private fun parseFlowMap(text: String, prefix: String, out: MutableMap<String, String>) {
        val body = text.trim()
        if (!body.startsWith("{") || !body.endsWith("}")) return
        splitTopLevel(body.substring(1, body.length - 1), ',').forEach { part ->
            val colon = findYamlColon(part)
            if (colon < 0) return@forEach
            val key = unquote(part.substring(0, colon).trim())
            val value = part.substring(colon + 1).trim()
            val full = "$prefix.$key"
            if (value.startsWith("{")) {
                parseFlowMap(value, full, out)
            } else {
                out[canonicalKey(full)] = unquote(value)
            }
        }
    }

    private fun splitTopLevel(text: String, delimiter: Char): List<String> {
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        var quote: Char? = null
        text.forEach { c ->
            when {
                quote != null -> {
                    sb.append(c)
                    if (c == quote) quote = null
                }
                c == '\'' || c == '"' -> {
                    quote = c
                    sb.append(c)
                }
                c == '{' || c == '[' -> {
                    depth++
                    sb.append(c)
                }
                c == '}' || c == ']' -> {
                    depth--
                    sb.append(c)
                }
                c == delimiter && depth == 0 -> {
                    parts += sb.toString()
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) parts += sb.toString()
        return parts
    }

    private fun findYamlColon(text: String): Int {
        var quote: Char? = null
        for (i in text.indices) {
            val c = text[i]
            if (quote != null) {
                if (c == quote) quote = null
                continue
            }
            if (c == '\'' || c == '"') {
                quote = c
                continue
            }
            if (c == ':' && (i + 1 == text.length || text[i + 1].isWhitespace())) return i
        }
        return -1
    }

    private fun stripYamlComment(line: String): String {
        var quote: Char? = null
        for (i in line.indices) {
            val c = line[i]
            if (quote != null) {
                if (c == quote) quote = null
                continue
            }
            if (c == '\'' || c == '"') {
                quote = c
                continue
            }
            if (c == '#' && (i == 0 || line[i - 1].isWhitespace())) return line.substring(0, i)
        }
        return line
    }

    /** `${NAME:default}` / `${NAME}` 简单解析：同文件里找得到或写了默认值才认为可静态解析 */
    private fun resolvePlaceholders(raw: String, props: Map<String, String>): String? {
        var value = raw
        repeat(6) {
            val start = value.indexOf("\${")
            if (start < 0) return value
            val end = value.indexOf('}', start + 2)
            if (end < 0) return null
            val expr = value.substring(start + 2, end)
            val name = expr.substringBefore(':')
            val fallback = expr.takeIf { it.contains(':') }?.substringAfter(':')
            val replacement = props[canonicalKey(name)] ?: fallback ?: return null
            value = value.substring(0, start) + replacement + value.substring(end + 1)
        }
        return if (value.contains("\${")) null else value
    }

    // ------------------------------------------------------------------ 字符串工具

    /** 规范化配置键：去掉大小写与所有非字母数字字符，模拟 Spring Boot relaxed binding */
    fun canonicalKey(raw: String): String = raw.trim().lowercase().filter { it.isLetterOrDigit() }

    /** 归一化 context path：补前导 `/`、去尾斜杠、折叠重复斜杠；非法值视为空 */
    fun normalize(raw: String?): String {
        var value = unquote(raw?.trim().orEmpty())
        if (value.isEmpty()) return ""
        if (value.contains("://")) {
            value = value.substringAfter("://").substringAfter('/', "").let { if (it.isEmpty()) "" else "/$it" }
        }
        value = value.trim('/').replace(Regex("/{2,}"), "/")
        if (value.isEmpty()) return ""
        val result = "/$value"
        if (result.any { it.isWhitespace() || it == '?' || it == '#' || it == '$' || it == '{' || it == '}' }) {
            return ""
        }
        return result
    }

    /** 用户手填的接口地址：补 scheme、去尾斜杠；空白视为恢复默认 */
    fun normalizeBaseUrl(raw: String?): String {
        var value = raw?.trim().orEmpty()
        if (value.isEmpty()) return DEFAULT_BASE_URL
        if (!value.contains("://")) value = "http://$value"
        return value.trimEnd('/')
    }

    /** 主机 + context path + 接口路径 -> 完整 URL，三段的斜杠都归一化 */
    fun compose(baseUrl: String, contextPath: String, path: String): String {
        var url = baseUrl.trim().trimEnd('/')
        val prefix = normalize(contextPath)
        if (prefix.isNotEmpty()) url += prefix
        val suffix = path.trim()
        if (suffix.isNotEmpty()) url += if (suffix.startsWith("/")) suffix else "/$suffix"
        return url
    }

    /** 从完整地址里拆出主机与 context path（用于展示与手动覆盖回显） */
    fun splitBaseUrl(baseUrl: String): Pair<String, String> {
        val schemeEnd = baseUrl.indexOf("://")
        if (schemeEnd < 0) return baseUrl.trimEnd('/') to ""
        val rest = baseUrl.substring(schemeEnd + 3)
        val slash = rest.indexOf('/')
        if (slash < 0) return baseUrl.trimEnd('/') to ""
        val host = baseUrl.substring(0, schemeEnd + 3 + slash)
        return host to normalize(rest.substring(slash))
    }

    private fun unquote(value: String): String {
        val trimmed = value.trim()
        if (trimmed.length >= 2) {
            val first = trimmed.first()
            val last = trimmed.last()
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return trimmed.substring(1, trimmed.length - 1).trim()
            }
        }
        return trimmed
    }

    // ------------------------------------------------------------------ 配置中心识别

    /**
     * 配置文本里出现 Nacos / Spring Cloud Config 引用。
     * 只把「远端」当风险：本地 `spring.config.import=classpath:...` 不算；
     * `spring.cloud.nacos.discovery`（纯服务发现）也不算，只有带 config 的才算。
     */
    fun hasNacosReference(text: String): Boolean {
        val lower = text.lowercase()
        if (lower.contains("spring.cloud.nacos.config") ||
            lower.contains("shared-configs") ||
            lower.contains("extension-configs") ||
            lower.contains("configserver")
        ) {
            return true
        }
        if (lower.contains("spring.config.import")) {
            return lower.contains("nacos") || lower.contains("configserver") || lower.contains("optional:")
        }
        // yaml 嵌套写法：spring.cloud.nacos.config 被打散成 nacos: / config: 两层键
        return lower.contains("nacos") && lower.contains("config")
    }

    /** 构建脚本里出现 Nacos 配置中心依赖 */
    fun hasNacosDependency(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("spring-cloud-starter-alibaba-nacos-config") ||
            lower.contains("nacos-config-spring-boot-starter") ||
            lower.contains("nacos-config") ||
            (lower.contains("com.alibaba.cloud") && lower.contains("nacos"))
    }
}
