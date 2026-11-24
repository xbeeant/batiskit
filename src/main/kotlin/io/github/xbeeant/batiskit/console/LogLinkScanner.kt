package io.github.xbeeant.batiskit.console

/**
 * 日志行里的可导航引用（纯逻辑层，无平台依赖）：
 *
 * - [Kind.URL]：`https://...` —— 浏览器打开；
 * - [Kind.JAVA_CLASS]：`com.example.pkg.ClassName` —— 快速导航到类；
 * - [Kind.JAVA_METHOD]：`com.example.pkg.ClassName.method` —— 跳到方法 / 字段；
 * - [Kind.FILE_PATH]：`src/main/kotlin/.../Foo.kt:42` —— 打开文件并定位行号。
 *
 * 控制台里平台自带的过滤器已经处理了 URL、绝对路径与 `at …` 堆栈行，
 * 所以控制台侧只补平台不认的引用；工具窗口（只读副本，平台不做链接）则四类全用。
 * 识别出的区间交给平台侧解析成 hyperlink，或给工具窗口做「跳转」菜单。
 */
object LogLinkScanner {

    enum class Kind(val label: String) {
        URL("链接"),
        JAVA_CLASS("Java 类"),
        JAVA_METHOD("Java 方法"),
        FILE_PATH("文件路径"),
    }

    data class Link(val kind: Kind, val span: Span, val text: String) {
        val start: Int get() = span.start
        val end: Int get() = span.end
    }

    /** URL：从 http(s) 开头吃到空白 / 常见包裹符为止 */
    private val URL_REFERENCE = Regex("""\bhttps?://[^\s"'<>()\\]+""")

    /**
     * 项目相对路径 + 可选行号：`src/main/kotlin/.../Foo.kt:42`、`./config/app.yml`。
     * 要求至少有一段目录分隔，后缀白名单避免把 `a.b.c` 这种句子切碎。
     */
    private val FILE_REFERENCE = Regex(
        """(?<![\w/\\])(?:\.{0,2}/)?[\w\-./\\]+/(?:[\w\-./\\]+\.(?:java|kt|kts|xml|yml|yaml|properties|gradle|sql|tpl|json|md|txt))(?::(\d{1,5}))?"""
    )

    /**
     * Java 引用：至少两段小写包名 + 大写类名，可选再跟一段标识符（方法 / 字段）。
     * 堆栈行 `at com.e.Demo.run(Demo.java:42)` 平台已处理，这里靠 [LogLineParser.isContinuation] 排除。
     */
    private val JAVA_REFERENCE = Regex(
        """(?<![\w$.])((?:[a-z][A-Za-z0-9_]*\.)+)([A-Z][A-Za-z0-9_]*)((?:\.[A-Z][A-Za-z0-9_]*)*)(?:\.([A-Za-z_][A-Za-z0-9_]*))?"""
    )

    fun scan(line: String): List<Link> {
        if (line.isEmpty() || line.length > MAX_LINE) return emptyList()
        val result = ArrayList<Link>()
        val taken = ArrayList<Span>()
        fun overlaps(span: Span): Boolean = taken.any { it.start < span.end && span.start < it.end }

        for (match in URL_REFERENCE.findAll(line)) {
            val span = Span(match.range.first, match.range.last + 1)
            if (!overlaps(span)) {
                taken += span
                result += Link(Kind.URL, span, match.value)
            }
        }
        // 堆栈行里的 FQN / 路径交给平台的堆栈链接，这里只认正文
        val body = if (LogLineParser.isContinuation(line)) {
            ""
        } else {
            line
        }
        if (body.isNotEmpty()) {
            for (match in FILE_REFERENCE.findAll(body)) {
                val span = Span(match.range.first, match.range.last + 1)
                if (!overlaps(span)) {
                    taken += span
                    result += Link(Kind.FILE_PATH, span, match.value)
                }
            }
            for (match in JAVA_REFERENCE.findAll(body)) {
                val span = Span(match.range.first, match.range.last + 1)
                if (overlaps(span)) continue
                val packages = match.groupValues[1].dropLast(1)
                val className = match.groupValues[2] + match.groupValues[3]
                val member = match.groupValues[4]
                if (packages.isEmpty() || className.isBlank()) continue
                val kind = if (member.isNotBlank() && !isLikelyFileSuffix(member)) {
                    Kind.JAVA_METHOD
                } else {
                    Kind.JAVA_CLASS
                }
                taken += span
                result += Link(kind, span, match.value)
            }
        }
        return result.sortedBy { it.start }
    }

    /** `Demo.run` 里的 `run` 是方法；`com.e.Demo.java` 里被引用正则误挂上的 `java` 是文件后缀 */
    private fun isLikelyFileSuffix(member: String): Boolean = member in FILE_SUFFIX_WORDS

    private val FILE_SUFFIX_WORDS = setOf(
        "java", "kt", "kts", "xml", "yml", "yaml", "properties", "json", "sql", "txt", "md",
    )

    private const val MAX_LINE = 4000
}
