package io.github.xbeeant.batiskit.refactor

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import io.github.xbeeant.batiskit.navigation.MapperGotoDeclarationHandler
import io.github.xbeeant.batiskit.navigation.MapperLineMarkerProvider
import io.github.xbeeant.batiskit.navigation.MapperXmlResolver

/** `<sql id>` ↔ `<include refid>` 的引用、补全与重命名联动 */
class MapperSqlIncludeTest : BasePlatformTestCase() {

    private val userXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <mapper namespace="com.example.mapper.UserMapper">
            <sql id="Base_Column_List">id, name</sql>
            <sql id="Query_Condition">
                <if test="name != null">AND name = #{name}</if>
            </sql>
            <select id="selectById" resultType="map">
                SELECT <include refid="Base_Column_List"/> FROM t_user
                WHERE 1=1 <include refid="Query_Condition"/>
            </select>
        </mapper>
    """.trimIndent()

    private val orderXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <mapper namespace="com.example.mapper.OrderMapper">
            <sql id="Order_Columns">id, order_no</sql>
            <select id="list" resultType="map">
                SELECT <include refid="com.example.mapper.UserMapper.Base_Column_List"/> FROM t_user
            </select>
        </mapper>
    """.trimIndent()

    private fun addMappers(): Pair<XmlFile, XmlFile> {
        val user = myFixture.addFileToProject("mybatis/UserMapper.xml", userXml) as XmlFile
        val order = myFixture.addFileToProject("mybatis/OrderMapper.xml", orderXml) as XmlFile
        return user to order
    }

    private fun referencesAt(file: XmlFile, marker: String): List<PsiReference> {
        val index = file.text.indexOf(marker)
        assertTrue("XML 里找不到 $marker", index >= 0)
        val offset = index + marker.length / 2
        val reference = file.findReferenceAt(offset) ?: return emptyList()
        return if (reference is com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference) {
            reference.references.toList()
        } else {
            listOf(reference)
        }
    }

    private fun valueOf(file: XmlFile, marker: String): XmlAttributeValue {
        val offset = file.text.indexOf(marker)
        assertTrue("XML 里找不到 $marker", offset >= 0)
        val leaf = file.findElementAt(offset + marker.length / 2)!!
        return PsiTreeUtil.getParentOfType(leaf, XmlAttributeValue::class.java)!!
    }

    // ------------------------------------------------------------------ 引用解析

    fun `test 短名 refid 解析到本文件的 sql 定义`() {
        val (user, _) = addMappers()
        val resolved = referencesAt(user, "refid=\"Base_Column_List\"").mapNotNull { it.resolve() as? XmlTag }
        assertEquals(1, resolved.size)
        assertEquals("sql", resolved[0].name)
        assertEquals("Base_Column_List", resolved[0].getAttributeValue("id"))
    }

    fun `test 全限定 refid 跨文件解析到 sql 定义`() {
        val (user, order) = addMappers()
        val expected = user.rootTag!!.findSubTags("sql").first()
        val resolved = referencesAt(order, "UserMapper.Base_Column_List").mapNotNull { it.resolve() }
        assertTrue("跨文件全限定 refid 应解析到 UserMapper 的 sql 定义", resolved.contains(expected))
    }

    fun `test 写错的 refid 不解析 非 mapper XML 不生成引用`() {
        addMappers()
        val broken = myFixture.addFileToProject(
            "mybatis/BrokenMapper.xml",
            """<mapper namespace="com.example.mapper.BrokenMapper"><select id="x"><include refid="No_Such_Fragment"/></select></mapper>"""
        ) as XmlFile
        assertTrue(
            "找不到的片段不应解析出定义",
            referencesAt(broken, "refid=\"No_Such_Fragment\"").mapNotNull { it.resolve() }.isEmpty()
        )

        val plain = myFixture.configureByText(
            "plain.xml",
            """<configuration><include refid="Base_Column_List"/></configuration>"""
        ) as XmlFile
        assertTrue(referencesAt(plain, "refid=\"Base_Column_List\"").isEmpty())
    }

    fun `test 同 namespace 兄弟 XML 的短名 refid 能解析`() {
        val base = myFixture.addFileToProject(
            "mybatis/UserMapper.xml",
            """<mapper namespace="com.example.mapper.UserMapper"><sql id="Shared_Columns">id, name</sql></mapper>"""
        ) as XmlFile
        val ext = myFixture.addFileToProject(
            "mybatis/UserMapperExt.xml",
            """<mapper namespace="com.example.mapper.UserMapper"><select id="list">SELECT <include refid="Shared_Columns"/> FROM t_user</select></mapper>"""
        ) as XmlFile
        val include = ext.rootTag!!.findSubTags("select").first().findSubTags("include").first()

        val definitions = MapperXmlResolver.sqlDefinitions(include, "Shared_Columns")
        assertEquals(1, definitions.size)
        assertEquals(base, definitions[0].containingFile)
        assertTrue(MapperXmlResolver.sqlIdVariants(ext.rootTag!!).contains("Shared_Columns"))
        assertTrue(
            "定义侧 Find Usages 要收进兄弟文件里的 include",
            MapperXmlResolver.sqlUsages(definitions[0]).any { it.containingFile == ext }
        )
    }

    // ------------------------------------------------------------------ Find Usages（引用处收集）

    fun `test sqlUsages 收集短名与跨文件全限定引用`() {
        val (user, _) = addMappers()
        val definition = user.rootTag!!.findSubTags("sql").first()
        val usages = MapperXmlResolver.sqlUsages(definition)
        assertEquals("本文件 1 处短名 + 跨文件 1 处全限定", 2, usages.size)
        assertTrue(usages.all { it.name == "include" })
    }

    // ------------------------------------------------------------------ 补全

    fun `test refid 补全列出本文件片段与跨 namespace 全限定名`() {
        val (user, _) = addMappers()
        val variants = MapperXmlResolver.sqlIdVariants(user.rootTag!!.subTags.first())
        assertTrue(variants.contains("Base_Column_List"))
        assertTrue(variants.contains("Query_Condition"))
        assertTrue(variants.contains("com.example.mapper.OrderMapper.Order_Columns"))
        // 本文件自己的片段不该再出一份全限定副本
        assertFalse(variants.contains("com.example.mapper.UserMapper.Base_Column_List"))
    }

    fun `test 输入 refid 时弹出片段补全`() {
        addMappers()
        val xml = """
            <mapper namespace="com.example.mapper.UserMapper">
                <sql id="Base_Column_List">id, name</sql>
                <select id="list">SELECT <include refid="<caret>"/> FROM t_user</select>
            </mapper>
        """.trimIndent()
        myFixture.configureByText("UserMapper.xml", xml)
        myFixture.complete(CompletionType.BASIC, 1)
        val strings = myFixture.lookupElementStrings.orEmpty()
        // 唯一候选时平台可能直接插入而不弹 lookup
        assertTrue(
            "应给出 Base_Column_List：lookup=$strings, file=${myFixture.file.text}",
            strings.contains("Base_Column_List") || myFixture.file.text.contains("refid=\"Base_Column_List\"")
        )
    }

    // ------------------------------------------------------------------ 重命名

    fun `test 重命名 sql 定义同步短名与跨文件全限定引用`() {
        val (user, order) = addMappers()
        val definition = user.rootTag!!.findSubTags("sql").first()
        MapperRenameSupport.renameSql(project, definition, "User_Columns")

        assertTrue("定义 id 应更新", user.text.contains("<sql id=\"User_Columns\">"))
        assertTrue("本文件短名引用应更新", user.text.contains("refid=\"User_Columns\""))
        assertTrue(
            "跨文件全限定引用应只改最后一段",
            order.text.contains("refid=\"com.example.mapper.UserMapper.User_Columns\"")
        )
        assertFalse("旧 id 不应残留", user.text.contains("Base_Column_List") || order.text.contains("Base_Column_List"))
    }

    fun `test 重命名目标解析覆盖 sql 定义与 include 引用`() {
        val (user, _) = addMappers()
        val definition = user.rootTag!!.findSubTags("sql").first()

        val definitionValue = valueOf(user, "<sql id=\"Base_Column_List\"")
        assertEquals(SqlRenameTarget(definition), MapperRenameSupport.renameTargetOf(definitionValue))

        val usageValue = valueOf(user, "refid=\"Base_Column_List\"")
        assertEquals(SqlRenameTarget(definition), MapperRenameSupport.renameTargetOf(usageValue))
    }

    // ------------------------------------------------------------------ 使用情况跟踪（点击知道哪些地方用了）

    fun `test sql 定义 id 上 Ctrl+B 列出全部 include 引用`() {
        val (user, _) = addMappers()
        val definitionValue = valueOf(user, "<sql id=\"Base_Column_List\"")
        val targets = MapperGotoDeclarationHandler().getGotoDeclarationTargets(definitionValue, 0, null)
        assertNotNull("定义侧应给出引用列表", targets)
        assertEquals("本文件 1 处短名 + 跨文件 1 处全限定", 2, targets!!.size)
        assertTrue(targets.all { it is XmlTag && it.name == "include" })
    }

    fun `test refid 上 Ctrl+B 同时给出定义与全部引用`() {
        val (user, order) = addMappers()
        val includeValue = valueOf(user, "refid=\"Base_Column_List\"")
        val targets = MapperGotoDeclarationHandler().getGotoDeclarationTargets(includeValue, 0, null)
        assertNotNull(targets)
        assertEquals(1, targets!!.count { it is XmlTag && it.name == "sql" })
        assertEquals(2, targets.count { it is XmlTag && it.name == "include" })
        assertTrue("跨文件的引用也要出现在列表里", targets.any { it.containingFile == order })
    }

    fun `test 写错的 refid 不给错误的使用列表`() {
        addMappers()
        val broken = myFixture.addFileToProject(
            "mybatis/BrokenMapper.xml",
            """<mapper namespace="com.example.mapper.BrokenMapper"><select id="x"><include refid="No_Such_Fragment"/></select></mapper>"""
        ) as XmlFile
        val value = valueOf(broken, "refid=\"No_Such_Fragment\"")
        assertNull(MapperGotoDeclarationHandler().getGotoDeclarationTargets(value, 0, null))
    }

    fun `test 侧栏图标覆盖每个 sql 定义与每处 include`() {
        val (user, _) = addMappers()
        val elements = mutableListOf<PsiElement>()
        PsiTreeUtil.findChildrenOfType(user, XmlTag::class.java).forEach { elements += it }
        val markers = mutableListOf<LineMarkerInfo<*>>()
        MapperLineMarkerProvider().collectSlowLineMarkers(elements, markers)
        // 2 个 <sql> 定义 + 2 个 <include>；没有 Java 接口，语句标签不产生图标
        assertEquals(4, markers.size)
    }

    fun `test 没有被使用的 sql 定义也有图标入口`() {
        val file = myFixture.addFileToProject(
            "mybatis/UnusedMapper.xml",
            """<mapper namespace="com.example.mapper.UnusedMapper"><sql id="Unused_Columns">a, b</sql></mapper>"""
        ) as XmlFile
        val elements = mutableListOf<PsiElement>()
        PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java).forEach { elements += it }
        val markers = mutableListOf<LineMarkerInfo<*>>()
        MapperLineMarkerProvider().collectSlowLineMarkers(elements, markers)
        assertEquals("0 处引用也要能点开看到结论", 1, markers.size)
    }
}
