package io.github.xbeeant.batiskit.setter

/**
 * setter 参数的默认值表达式。
 *
 * 表达式里的类一律写**全限定名**：目标文件有没有 import 都能编译；写回源码时由
 * `JavaCodeStyleManager.shortenClassReferences` 缩短并补 import。
 * 这一层是纯 Kotlin（不碰 PSI），可以直接用普通 JUnit 断言每种类型的默认值。
 */

/**
 * PSI 侧才知道的信息（纯逻辑拿不到，但决定默认值能否构造）。
 *
 * @param qualifiedName 解析到的类全限定名；解析不到（泛型变量 / 未 import 的类）为 null
 * @param enumConstants 枚举常量清单，按声明顺序；非空即代表这是枚举
 * @param instantiable  能否用 `new X()` 构造：非接口 / 非抽象 / 非枚举 / 非注解、
 *                      非「需要外层实例」的非静态内部类，且有可访问的无参构造
 */
data class TypeHint(
    val qualifiedName: String? = null,
    val enumConstants: List<String> = emptyList(),
    val instantiable: Boolean = false
) {
    val isEnum: Boolean get() = enumConstants.isNotEmpty()
}

/**
 * 一个默认值表达式的三种写法信息。
 *
 * - [qualifiedText]：全限定名，永远能编译（import 冲突 / 补不上 import 时用它兜底）
 * - [text]：短名，配合 [imports] 使用，是最终写进源码的默认形态
 * - [imports]：需要补的 import（`java.lang.*` 不需要）
 */
data class DefaultExpression(
    val text: String,
    val qualifiedText: String,
    val imports: List<String>
)

object SetterDefaults {

    /** 基本类型 / 包装类 / 字符串 / 数值：按 Java 的「零值」语义给，而不是一律 null */
    private val SCALARS = mapOf(
        "byte" to "0", "short" to "0", "int" to "0", "long" to "0L",
        "float" to "0.0F", "double" to "0.0D", "char" to "'\\0'", "boolean" to "false",
        "Byte" to "(byte) 0", "Short" to "(short) 0", "Integer" to "0", "Long" to "0L",
        "Float" to "0.0F", "Double" to "0.0D", "Character" to "'\\0'", "Boolean" to "false",
        "String" to "\"\"", "CharSequence" to "\"\"",
        "StringBuilder" to "new java.lang.StringBuilder()",
        "StringBuffer" to "new java.lang.StringBuffer()",
        "BigDecimal" to "java.math.BigDecimal.ZERO",
        "BigInteger" to "java.math.BigInteger.ZERO",
        "Object" to "new java.lang.Object()",
        "Class" to "java.lang.Object.class",
        "UUID" to "java.util.UUID.randomUUID()",
        "Optional" to "java.util.Optional.empty()",
        "OptionalInt" to "java.util.OptionalInt.empty()",
        "OptionalLong" to "java.util.OptionalLong.empty()",
        "OptionalDouble" to "java.util.OptionalDouble.empty()",
        "File" to "new java.io.File(\"\")"
    )

    /** `java.sql.Date` / `Time` / `Timestamp` 没有无参构造，单独处理 */
    private val SQL_DATES = mapOf(
        "java.sql.Date" to "new java.sql.Date(System.currentTimeMillis())",
        "java.sql.Time" to "new java.sql.Time(System.currentTimeMillis())",
        "java.sql.Timestamp" to "new java.sql.Timestamp(System.currentTimeMillis())"
    )

    private val JAVA_TIME = mapOf(
        "LocalDate" to "java.time.LocalDate.now()",
        "LocalTime" to "java.time.LocalTime.now()",
        "LocalDateTime" to "java.time.LocalDateTime.now()",
        "Instant" to "java.time.Instant.now()",
        "ZonedDateTime" to "java.time.ZonedDateTime.now()",
        "OffsetDateTime" to "java.time.OffsetDateTime.now()",
        "OffsetTime" to "java.time.OffsetTime.now()",
        "Duration" to "java.time.Duration.ZERO",
        "Period" to "java.time.Period.ZERO",
        "Year" to "java.time.Year.now()",
        "YearMonth" to "java.time.YearMonth.now()"
    )

    private val OTHER_DATES = mapOf(
        "Date" to "new java.util.Date()",
        "Calendar" to "java.util.Calendar.getInstance()",
        "GregorianCalendar" to "new java.util.GregorianCalendar()",
        "TimeZone" to "java.util.TimeZone.getDefault()"
    )

    /** 集合接口 / 实现 -> 一个可写的空实现（带 `<>`，让 setter 的参数类型推断泛型实参） */
    private val COLLECTIONS = mapOf(
        "List" to "ArrayList", "Collection" to "ArrayList", "Iterable" to "ArrayList",
        "ArrayList" to "ArrayList", "LinkedList" to "LinkedList",
        "Set" to "HashSet", "HashSet" to "HashSet", "LinkedHashSet" to "LinkedHashSet",
        "SortedSet" to "TreeSet", "NavigableSet" to "TreeSet", "TreeSet" to "TreeSet",
        "Map" to "HashMap", "HashMap" to "HashMap", "LinkedHashMap" to "LinkedHashMap",
        "SortedMap" to "TreeMap", "NavigableMap" to "TreeMap", "TreeMap" to "TreeMap",
        "ConcurrentMap" to "ConcurrentHashMap", "ConcurrentHashMap" to "ConcurrentHashMap",
        "Queue" to "ArrayDeque", "Deque" to "ArrayDeque", "ArrayDeque" to "ArrayDeque",
        "Vector" to "Vector", "Stack" to "Stack", "Hashtable" to "Hashtable"
    )

    /** 没有泛型的 java.util 类，不能用菱形操作符 */
    private val NON_GENERIC_UTIL = setOf("Properties", "BitSet")

    /**
     * 全限定类名：`java.math.BigDecimal` / `com.example.Status` / `java.util.ArrayList`。
     * 左侧的 lookbehind 防止把 `Status.ENABLED` 里的 `tatus.ENABLED` 误当成包名。
     */
    private val FQN = Regex("(?<![\\w.])[a-z][\\w]*(?:\\.[a-z][\\w]*)*\\.[A-Z][\\w]*")

    /**
     * 在 [expression] 的全限定写法之上，算出短名写法与需要补的 import。
     * 生成器按「能否补 import」在两者之间选，冲突时自动退回全限定写法。
     */
    fun defaultFor(typeText: String, hint: TypeHint = TypeHint()): DefaultExpression {
        val qualified = expression(typeText, hint)
        val used = FQN.findAll(qualified).map { it.value }.distinct().toList()
        var short = qualified
        used.sortedByDescending { it.length }.forEach { fqn ->
            short = short.replace(fqn, fqn.substringAfterLast('.'))
        }
        return DefaultExpression(short, qualified, used.filterNot { it.startsWith("java.lang.") })
    }

    /**
     * 按类型文本推导默认值表达式（全限定写法）。
     *
     * @param typeText `PsiType.canonicalText`（如 `java.util.List<java.lang.String>`、`int[]`、`BigDecimal`）
     */
    fun expression(typeText: String, hint: TypeHint = TypeHint()): String {
        val text = typeText.trim()
        if (text.isEmpty() || text == "null" || text == "void" || text == "Void") {
            return "null"
        }

        // 数组：new int[0] / new java.lang.String[0] / new int[0][0]
        Regex("(?:\\[\\s*])+$").find(text)?.let { match ->
            val dimensions = match.value.count { it == '[' }
            val component = text.substring(0, match.range.first)
            return "new $component${"[0]".repeat(dimensions)}"
        }

        // 去掉泛型实参，剩下的才是要判断的类型
        val base = text.substringBefore('<').trim()
        val simple = base.substringAfterLast('.')

        // java.util.Date 与 java.sql.Date 简名相同，先判全限定名
        SQL_DATES[base]?.let { return it }

        SCALARS[simple]?.let { return it }
        JAVA_TIME[simple]?.let { return it }
        OTHER_DATES[simple]?.let { return it }

        // 只认 java.util.* 或没带包的简名；`com.foo.ArrayList` 是别的类，不能换成 JDK 的实现
        if (base.startsWith("java.util.") || !base.contains('.')) {
            COLLECTIONS[simple]?.let { return "new java.util.$it<>()" }
            if (simple in NON_GENERIC_UTIL) {
                return "new java.util.$simple()"
            }
        }

        // 枚举取第一个常量
        if (hint.isEnum) {
            return "${hint.qualifiedName ?: base}.${hint.enumConstants.first()}"
        }

        // 未知 POJO：能安全 new 就 new，否则不猜，交给 null（保证生成的代码能编译）
        return if (hint.instantiable) "new ${hint.qualifiedName ?: base}()" else "null"
    }
}
