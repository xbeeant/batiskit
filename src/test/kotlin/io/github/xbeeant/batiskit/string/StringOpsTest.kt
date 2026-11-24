package io.github.xbeeant.batiskit.string

import io.github.xbeeant.batiskit.template.Template
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 字符串处理的纯逻辑测试：不依赖 IDE 运行环境，编辑器菜单 / 工具窗口 / 模板函数共用这一套断言。
 */
class StringOpsTest {

    private fun apply(id: String, input: String, options: StringOptions = StringOptions()): String =
        StringOps.apply(id, input, options)

    // ------------------------------------------------------------------ 操作目录

    @Test
    fun `操作目录 id 唯一且分组齐全`() {
        assertEquals(StringOps.ALL.size, StringOps.ALL.map { it.id }.toSet().size)
        assertEquals(StringOps.ALL.size, StringOpGroup.values().sumOf { StringOps.of(it).size })
        assertNotNull(StringOps.byId("camel"))
        assertNull(StringOps.byId("not-exists"))
    }

    @Test
    fun `未知操作给出明确错误`() {
        try {
            apply("not-exists", "x")
            fail("应当抛出 StringOpException")
        } catch (ex: StringOpException) {
            assertTrue(ex.message!!.contains("not-exists"))
        }
    }

    @Test
    fun `预览不会把异常抛到 UI`() {
        val op = StringOps.byId("base64Decode")!!
        assertTrue(StringOps.preview(op, "!!!").contains("解码失败"))
    }

    // ------------------------------------------------------------------ 大小写

    @Test
    fun `全部大写与全部小写`() {
        assertEquals("HELLO WORLD", apply("upper", "hello World"))
        assertEquals("hello world", apply("lower", "Hello WORLD"))
    }

    @Test
    fun `句首 单词首字母 大小写互换与交替`() {
        assertEquals("Hello world", apply("capitalize", "hello world"))
        assertEquals("User Name 2", apply("titleCase", "user name 2"))
        assertEquals("hELLO wORLD", apply("invertCase", "Hello World"))
        assertEquals("hElLo", apply("alternatingCase", "hello"))
    }

    // ------------------------------------------------------------------ 命名风格

    @Test
    fun `驼峰 帕斯卡 下划线 常量 连字符 点号 空格`() {
        assertEquals("userName", apply("camel", "user_name"))
        assertEquals("UserName", apply("pascal", "user_name"))
        assertEquals("user_name", apply("snake", "userName"))
        assertEquals("USER_NAME", apply("screamingSnake", "userName"))
        assertEquals("user-name", apply("kebab", "userName"))
        assertEquals("user.name", apply("dot", "userName"))
        assertEquals("user name", apply("spaced", "userName"))
    }

    @Test
    fun `缩写与数字边界也能拆词`() {
        assertEquals("http_server", apply("snake", "HTTPServer"))
        assertEquals("xml-http-request", apply("kebab", "XMLHttpRequest"))
        assertEquals("user2Name", apply("camel", "user2Name"))
        assertEquals("helloWorld", apply("camel", "hello world"))
    }

    @Test
    fun `命名风格轮换能走完一圈`() {
        var value = "userName"
        val seen = mutableListOf<String>()
        repeat(7) {
            value = apply("switchCase", value)
            seen += value
        }
        assertEquals(
            listOf("UserName", "user_name", "USER_NAME", "user-name", "user.name", "user name", "userName"),
            seen
        )
    }

    @Test
    fun `非 ASCII 文本不会被命名转换拆坏`() {
        // 中文没有大小写边界，各种命名风格都应原样保留，不能出现乱码或被拆成代理项
        assertEquals("用户名称", apply("camel", "用户名称"))
        assertEquals("用户名称", apply("snake", "用户名称"))
        assertEquals("用户名称", apply("pascal", "用户名称"))
    }

    // ------------------------------------------------------------------ 编解码

    @Test
    fun `URL 编码用百分号而不是加号`() {
        assertEquals("a%20b%E4%B8%AD", apply("urlEncode", "a b中"))
        assertEquals("a b中", apply("urlDecode", "a%20b%E4%B8%AD"))
    }

    @Test
    fun `URL 解码保留字面加号`() {
        assertEquals("a+b", apply("urlDecode", "a+b"))
    }

    @Test
    fun `Base64 按 UTF-8 往返`() {
        assertEquals("5Lit5paH", apply("base64Encode", "中文"))
        assertEquals("中文", apply("base64Decode", "5Lit5paH"))
    }

    @Test
    fun `Base64 容忍缺填充 换行与 URL-safe 字符`() {
        assertEquals("hello", apply("base64Decode", "aGVsbG8"))
        assertEquals("hello", apply("base64Decode", "aGVs\n bG8="))
    }

    @Test
    fun `解码失败抛可读错误而不是产出乱码`() {
        assertOpFails { apply("base64Decode", "!!!") }
        assertOpFails { apply("urlDecode", "%ZZ") }
    }

    @Test
    fun `Unicode 转义往返`() {
        assertEquals("\\u4E2D\\u6587A", apply("unicodeEscape", "中文A"))
        assertEquals("中文A", apply("unicodeUnescape", "\\u4E2D\\u6587A"))
    }

    @Test
    fun `HTML 转义与数字实体反转义`() {
        assertEquals("&lt;a href=&quot;x&quot;&gt;&amp;", apply("htmlEscape", "<a href=\"x\">&"))
        assertEquals("<a>'中", apply("htmlUnescape", "&lt;a&gt;&#39;&#x4E2D;"))
    }

    @Test
    fun `Java 字符串转义往返`() {
        val raw = "a\nb\t\"c\"\\d"
        assertEquals(raw, apply("javaUnescape", apply("javaEscape", raw)))
        assertEquals("a\\nb", apply("javaEscape", "a\nb"))
    }

    // ------------------------------------------------------------------ 行操作

    @Test
    fun `行排序保留末尾换行`() {
        assertEquals("a\nb\nc\n", apply("sortAsc", "b\na\nc\n"))
    }

    @Test
    fun `行排序保留 CRLF 换行符`() {
        assertEquals("a\r\nb\r\n", apply("sortAsc", "b\r\na\r\n"))
    }

    @Test
    fun `忽略大小写与降序`() {
        assertEquals(
            "cherry\nbanana\nApple",
            apply("sortAsc", "banana\nApple\ncherry", StringOptions(ignoreCase = true, descending = true))
        )
    }

    @Test
    fun `自然排序按数值比较数字段`() {
        assertEquals("item1\nitem2\nitem10", apply("naturalSort", "item10\nitem2\nitem1"))
    }

    @Test
    fun `去重保留首次出现的行`() {
        assertEquals("a\nA\nb\n", apply("distinct", "a\nA\nb\na\n"))
        assertEquals("a\nb\n", apply("distinct", "a\nA\nb\na\n", StringOptions(ignoreCase = true)))
    }

    @Test
    fun `相邻去重 排序并去重 反转行序`() {
        assertEquals("a\nb\na\n", apply("distinctAdjacent", "a\na\nb\na\n"))
        assertEquals("a\nb\n", apply("sortAndDistinct", "b\na\nb\n"))
        assertEquals("b\na\n", apply("reverseLines", "a\nb\n"))
        assertEquals("cba\ned\n", apply("reverseEach", "abc\nde\n"))
    }

    @Test
    fun `拼接与拆分按分隔符`() {
        assertEquals("a | b | c", apply("join", "a\nb\nc\n", StringOptions(separator = " | ")))
        assertEquals("a\nb\nc", apply("join", "a\nb\nc", StringOptions(separator = "\\n")))
        assertEquals("a\nb\nc", apply("split", "a, b ,c", StringOptions(splitSeparator = ",")))
        assertEquals("a\nb\nc", apply("split", "a  b\nc", StringOptions(splitSeparator = "")))
    }

    @Test
    fun `跳过空行与 trim 选项生效`() {
        assertEquals("a|b", apply("join", "a\n  \n b ", StringOptions(separator = "|")))
        assertEquals(
            "a|  | b ",
            apply("join", "a\n  \n b ", StringOptions(separator = "|", skipEmpty = false, trimItems = false))
        )
    }

    @Test
    fun `逐行 trim 去空行 行编号`() {
        assertEquals("a\n\nb\n", apply("trimLines", "  a  \n   \n b \n"))
        assertEquals("a\nb\n", apply("removeEmptyLines", "a\n\n  \nb\n"))
        assertEquals("1. a\n2. b\n", apply("numberLines", "a\nb\n"))
    }

    // ------------------------------------------------------------------ 文本整理

    @Test
    fun `trim 合并空白 去空白与按码点反转`() {
        assertEquals("a", apply("trim", "  a \n"))
        assertEquals("a b", apply("collapseWhitespace", "a  \n  b"))
        assertEquals("abc", apply("removeWhitespace", "a b\tc"))
        assertEquals("b😀a", apply("reverse", "a😀b"))
    }

    @Test
    fun `分隔符的反斜杠写法会被还原`() {
        assertEquals("\n", StringOptions.unescape("\\n"))
        assertEquals("\t", StringOptions.unescape("\\t"))
        assertEquals("\\", StringOptions.unescape("\\\\"))
        assertEquals("\\n", StringOptions.unescape("\\\\n"))
    }

    // ------------------------------------------------------------------ 模板函数

    @Test
    fun `模板里的 str 函数与右键菜单共用同一套算法`() {
        val model = mapOf<String, Any?>(
            "str" to stringTemplateFunctions(),
            "lines" to listOf("b", "a")
        )
        val source = "\$str.camel(\"user_name\")|\$str.snake(\"userName\")|\$str.upper(\"ab\")|\$str.join(\$lines, \" - \")"
        assertEquals("userName|user_name|AB|b - a", Template.compile(source).render(model).text)
    }

    @Test
    fun `模板里 List 会按行摊平后再处理`() {
        val model = mapOf<String, Any?>("str" to stringTemplateFunctions(), "lines" to listOf("b", "a", "b"))
        assertEquals("b\na", Template.compile("\$str.distinct(\$lines)").render(model).text)
    }

    // ------------------------------------------------------------------ 小工具

    private inline fun assertOpFails(block: () -> String) {
        try {
            block()
            fail("应当抛出 StringOpException")
        } catch (ex: StringOpException) {
            assertTrue(ex.message!!.isNotBlank())
        }
    }

    @Test
    fun `空输入不会抛异常`() {
        for (op in StringOps.ALL) {
            // 每个操作都要能吃下空串，否则工具窗口刚打开、还没输入时就会弹错误
            StringOps.apply(op, "")
        }
    }
}
