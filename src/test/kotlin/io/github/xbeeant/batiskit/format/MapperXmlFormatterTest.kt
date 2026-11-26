package io.github.xbeeant.batiskit.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mapper XML 格式化引擎的纯逻辑测试。
 */
class MapperXmlFormatterTest {

    private val messy = """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.e.UserMapper">

    <resultMap id="userMap" type="com.e.User">
      <id property="id" column="id"/>
            <result property="name" column="name"/>
  </resultMap>

   <select id="selectUsers" resultMap="userMap">
   SELECT u.id, u.name
FROM user u
   <where>
   <if test="name != null">
AND u.name = #{name}
   </if>
   </where>
   ORDER BY u.id
   </select>

   <update id="rename">
      <set>
         <if test="name != null">name = #{name},</if>
      </set>
      WHERE id = #{id}
   </update>
</mapper>
"""

    private val expected = """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
<mapper namespace="com.e.UserMapper">

    <resultMap id="userMap" type="com.e.User">
        <id property="id" column="id"/>
        <result property="name" column="name"/>
    </resultMap>

    <select id="selectUsers" resultMap="userMap">
        SELECT u.id, u.name
        FROM user u
        <where>
            <if test="name != null">
                AND u.name = #{name}
            </if>
        </where>
        ORDER BY u.id
    </select>

    <update id="rename">
        <set>
            <if test="name != null">name = #{name},</if>
        </set>
        WHERE id = #{id}
    </update>
</mapper>
"""

    @Test
    fun `识别 Mapper XML`() {
        assertTrue(MapperXmlFormatter.isMapperXml(messy))
        assertTrue(MapperXmlFormatter.isMapperXml("<mapper namespace=\"a.b\"/>"))
        assertFalse(MapperXmlFormatter.isMapperXml("<root><child/></root>"))
        assertFalse(MapperXmlFormatter.isMapperXml(""))
    }

    @Test
    fun `格式化整篇 缩进归位 SQL 正文按行保留`() {
        val out = MapperXmlFormatter.format(messy.trim())
        assertEquals(expected.trim(), out.trim())
        // SQL 内容行不被重排：逐字符等于原文 trim 后的行
        assertTrue(out.contains("SELECT u.id, u.name"))
        assertTrue(out.contains("AND u.name = #{name}"))
    }

    @Test
    fun `格式化幂等`() {
        val once = MapperXmlFormatter.format(messy.trim())
        val twice = MapperXmlFormatter.format(once)
        assertEquals(once, twice)
    }

    @Test
    fun `短的叶子元素保持内联`() {
        val text = """
            <mapper namespace="a.b">
                <update id="rename">
                    <set>
                        <if test="name != null">name = #{name},</if>
                    </set>
                </update>
            </mapper>
        """.trimIndent()
        val out = MapperXmlFormatter.format(text)
        assertTrue(out.contains("<if test=\"name != null\">name = #{name},</if>"))
    }

    @Test
    fun `长的内文换行成块`() {
        val longCondition = "u.name = #{name} and u.age = #{age} and u.city = #{city} and u.dept = #{dept}" +
            " and u.email = #{email} and u.mobile = #{mobile}"
        val text = """
            <mapper namespace="a.b">
                <select id="x">
                    <if test="name != null">$longCondition</if>
                </select>
            </mapper>
        """.trimIndent()
        val out = MapperXmlFormatter.format(text)
        assertFalse(out.contains("<if test=\"name != null\">$longCondition</if>"))
        // 内文行整体保留，只是重新缩进
        assertTrue(out.contains(longCondition))
    }

    @Test
    fun `属性值里的尖括号不会截断标签`() {
        val text = """
            <mapper namespace="a.b">
                <select id="x">
                    <if test="age &gt; 18">WHERE age = #{age}</if>
                </select>
            </mapper>
        """.trimIndent()
        val out = MapperXmlFormatter.format(text)
        assertTrue(out.contains("<if test=\"age &gt; 18\">WHERE age = #{age}</if>"))
    }

    @Test
    fun `属性内多余空白压成单行`() {
        val text = """
            <mapper    namespace = "a.b"   >
                <select    id="x"  resultMap="r"   >
                    SELECT 1
                </select>
            </mapper>
        """.trimIndent()
        val out = MapperXmlFormatter.format(text)
        assertTrue(out.contains("<mapper namespace=\"a.b\">"))
        assertTrue(out.contains("<select id=\"x\" resultMap=\"r\">"))
        assertTrue(out.contains("SELECT 1"))
    }

    @Test
    fun `连续空行收敛为一行`() {
        val text = """
            <mapper namespace="a.b">
                <select id="x">
                    SELECT 1
                </select>



                <select id="y">
                    SELECT 2
                </select>
            </mapper>
        """.trimIndent()
        val out = MapperXmlFormatter.format(text)
        assertEquals(1, Regex("\\n\\n").findAll(out).count())
    }

    @Test
    fun `CDATA 块独占行并保持内容`() {
        val text = """
            <mapper namespace="a.b">
                <select id="x">
                    SELECT * FROM t WHERE a
                    <![CDATA[ < 1 AND b > 2 ]]>
                </select>
            </mapper>
        """.trimIndent()
        val out = MapperXmlFormatter.format(text)
        assertTrue(out.contains("<![CDATA[< 1 AND b > 2]]>"))
    }

    @Test
    fun `注释独占行`() {
        val text = """
            <mapper namespace="a.b">
                <!-- 用户查询 -->
                <select id="x">
                    SELECT 1
                </select>
            </mapper>
        """.trimIndent()
        val out = MapperXmlFormatter.format(text)
        assertTrue(out.contains("    <!-- 用户查询 -->"))
    }

    @Test
    fun `SQL 里含换行的字符串字面量不被改写`() {
        // 正文行只动首尾空白，不增删行内的换行
        val text = """
            <mapper namespace="a.b">
                <select id="x">
                    INSERT INTO t VALUES ('line1
                    line2')
                </select>
            </mapper>
        """.trimIndent()
        val out = MapperXmlFormatter.format(text)
        assertTrue(out.contains("'line1"))
        assertTrue(out.contains("line2')"))
        // 输出以换行收尾，行数按去尾换行后比较
        assertEquals(text.lines().size, out.trimEnd('\n').lines().size)
    }

    @Test
    fun `Enter 续行缩进按所在层级计算`() {
        val text = """
            <mapper namespace="a.b">
                <select id="x">
                    SELECT 1
                </select>
            </mapper>
        """.trimIndent()
        assertEquals("    ", MapperXmlFormatter.indentAt(text, text.indexOf("<select")))
        assertEquals("        ", MapperXmlFormatter.indentAt(text, text.indexOf("SELECT 1")))
        assertEquals("", MapperXmlFormatter.indentAt("not a mapper", 0))
    }

    @Test
    fun `选区片段格式化不要求 mapper 根标签`() {
        val fragment = """
            <select id="x">
            SELECT 1
               <if test="a != null">
             AND a = #{a}
               </if>
            </select>
        """.trimIndent()
        // 旧行为：整段不含 <mapper>，format() 直接原样返回 —— 这就是选区格式化「按了没反应」的原因
        assertEquals(fragment, MapperXmlFormatter.format(fragment))
        val out = MapperXmlFormatter.formatFragment(fragment)
        assertNotNull(out)
        assertTrue(out!!.contains("<select id=\"x\">"))
        assertTrue(out.contains("SELECT 1"))
        assertTrue(out.contains("<if test=\"a != null\">"))
        assertTrue(out.contains("</select>"))
        assertFalse("选区替换不能带尾随换行，否则每格式化一次多一个空行", out.endsWith("\n"))
    }

    @Test
    fun `选区片段按基础缩进整体平移`() {
        val fragment = "<select id=\"x\">\nSELECT 1\n</select>"
        val out = MapperXmlFormatter.formatFragment(fragment, 8)!!
        assertTrue(out.startsWith("        <select"))
        assertTrue(out.contains("            SELECT 1"))
        assertTrue(out.lines().all { it.isBlank() || it.startsWith("        ") })
    }

    @Test
    fun `标签不自洽的选区返回 null 让调用方给提示`() {
        assertNull(MapperXmlFormatter.formatFragment("<select id=\"x\"><if test=\"a\">"))
        assertNull(MapperXmlFormatter.formatFragment("AND a = #{a}</if>"))
        assertNull(MapperXmlFormatter.formatFragment("   \n  "))
    }

    @Test
    fun `选区片段格式化幂等`() {
        val fragment = "<if test=\"a != null\">\nAND a = #{a}\n</if>"
        val once = MapperXmlFormatter.formatFragment(fragment)!!
        assertEquals(once, MapperXmlFormatter.formatFragment(once))
    }

    /** 用户报告的用例：`<if test="…size() > 0">` 里带裸 `>`，SQL 正文与动态标签全部顶格 */
    private val carriedSeriesSql = """
        <sql id="searchCarriedSeriesMidSql">
        SELECT DISTINCT
        mid
        FROM
        series_module_link
        WHERE
        deleted = 0
        <if test="example.seriesIds != null and example.seriesIds.size() > 0">
        and series_id in
        <foreach collection="example.seriesIds" item="seriesId" separator="," open="(" close=")">
        #{seriesId}
        </foreach>
        </if>
        <if test="example.rootId != null">
        AND root_id = #{example.rootId, jdbcType=BIGINT}
        </if>
        <if test="example.type != null and example.type != ''">
        AND type = #{example.type, jdbcType=VARCHAR}
        </if>
        </sql>
    """.trimIndent()

    private val carriedSeriesExpected = listOf(
        "    <sql id=\"searchCarriedSeriesMidSql\">",
        "        SELECT DISTINCT",
        "        mid",
        "        FROM",
        "        series_module_link",
        "        WHERE",
        "        deleted = 0",
        "        <if test=\"example.seriesIds != null and example.seriesIds.size() > 0\">",
        "            and series_id in",
        "            <foreach collection=\"example.seriesIds\" item=\"seriesId\" separator=\",\" open=\"(\" close=\")\">",
        "                #{seriesId}",
        "            </foreach>",
        "        </if>",
        "        <if test=\"example.rootId != null\">",
        "            AND root_id = #{example.rootId, jdbcType=BIGINT}",
        "        </if>",
        "        <if test=\"example.type != null and example.type != ''\">",
        "            AND type = #{example.type, jdbcType=VARCHAR}",
        "        </if>",
        "    </sql>",
    )

    private fun carriedSeriesDoc(sql: String): String =
        "<mapper namespace=\"com.e.SeriesMapper\">\n$sql\n</mapper>"

    private fun mapperBody(out: String): List<String> =
        out.trimEnd('\n').lines().drop(1).dropLast(1)

    @Test
    fun `属性值带裸大于号的动态 SQL 逐层缩进且幂等`() {
        val out = MapperXmlFormatter.format(carriedSeriesDoc(carriedSeriesSql))
        assertEquals(carriedSeriesExpected, mapperBody(out))
        assertEquals(out, MapperXmlFormatter.format(out))
    }

    @Test
    fun `属性值带裸大于号时 Enter 续行缩进仍按层级计算`() {
        val doc = MapperXmlFormatter.format(carriedSeriesDoc(carriedSeriesSql))
        val expected = mapOf(
            "<sql id" to 4,
            "SELECT DISTINCT" to 8,
            "<if test=\"example.seriesIds" to 8,
            "and series_id in" to 12,
            "<foreach collection" to 12,
            "#{seriesId}" to 16,
            "<if test=\"example.rootId" to 8,
            "AND root_id" to 12,
            "AND type" to 12,
        )
        expected.forEach { (marker, width) ->
            assertEquals(
                "缩进算错：$marker（多半是 test 属性里的裸 > 把标签截断了）",
                " ".repeat(width),
                MapperXmlFormatter.indentAt(doc, doc.indexOf(marker)),
            )
        }
    }

    @Test
    fun `属性值带裸大于号的选区片段按基础缩进整体平移`() {
        val out = MapperXmlFormatter.formatFragment(carriedSeriesSql, 4)!!
        assertEquals(carriedSeriesExpected, out.lines())
        assertEquals(out, MapperXmlFormatter.formatFragment(out, 4))
    }

    @Test
    fun `SQL 正文里的裸小于号不再让整篇格式化静默失效`() {
        val doc = "<mapper namespace=\"a.b\">\n" +
            "  <select id=\"x\">\n" +
            "  SELECT * FROM t WHERE a < 10\n" +
            "  </select>\n" +
            "</mapper>"
        val out = MapperXmlFormatter.format(doc)
        assertFalse("整篇被原样丢回，看起来就是没有缩进", out == doc)
        assertEquals(
            listOf(
                "    <select id=\"x\">",
                "        SELECT * FROM t WHERE a < 10",
                "    </select>",
            ),
            mapperBody(out),
        )
        assertEquals(out, MapperXmlFormatter.format(out))
    }

    @Test
    fun `属性值里的裸小于号不影响标签解析`() {
        val doc = "<mapper namespace=\"a.b\">\n" +
            "  <select id=\"x\">\n" +
            "  <if test=\"age < 18\">AND age = #{age}</if>\n" +
            "  </select>\n" +
            "</mapper>"
        val out = MapperXmlFormatter.format(doc)
        assertTrue(out.contains("<if test=\"age < 18\">AND age = #{age}</if>"))
        assertTrue(out.contains("    <select id=\"x\">"))
    }

    @Test
    fun `属性值内的空白保持原样`() {
        val doc = "<mapper namespace=\"a.b\">\n" +
            "  <select id=\"x\">\n" +
            "  <if test=\"age  !=  null\">AND age = #{age}</if>\n" +
            "  </select>\n" +
            "</mapper>"
        val out = MapperXmlFormatter.format(doc)
        assertTrue(out.contains("<if test=\"age  !=  null\">AND age = #{age}</if>"))
    }

    @Test
    fun `mapper 根标签属性里的裸大于号不影响识别`() {
        val doc = "<mapper foo=\"a > b\" namespace=\"a.b\">\n<select id=\"x\">SELECT 1</select>\n</mapper>"
        assertTrue(MapperXmlFormatter.isMapperXml(doc))
        assertEquals(
            listOf("    <select id=\"x\">SELECT 1</select>"),
            mapperBody(MapperXmlFormatter.format(doc)),
        )
    }

    @Test
    fun `标签不自洽时 formatOrNull 返回 null 而不是静默原样返回`() {
        val doc = "<mapper namespace=\"a.b\">\n<select id=\"x\">\nSELECT 1\n</mapper>"
        assertNull(MapperXmlFormatter.formatOrNull(doc))
        assertEquals(doc, MapperXmlFormatter.format(doc))
    }

    /** 用户反馈：SQL 正文已人工排好版；格式化必须让 SQL 挂在正确的 XML 层级下 */
    private val forkedProjectSql = """
        <mapper namespace="io.github.xbeeant.manifest.mapper.ModuleForkMapper">
        <select id="listForkedProjectBySids" resultType="io.github.xbeeant.manifest.vo.ForkedProjectVO">
            SELECT DISTINCT
                mfr.sid,
                t.root_id AS rootId
            FROM
                `manifest_module_fork_relation` AS mfr
                    INNER JOIN manifest_module AS t ON mfr.tid = t.mid
            WHERE
                mfr.`sid` IN
                <foreach collection="sids" item="sid" open="(" close=")" separator=",">
                    #{sid}
                </foreach>
                <if test="businessSpace != null and businessSpace != ''">
                    and mfr.`business_space` = #{businessSpace,jdbcType=VARCHAR}
                </if>
                AND t.deleted = 0
        </select>
        </mapper>
    """.trimIndent()

    /**
     * 联合缩进后的正确结果：`<select>` 在 4 空格，SQL 基线在 8 空格（比标签深一级），
     * 字段 / JOIN 再依次深一级；动态标签按 XML 层级落在 8 空格。
     * 旧实现把 SQL 的**绝对**前导空白原样搬过来，导致 SQL 与 `<select>` 都挤在 4 空格、
     * 而 `<foreach>` 却在 8 空格 —— 两套缩进互相错位。
     */
    private val forkedProjectExpected = listOf(
        "<mapper namespace=\"io.github.xbeeant.manifest.mapper.ModuleForkMapper\">",
        "    <select id=\"listForkedProjectBySids\" resultType=\"io.github.xbeeant.manifest.vo.ForkedProjectVO\">",
        "        SELECT DISTINCT",
        "            mfr.sid,",
        "            t.root_id AS rootId",
        "        FROM",
        "            `manifest_module_fork_relation` AS mfr",
        "                INNER JOIN manifest_module AS t ON mfr.tid = t.mid",
        "        WHERE",
        "            mfr.`sid` IN",
        "        <foreach collection=\"sids\" item=\"sid\" open=\"(\" close=\")\" separator=\",\">",
        "            #{sid}",
        "        </foreach>",
        "        <if test=\"businessSpace != null and businessSpace != ''\">",
        "            and mfr.`business_space` = #{businessSpace,jdbcType=VARCHAR}",
        "        </if>",
        "        AND t.deleted = 0",
        "    </select>",
        "</mapper>",
    )

    @Test
    fun `已排好版的 SQL 正文挂在正确的 XML 层级下且幂等`() {
        val out = MapperXmlFormatter.format(forkedProjectSql)
        assertEquals(forkedProjectExpected, out.trimEnd('\n').lines())
        assertEquals(out, MapperXmlFormatter.format(out))
    }

    @Test
    fun `SQL 关键字 字段 JOIN 的三级相对层次不被压平`() {
        val body = MapperXmlFormatter.format(forkedProjectSql).trimEnd('\n').lines()
        assertTrue(body.contains("        SELECT DISTINCT"))
        assertTrue(body.contains("            mfr.sid,"))
        assertTrue(body.contains("                INNER JOIN manifest_module AS t ON mfr.tid = t.mid"))
        assertFalse("字段行被压平到与 SELECT 同一级", body.contains("        mfr.sid,"))
        assertFalse("JOIN 行被压平到与 SELECT 同一级", body.contains("        INNER JOIN"))
    }

    @Test
    fun `SQL 正文不再与 select 标签挤在同一级`() {
        val body = MapperXmlFormatter.format(forkedProjectSql).trimEnd('\n').lines()
        val select = body.indexOfFirst { it.trimStart().startsWith("<select ") }
        val sql = body.indexOfFirst { it.trim() == "SELECT DISTINCT" }
        assertTrue(select >= 0 && sql > select)
        val indentOf: (String) -> Int = { it.takeWhile { c -> c == ' ' }.length }
        assertEquals("SQL 应比 select 深一级", 4, indentOf(body[sql]) - indentOf(body[select]))
        // 动态标签与 SQL 基线同属 select 的内容，缩进一致
        val foreach = body.indexOfFirst { it.trimStart().startsWith("<foreach ") }
        assertEquals(indentOf(body[sql]), indentOf(body[foreach]))
    }

    @Test
    fun `换缩进宽度后 SQL 相对层次仍按单位换算`() {
        val out = MapperXmlFormatter.format(forkedProjectSql, MapperXmlFormatter.Options(indentUnit = 2))
        val body = out.trimEnd('\n').lines()
        assertTrue(body.contains("    SELECT DISTINCT"))            // select 在 2，SQL 在 4
        assertTrue(body.contains("        mfr.sid,"))               // 字段再深 2
        assertTrue(body.contains("            INNER JOIN manifest_module AS t ON mfr.tid = t.mid"))
        assertEquals(out, MapperXmlFormatter.format(out, MapperXmlFormatter.Options(indentUnit = 2)))
    }

    @Test
    fun `XML 层级加深时 SQL 整体跟随平移而相对层次不变`() {
        val deep = "<mapper namespace=\"a.b\">\n" +
            "<select id=\"x\">\n" +
            "<where>\n" +
            "    SELECT a,\n" +
            "        b\n" +
            "    FROM t\n" +
            "</where>\n" +
            "</select>\n" +
            "</mapper>"
        val body = MapperXmlFormatter.format(deep).trimEnd('\n').lines()
        val select = body.indexOfFirst { it.trimStart().startsWith("<select ") }
        val where = body.indexOfFirst { it.trim() == "<where>" }
        val selectLine = body.indexOfFirst { it.trim() == "SELECT a," }
        val fieldLine = body.indexOfFirst { it.trim() == "b" }
        val indentOf: (String) -> Int = { it.takeWhile { c -> c == ' ' }.length }
        assertTrue(select >= 0 && where > select && selectLine > where && fieldLine > selectLine)
        assertEquals(4, indentOf(body[where]) - indentOf(body[select]))
        assertEquals(4, indentOf(body[selectLine]) - indentOf(body[where]))
        assertEquals("SQL 内部相对层次保持一级", 4, indentOf(body[fieldLine]) - indentOf(body[selectLine]))
        assertEquals(body, MapperXmlFormatter.format(MapperXmlFormatter.format(deep)).trimEnd('\n').lines())
    }

    @Test
    fun `SQL 从非整单位列起排时内部相对层次仍保留`() {
        // 原文整体从 2 空格起排（2 / 6 / 10），相对基线差 0 / 4 / 8 是完整层次，
        // 不能因为绝对列数不是 4 的倍数就把字段 / WHERE 压平到与 SELECT 同一级
        val doc = "<mapper namespace=\"a.b\">\n" +
            "<select id=\"x\">\n" +
            "  SELECT 1\n" +
            "      FROM t\n" +
            "          WHERE id = #{id}\n" +
            "</select>\n" +
            "</mapper>"
        val out = MapperXmlFormatter.format(doc)
        assertEquals(
            listOf(
                "    <select id=\"x\">",
                "        SELECT 1",
                "            FROM t",
                "                WHERE id = #{id}",
                "    </select>",
            ),
            mapperBody(out),
        )
        assertEquals(out, MapperXmlFormatter.format(out))
    }

    @Test
    fun `零散的 3 空格乱缩进仍按 XML 层级规整`() {
        val doc = "<mapper namespace=\"a.b\">\n" +
            "<select id=\"x\">\n" +
            "   SELECT 1\n" +
            "  FROM t\n" +
            "</select>\n" +
            "</mapper>"
        assertEquals(
            listOf("    <select id=\"x\">", "        SELECT 1", "        FROM t", "    </select>"),
            mapperBody(MapperXmlFormatter.format(doc)),
        )
    }

    @Test
    fun `tab 排版的 SQL 按缩进宽度换算后挂在 XML 层级下`() {
        val doc = "<mapper namespace=\"a.b\">\n" +
            "<select id=\"x\">\n" +
            "\tSELECT 1\n" +
            "\t\tFROM t\n" +
            "</select>\n" +
            "</mapper>"
        val out = MapperXmlFormatter.format(doc)
        // tab 按一级（4 空格）换算成相对层次，不再把绝对 tab 原样搬过来导致 SQL 与标签错位
        assertEquals(
            listOf("    <select id=\"x\">", "        SELECT 1", "            FROM t", "    </select>"),
            mapperBody(out),
        )
        assertEquals(out, MapperXmlFormatter.format(out))
    }

    @Test
    fun `选区片段里排好版的 SQL 与 XML 一起按基础缩进平移`() {
        val fragment = "<select id=\"x\">\n" +
            "    SELECT 1\n" +
            "        FROM t\n" +
            "</select>"
        val out = MapperXmlFormatter.formatFragment(fragment, 4)!!
        assertEquals(
            listOf("    <select id=\"x\">", "        SELECT 1", "            FROM t", "    </select>"),
            out.lines(),
        )
        assertEquals(out, MapperXmlFormatter.formatFragment(out, 4))
    }

    @Test
    fun `SQL 与 foreach 写在同一行时不被拆成两行`() {
        val doc = """
            <mapper namespace="a.b">
            <select id="x">
            SELECT * FROM t WHERE id IN <foreach collection="ids" item="id" open="(" close=")" separator=",">#{id}</foreach>
            </select>
            </mapper>
        """.trimIndent()
        val out = MapperXmlFormatter.format(doc)
        assertTrue(
            "SQL 与内联标签应留在同一行：$out",
            out.lines().any {
                it.trimStart().startsWith("SELECT * FROM t WHERE id IN <foreach") &&
                    it.contains("#{id}</foreach>")
            }
        )
        assertEquals(out, MapperXmlFormatter.format(out))
    }

    @Test
    fun `两个内联标签之间有换行时各自成行`() {
        val doc = """
            <mapper namespace="a.b">
            <select id="x">
            SELECT 1
            <if test="a != null">AND a = #{a}</if>
            <if test="b != null">AND b = #{b}</if>
            </select>
            </mapper>
        """.trimIndent()
        val out = MapperXmlFormatter.format(doc)
        val ifLines = out.lines().filter { it.trimStart().startsWith("<if ") }
        assertEquals(2, ifLines.size)
        assertTrue(ifLines.all { it.startsWith("        <if ") })
    }

    @Test
    fun `多行 CDATA 里的 SQL 相对缩进保留且不被比较运算符截断`() {
        val doc = "<mapper namespace=\"a.b\">\n" +
            "<select id=\"x\">\n" +
            "  <![CDATA[\n" +
            "      SELECT * FROM t\n" +
            "          WHERE a < 10 AND b > 2\n" +
            "  ]]>\n" +
            "</select>\n" +
            "</mapper>"
        val out = MapperXmlFormatter.format(doc)
        assertTrue(out.contains("            SELECT * FROM t"))
        assertTrue(out.contains("                WHERE a < 10 AND b > 2"))
        assertTrue(out.contains("        <![CDATA["))
        assertTrue(out.contains("        ]]>"))
        assertEquals(out, MapperXmlFormatter.format(out))
    }
}
