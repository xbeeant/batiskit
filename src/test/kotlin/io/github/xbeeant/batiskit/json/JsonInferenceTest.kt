package io.github.xbeeant.batiskit.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 类型推断：多份样本合并、冲突提升、结构复用、动态 Map、日期与命名 */
class JsonInferenceTest {

    private fun infer(vararg json: String, settings: JsonSettings = JsonSettings()): InferenceResult {
        val samples = json.flatMap { JsonParser.parseSamples(it).values }
        return JsonInferrer(settings).infer(samples, "Response")
    }

    private fun field(result: InferenceResult, name: String): FieldModel =
        result.root!!.fields.first { it.jsonKey == name }

    @Test
    fun `数字按范围与精度选类型`() {
        val result = infer(
            """{"i":1,"l":9007199254740993,"d":1.5,"huge":123456789012345678901234567890,"b":true,"s":"x","n":null}"""
        )
        assertEquals("Integer", field(result, "i").type.java())
        assertEquals("Long", field(result, "l").type.java())
        assertEquals("BigDecimal", field(result, "d").type.java())
        assertEquals("BigInteger", field(result, "huge").type.java())
        assertEquals("Boolean", field(result, "b").type.java())
        assertEquals("String", field(result, "s").type.java())
        assertEquals("Object", field(result, "n").type.java())
        assertTrue(field(result, "n").notes.any { it.contains("null") })
    }

    @Test
    fun `多份样本取字段并集并标记可选字段`() {
        val result = infer("""{"code":0,"data":{"id":1}}""", """{"code":500,"msg":"error"}""")
        assertEquals(listOf("code", "data", "msg"), result.root!!.fields.map { it.jsonKey })
        assertEquals(2, field(result, "code").occurrences)
        assertTrue(field(result, "msg").optional)
        assertTrue(field(result, "msg").notes.any { it.contains("1/2") })
    }

    @Test
    fun `同一字段字符串与数字混用统一为 String`() {
        val result = infer("""{"value":1}""", """{"value":"1"}""")
        assertEquals("String", field(result, "value").type.java())
        assertTrue(result.warnings.any { it.contains("多种类型") })
    }

    @Test
    fun `数组里形状不同的元素合并成一个类`() {
        val result = infer("""{"items":[{"a":1},{"b":"x"}]}""")
        val list = field(result, "items").type as JsonType.ListType
        val bean = list.element as JsonType.Bean
        val item = result.classes.first { it.name == bean.className }
        assertEquals(listOf("a", "b"), item.fields.map { it.jsonKey })
        assertEquals("Item", bean.className)
    }

    @Test
    fun `结构相同的对象复用同一个类`() {
        val reused = infer("""{"x":{"id":1,"name":"a"},"y":{"id":2,"name":"b"}}""")
        assertEquals(2, reused.classes.size)
        assertEquals(field(reused, "x").type.java(), field(reused, "y").type.java())

        val separate = infer(
            """{"x":{"id":1,"name":"a"},"y":{"id":2,"name":"b"}}""",
            settings = JsonSettings().apply { reuseBeans = false }
        )
        assertEquals(3, separate.classes.size)
    }

    @Test
    fun `顶层数字 key 的对象折叠成一个 Map 字段`() {
        val result = infer("""{"1":{"v":1},"2":{"v":2}}""")
        assertEquals(1, result.root!!.fields.size)
        val map = result.root.fields.single()
        assertEquals("data", map.name)
        assertTrue(map.type is JsonType.MapType)
        assertTrue((map.type as JsonType.MapType).value is JsonType.Bean)
        assertTrue(map.notes.any { it.contains("Map") })
    }

    @Test
    fun `嵌套的数字 key 对象折叠成 Map`() {
        val result = infer("""{"stats":{"1":{"v":1},"2":{"v":2}}}""")
        val map = field(result, "stats").type as JsonType.MapType
        assertTrue(map.value is JsonType.Bean)
        assertTrue(field(result, "stats").notes.any { it.contains("Map") })
    }

    @Test
    fun `日期字符串识别成 java time 并给出格式`() {
        val result = infer("""{"createdAt":"2026-11-05 22:10:03","birthday":"2026-11-05","isoAt":"2026-11-05T22:10:03Z"}""")
        assertEquals("LocalDateTime", field(result, "createdAt").type.java())
        assertEquals("yyyy-MM-dd HH:mm:ss", field(result, "createdAt").datePattern)
        assertEquals("LocalDate", field(result, "birthday").type.java())
        assertEquals("OffsetDateTime", field(result, "isoAt").type.java())
    }

    @Test
    fun `关闭日期识别后仍是 String`() {
        val settings = JsonSettings().apply { detectDates = false }
        assertEquals("String", field(infer("""{"at":"2026-11-05"}""", settings = settings), "at").type.java())
    }

    @Test
    fun `命名处理保留字 缩写 数字开头 与 is 前缀`() {
        val result = infer("""{"class":"x","userID":1,"1st":"y","isActive":true}""")
        assertEquals(listOf("classValue", "userId", "f1st", "active"), result.root!!.fields.map { it.name })
    }

    @Test
    fun `顶层数组按元素合并`() {
        val result = infer("""[{"id":1},{"id":2,"name":"x"}]""")
        assertEquals(listOf("id", "name"), result.root!!.fields.map { it.jsonKey })
        assertEquals(2, field(result, "id").occurrences)
    }

    @Test
    fun `空数组提示需要手动修正`() {
        val result = infer("""{"list":[]}""")
        assertEquals("List<Object>", field(result, "list").type.java())
        assertTrue(field(result, "list").notes.any { it.contains("空数组") })
    }

    @Test
    fun `全大写字符串给出枚举候选提示`() {
        val result = infer("""{"status":"PENDING"}""", """{"status":"SUCCESS"}""")
        assertTrue(field(result, "status").notes.any { it.contains("疑似枚举") })
    }

    @Test
    fun `嵌套形状不同时不会误复用而丢字段`() {
        val result = infer("""{"a":{"x":1},"b":{"x":1,"y":2},"c":{"x":1}}""")
        val a = (field(result, "a").type as JsonType.Bean).className
        val b = (field(result, "b").type as JsonType.Bean).className
        val c = (field(result, "c").type as JsonType.Bean).className
        assertNotEquals(a, b)
        assertEquals(a, c)
        val inner = result.classes.first { it.name == b }
        assertEquals(listOf("x", "y"), inner.fields.map { it.jsonKey })
    }

    @Test
    fun `嵌套层级过深时退回 Object`() {
        var json = "1"
        repeat(16) { json = "{\"next\":$json}" }
        val result = infer(json)
        assertTrue(result.classes.size < 16)
        assertTrue(result.warnings.any { it.contains("层级超过") } || result.classes.any { c ->
            c.fields.any { (it.type as? JsonType.Simple)?.name == "Object" }
        })
    }
}
