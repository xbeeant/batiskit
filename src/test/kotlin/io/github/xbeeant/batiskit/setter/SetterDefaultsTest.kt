package io.github.xbeeant.batiskit.setter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 默认值推导的纯逻辑测试（不依赖 IDE 运行环境）。
 *
 * 重点守两条：表达式必须能编译（不猜无参构造、不生成 null 给基本类型），
 * 以及常用 JDK 类型给出的是「像人写的」零值而不是一律 null。
 */
class SetterDefaultsTest {

    private fun value(type: String, hint: TypeHint = TypeHint()): String =
        SetterDefaults.expression(type, hint)

    @Test
    fun `基本类型与包装类给零值`() {
        assertEquals("0", value("int"))
        assertEquals("0", value("java.lang.Integer"))
        assertEquals("0L", value("long"))
        assertEquals("0L", value("java.lang.Long"))
        assertEquals("0.0F", value("float"))
        assertEquals("0.0D", value("double"))
        assertEquals("false", value("boolean"))
        assertEquals("false", value("java.lang.Boolean"))
        assertEquals("'\\0'", value("char"))
        assertEquals("(byte) 0", value("java.lang.Byte"))
        assertEquals("(short) 0", value("java.lang.Short"))
    }

    @Test
    fun `字符串与常用值类型`() {
        assertEquals("\"\"", value("java.lang.String"))
        assertEquals("\"\"", value("CharSequence"))
        assertEquals("new java.lang.StringBuilder()", value("java.lang.StringBuilder"))
        assertEquals("java.math.BigDecimal.ZERO", value("java.math.BigDecimal"))
        assertEquals("java.math.BigInteger.ZERO", value("BigInteger"))
        assertEquals("java.util.UUID.randomUUID()", value("java.util.UUID"))
        assertEquals("java.util.Optional.empty()", value("java.util.Optional<java.lang.String>"))
        assertEquals("new java.io.File(\"\")", value("java.io.File"))
    }

    @Test
    fun `日期时间按类型给可编译的值`() {
        assertEquals("new java.util.Date()", value("java.util.Date"))
        assertEquals("new java.sql.Date(System.currentTimeMillis())", value("java.sql.Date"))
        assertEquals("new java.sql.Timestamp(System.currentTimeMillis())", value("java.sql.Timestamp"))
        assertEquals("java.time.LocalDate.now()", value("java.time.LocalDate"))
        assertEquals("java.time.LocalDateTime.now()", value("java.time.LocalDateTime"))
        assertEquals("java.time.Instant.now()", value("java.time.Instant"))
        assertEquals("java.time.Duration.ZERO", value("java.time.Duration"))
        assertEquals("java.util.Calendar.getInstance()", value("java.util.Calendar"))
    }

    @Test
    fun `集合与带泛型的类型按裸类型推导`() {
        assertEquals("new java.util.ArrayList<>()", value("java.util.List<java.lang.String>"))
        assertEquals("new java.util.ArrayList<>()", value("java.util.Collection<com.example.User>"))
        assertEquals("new java.util.HashSet<>()", value("java.util.Set<java.lang.Long>"))
        assertEquals("new java.util.HashMap<>()", value("java.util.Map<java.lang.String, java.lang.Integer>"))
        assertEquals("new java.util.ArrayDeque<>()", value("java.util.Queue<java.lang.String>"))
        assertEquals("new java.util.Properties()", value("java.util.Properties"))
    }

    @Test
    fun `数组给空数组`() {
        assertEquals("new int[0]", value("int[]"))
        assertEquals("new java.lang.String[0]", value("java.lang.String[]"))
        assertEquals("new int[0][0]", value("int[][]"))
    }

    @Test
    fun `枚举取第一个常量`() {
        val hint = TypeHint("com.example.Status", listOf("ENABLED", "DISABLED"))
        assertEquals("com.example.Status.ENABLED", value("com.example.Status", hint))
        assertEquals("Status.ENABLED", value("Status", TypeHint(enumConstants = listOf("ENABLED"))))
    }

    @Test
    fun `未知对象只有确认能 new 才构造`() {
        assertEquals("new com.example.Address()", value("com.example.Address", TypeHint("com.example.Address", instantiable = true)))
        assertEquals("null", value("com.example.Address"))
        assertEquals("null", value("T"))                         // 泛型变量解析不到类
        assertEquals("null", value("java.io.Serializable"))       // 集合外的接口不猜
        assertEquals("null", value("void"))
        assertEquals("null", value(""))
    }

    @Test
    fun `短名写法与 import 清单配套`() {
        val bigDecimal = SetterDefaults.defaultFor("java.math.BigDecimal")
        assertEquals("java.math.BigDecimal.ZERO", bigDecimal.qualifiedText)
        assertEquals("BigDecimal.ZERO", bigDecimal.text)
        assertEquals(listOf("java.math.BigDecimal"), bigDecimal.imports)

        val list = SetterDefaults.defaultFor("java.util.List<java.lang.String>")
        assertEquals("new ArrayList<>()", list.text)
        assertEquals(listOf("java.util.ArrayList"), list.imports)

        val date = SetterDefaults.defaultFor("java.sql.Date")
        assertEquals("new Date(System.currentTimeMillis())", date.text)
        assertEquals(listOf("java.sql.Date"), date.imports)

        // java.lang 不需要 import，但仍应缩短
        val builder = SetterDefaults.defaultFor("java.lang.StringBuilder")
        assertEquals("new StringBuilder()", builder.text)
        assertTrue("java.lang 不该进 import：$builder", builder.imports.isEmpty())

        // 没有包名的类型 / 纯零值不需要任何 import
        assertTrue(SetterDefaults.defaultFor("int").imports.isEmpty())
        assertTrue(SetterDefaults.defaultFor("com.example.Address").imports.isEmpty())
    }
}
