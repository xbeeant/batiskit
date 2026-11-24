package io.github.xbeeant.batiskit.sequence

/**
 * 时序图「基础类 / 工具类」判定规则：纯数据、零平台依赖，测试和两条分析链路共用。
 *
 * 名单原先写死在 [SequenceRules] 里，现在这份只保留**出厂默认值**；实际生效的名单来自
 * Settings → Tools → Batis Kit → 时序图（[SequenceDiagramSettings]），设置页的文本
 * 经 [parse] 落到这里。判定语义与旧版逐字一致：全限定名先按包前缀匹配，带包名但不命中
 * 前缀的类一律保留；解析不出包名时才用简单类名兜底；`anonymous` 永不跳过。
 */
data class BasicClassRules(
    /** 基础类 / 工具类所在的包前缀，命中即跳过 */
    val packagePrefixes: List<String> = DEFAULT_PACKAGE_PREFIXES,
    /** 全限定名解析不出来时的简单类名兜底 */
    val simpleNames: Set<String> = DEFAULT_SIMPLE_NAMES,
    /**
     * 永不跳过的例外：可写全限定名或简单名，优先级高于前两份名单。
     * 写全限定名时其简单类名一并保留——PSI 解析不出包名时也不会把这个类重新过滤掉。
     */
    val keepNames: Set<String> = emptySet(),
) {

    /** 例外里全限定名条目派生出的简单类名 */
    private val keepSimpleNames = keepNames.filter { it.contains('.') }
        .map { it.substringAfterLast('.') }.toSet()

    fun isBasic(qualified: String?, simple: String? = null): Boolean {
        val q = qualified?.trim().orEmpty()
        if (q == "anonymous" || q.startsWith("anonymous ")) return false
        val name = q.ifEmpty { simple?.trim().orEmpty() }
        if (keepNames.isNotEmpty()) {
            val short = q.substringAfterLast('.')
            if ((q.isNotEmpty() && q in keepNames) || short in keepNames ||
                name in keepNames || short in keepSimpleNames || name in keepSimpleNames
            ) return false
        }
        if (q.isNotEmpty()) {
            if (packagePrefixes.any { q.startsWith(it) }) return true
            // 带包名的业务类不按简单名判断，避免误杀 org.junit.Assert / 用户自己的 StringUtils
            if (q.contains('.')) return false
        }
        // 全限定名解析不出来时（只有简单名或为 null），按简单类名白名单兜底
        return name in simpleNames
    }

    companion object {

        /** 出厂包前缀：JDK / Kotlin 标准库 / 常见工具类库 */
        val DEFAULT_PACKAGE_PREFIXES = listOf(
            // JDK / JVM
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            // Kotlin 标准库（集合 / 字符串扩展、协程等）
            "kotlin.", "kotlinx.",
            // 常见工具类库
            "org.apache.commons.",
            "org.springframework.util.",
            "com.google.common.",
            "cn.hutool.",
        )

        /** 出厂简单名兜底：一眼就是基础类型 / 工具类 */
        val DEFAULT_SIMPLE_NAMES = setOf(
            // java.lang / 字符串
            "String", "StringBuilder", "StringBuffer", "CharSequence", "Math", "System",
            // java.util / 集合与流式
            "Objects", "Arrays", "Collections", "Collectors", "Optional", "OptionalInt",
            "OptionalLong", "OptionalDouble", "Iterator", "Iterable", "Collection",
            "List", "Map", "Set", "Queue", "Deque", "Entry",
            "ArrayList", "LinkedList", "HashMap", "LinkedHashMap", "TreeMap",
            "HashSet", "LinkedHashSet", "TreeSet", "Vector", "Hashtable", "ArrayDeque",
            "Stream", "IntStream", "LongStream", "DoubleStream",
            // 常见 *Utils 与 Kotlin 标准库的 *Kt 门面类
            "CollectionUtils", "StringUtils", "ObjectUtils", "ArrayUtils", "MapUtils",
            "ListUtils", "SetUtils", "NumberUtils", "BooleanUtils", "DateUtils",
            "BeanUtils", "ClassUtils", "IOUtils", "FileUtils", "DigestUtils",
            "CollectionsKt", "StringsKt", "ArraysKt", "MapsKt", "SetsKt", "ListsKt",
        )

        /** 默认规则：老调用方（含纯 JVM 测试）不带规则时用它，行为与硬编码时代一致 */
        val DEFAULT = BasicClassRules()

        val DEFAULT_PREFIXES_TEXT = DEFAULT_PACKAGE_PREFIXES.joinToString("\n")
        val DEFAULT_SIMPLE_NAMES_TEXT = DEFAULT_SIMPLE_NAMES.joinToString("\n")
        const val DEFAULT_KEEP_NAMES_TEXT = ""

        /** 设置页的三段文本 → 规则；某一段留空即表示该类名单不生效 */
        fun parse(prefixes: String, simpleNames: String, keepNames: String = ""): BasicClassRules =
            BasicClassRules(
                packagePrefixes = entries(prefixes),
                simpleNames = entries(simpleNames).toSet(),
                keepNames = entries(keepNames).toSet(),
            )

        /** 换行 / 逗号 / 分号分隔，支持 `#` 行内注释、空行与重复项；保持输入顺序 */
        fun entries(text: String): List<String> = text
            .split(Regex("[\r\n,;]+"))
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
