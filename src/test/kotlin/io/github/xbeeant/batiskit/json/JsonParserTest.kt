package io.github.xbeeant.batiskit.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 宽松 JSON 解析器：标准 / JSON5 / 日志包裹 / 多份样本 / 错误定位 */
class JsonParserTest {

    @Test
    fun `标准 JSON 保留字段顺序与数字字面量`() {
        val value = JsonParser.parse("""{"z":1,"a":9007199254740993,"b":1.25,"c":true,"d":null}""") as JsonValue.Obj
        assertEquals(listOf("z", "a", "b", "c", "d"), value.fields.keys.toList())
        assertEquals("9007199254740993", (value.fields["a"] as JsonValue.Num).raw)
        assertEquals("1.25", (value.fields["b"] as JsonValue.Num).raw)
        assertTrue(value.fields["d"] is JsonValue.Null)
    }

    @Test
    fun `宽松语法 注释 单引号 尾逗号 无引号 key`() {
        val text = """
            // 接口返回
            {
              code: 0, /* 状态 */
              'msg': 'ok',
              data: [1, 2, 3,],
            }
        """.trimIndent()
        val obj = JsonParser.parse(text) as JsonValue.Obj
        assertEquals("0", (obj.fields["code"] as JsonValue.Num).raw)
        assertEquals("ok", (obj.fields["msg"] as JsonValue.Str).value)
        assertEquals(3, (obj.fields["data"] as JsonValue.Arr).items.size)
    }

    @Test
    fun `从日志行里截取 JSON`() {
        val text = """2026-11-05 22:10:03 INFO  c.e.demo.FeignLogger : response = {"code":0,"data":{"id":7}}"""
        val samples = JsonParser.parseSamples(text)
        assertTrue(samples.errors.toString(), samples.errors.isEmpty())
        assertEquals(1, samples.values.size)
        val obj = samples.values.first() as JsonValue.Obj
        assertEquals(7L, ((obj.fields["data"] as JsonValue.Obj).fields["id"] as JsonValue.Num).raw.toLong())
    }

    @Test
    fun `一次贴多份样本`() {
        val samples = JsonParser.parseSamples(
            """
            {"code":0,"data":{"id":1,"name":"a"}}
            {"code":500,"msg":"error"}
            """.trimIndent()
        )
        assertTrue(samples.errors.toString(), samples.errors.isEmpty())
        assertEquals(2, samples.values.size)
        assertEquals("error", ((samples.values[1] as JsonValue.Obj).fields["msg"] as JsonValue.Str).value)
    }

    @Test
    fun `被转义成字符串的 JSON 自动解包`() {
        // Kotlin 源码里 \\u 之类会被预处理，这里显式拼出一段「字符串包着的 JSON」
        val escaped = "\"{\\\"code\\\":0,\\\"data\\\":[1,2]}\""
        val value = JsonParser.parse(escaped) as JsonValue.Obj
        assertEquals("0", (value.fields["code"] as JsonValue.Num).raw)
        assertEquals(2, (value.fields["data"] as JsonValue.Arr).items.size)
    }

    @Test
    fun `unicode 与转义字符`() {
        val unicode = "\\u5f20\\u4e09"
        val value = JsonParser.parse("""{"name":"$unicode","path":"a\/b","line":"x\ny"}""") as JsonValue.Obj
        assertEquals("张三", (value.fields["name"] as JsonValue.Str).value)
        assertEquals("a/b", (value.fields["path"] as JsonValue.Str).value)
        assertEquals("x\ny", (value.fields["line"] as JsonValue.Str).value)
    }

    @Test
    fun `报错带行列号`() {
        val error = runCatching { JsonParser.parse("{\n  \"a\": }") }.exceptionOrNull() as? JsonParseException
        assertTrue(error != null)
        assertEquals(2, error!!.line)
        assertTrue(error.message!!.contains("第 2 行"))
    }

    @Test
    fun `空输入有明确提示`() {
        val samples = JsonParser.parseSamples("   ")
        assertTrue(samples.errors.first().contains("请先粘贴"))
    }
}
