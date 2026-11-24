package io.github.xbeeant.batiskit.navigation

import com.intellij.psi.PsiJavaFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Mapper 接口 ↔ XML 双向解析测试（namespace + id 匹配规则） */
class MapperNavigationTest : BasePlatformTestCase() {

    fun `test 方法名解析到 XML 语句片段并反查`() {
        val javaText = """
            package com.example.mapper;

            public interface UserMapper {
                User selectById(Long id);

                int insert(User record);
            }
        """.trimIndent()
        val xmlText = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN" "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="com.example.mapper.UserMapper">
                <select id="selectById" resultMap="BaseResultMap">select 1</select>
                <insert id="insert">insert into t_user values (1)</insert>
                <select id="selectOthers" resultMap="BaseResultMap">select 2</select>
            </mapper>
        """.trimIndent()

        val javaFile = myFixture.addFileToProject("com/example/mapper/UserMapper.java", javaText) as PsiJavaFile
        myFixture.addFileToProject("mybatis/mysql/demo/UserMapper.xml", xmlText)

        val mapperClass = javaFile.classes[0]
        val selectById = mapperClass.findMethodsByName("selectById", false).first()
        val insert = mapperClass.findMethodsByName("insert", false).first()

        // 方法 → 语句片段（只匹配同 id，不串到其它语句）
        val statements = MapperXmlResolver.statements(selectById)
        assertEquals(1, statements.size)
        assertEquals("selectById", statements[0].getAttributeValue("id"))
        assertEquals("select", statements[0].name)

        // 反向：语句片段 → 方法
        assertEquals(selectById, MapperXmlResolver.method(statements[0]))
        val insertStatements = MapperXmlResolver.statements(insert)
        assertEquals(1, insertStatements.size)
        assertEquals(insert, MapperXmlResolver.method(insertStatements[0]))

        // 接口 → XML 根标签（文件级跳转落点）
        val root = MapperXmlResolver.xmlRoot(mapperClass)
        assertNotNull(root)
        assertEquals("com.example.mapper.UserMapper", root?.getAttributeValue("namespace"))
    }

    fun `test namespace 不匹配时不误报`() {
        val javaText = """
            package com.example.mapper;

            public interface OrderMapper {
                int insert(Order record);
            }
        """.trimIndent()
        val xmlText = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.mapper.UserMapper">
                <insert id="insert">insert into t_user values (1)</insert>
            </mapper>
        """.trimIndent()

        val javaFile = myFixture.addFileToProject("com/example/mapper/OrderMapper.java", javaText) as PsiJavaFile
        myFixture.addFileToProject("mybatis/mysql/demo/UserMapper.xml", xmlText)

        val insert = javaFile.classes[0].findMethodsByName("insert", false).first()
        assertTrue("namespace 不匹配时不应解析到语句", MapperXmlResolver.statements(insert).isEmpty())
        assertNull("namespace 不匹配时不应有 XML 落点", MapperXmlResolver.xmlRoot(javaFile.classes[0]))
    }

    fun `test intention 在 XML 中生成语句骨架`() {
        myFixture.addFileToProject(
            "mybatis/mysql/demo/UserMapper.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.mapper.UserMapper">
            </mapper>
            """.trimIndent()
        )
        myFixture.configureByText(
            "UserMapper.java",
            """
            package com.example.mapper;

            public interface UserMapper {
                int <caret>insert(User record);
            }
            """.trimIndent()
        )

        val intention = myFixture.findSingleIntention("在 Mapper XML 中生成对应语句")
        myFixture.launchAction(intention)

        val xml = (psiManager.findFile(
            myFixture.findFileInTempDir("mybatis/mysql/demo/UserMapper.xml")
        ) as com.intellij.psi.xml.XmlFile).rootTag!!
        val tags = xml.findSubTags("insert")
        assertEquals(1, tags.size)
        assertEquals("insert", tags[0].getAttributeValue("id"))
    }

    fun `test resultMap 引用解析到定义`() {
        val xmlText = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.mapper.UserMapper">
                <resultMap id="BaseResultMap" type="com.example.User">
                    <id property="id" column="id"/>
                </resultMap>
                <select id="selectById" resultMap="BaseResultMap">select 1</select>
                <update id="update" resultMap="BaseResultMap">update t</update>
                <select id="qualified" resultMap="com.example.mapper.UserMapper.BaseResultMap">select 2</select>
                <select id="notRef" resultMap="OtherMap">select 3</select>
                <resultMap id="ExtendMap" type="com.example.User" extends="BaseResultMap"/>
                <select id="nested" resultMap="NestedMap">select 4</select>
                <resultMap id="NestedMap" type="com.example.UserDetail">
                    <association property="user" resultMap="BaseResultMap"/>
                </resultMap>
            </mapper>
        """.trimIndent()
        val file = myFixture.addFileToProject("mybatis/mysql/demo/UserMapper.xml", xmlText) as com.intellij.psi.xml.XmlFile
        val root = file.rootTag!!

        fun selectOf(id: String) = root.findSubTags("select").first { it.getAttributeValue("id") == id }

        // 引用 → 定义（resultMap / extends / 嵌套 association）
        val definitions = MapperXmlResolver.resultMapDefinitions(selectOf("selectById"), "BaseResultMap")
        assertEquals(1, definitions.size)
        assertEquals("BaseResultMap", definitions[0].getAttributeValue("id"))
        // 未定义的引用解析不到
        assertEquals(0, MapperXmlResolver.resultMapDefinitions(selectOf("notRef"), "OtherMap").size)

        // 全限定名引用（跨 namespace 语法）也能解析
        assertEquals(1, MapperXmlResolver.resultMapDefinitions(selectOf("qualified"), "com.example.mapper.UserMapper.BaseResultMap").size)

        // 定义 → 引用：两条语句 + extends + 嵌套 association + 全限定名语句
        val definition = definitions[0]
        val usages = MapperXmlResolver.resultMapUsages(definition)
        val usageIds = usages.map { it.getAttributeValue("id") }.toSet()
        assertTrue(usageIds.contains("selectById"))
        assertTrue(usageIds.contains("update"))
        assertTrue(usageIds.contains("qualified"))
        assertTrue(usageIds.contains("ExtendMap"))
        // 嵌套引用挂在 <association> 标签上（它没有 id 属性）
        assertTrue(usages.any { it.name == "association" && it.getAttributeValue("property") == "user" })
        assertEquals(5, usages.size)
    }

    fun `test resultMap 未限定引用不跨文件误报`() {
        val userXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.mapper.UserMapper">
                <resultMap id="BaseResultMap" type="com.example.User"/>
            </mapper>
        """.trimIndent()
        // OrderMapper 里恰好也有同名的 BaseResultMap，但它的未限定引用只属于它自己的 namespace
        val orderXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.mapper.OrderMapper">
                <resultMap id="BaseResultMap" type="com.example.Order"/>
                <select id="selectOrder" resultMap="BaseResultMap">select 1</select>
                <select id="selectUser" resultMap="com.example.mapper.UserMapper.BaseResultMap">select 2</select>
            </mapper>
        """.trimIndent()
        val userFile = myFixture.addFileToProject("mybatis/mysql/demo/UserMapper.xml", userXml) as com.intellij.psi.xml.XmlFile
        myFixture.addFileToProject("mybatis/mysql/demo/OrderMapper.xml", orderXml)

        val definition = MapperXmlResolver.resultMapDefinitions(userFile.rootTag!!, "BaseResultMap").first()
        val usages = MapperXmlResolver.resultMapUsages(definition)
        // 只算全限定引用；OrderMapper 里未限定的同名引用不算
        assertEquals(1, usages.size)
        assertEquals("selectUser", usages[0].getAttributeValue("id"))
    }

    fun `test 同 namespace 多个 XML 的短名 extends 与 refid 跨文件解析`() {
        // 一个 Mapper 接口拆成多个 XML：基础 XML 放 resultMap / sql 片段，扩展 XML 放自定义语句
        val baseXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.mapper.UserMapper">
                <resultMap id="ResultMapWithBLOBs" type="com.example.User"/>
                <resultMap id="LinkTaskViewMap" type="com.example.Task"/>
                <sql id="fuzzySearchLinkTaskByPagerExtSql">select * from t_task</sql>
            </mapper>
        """.trimIndent()
        val extXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.mapper.UserMapper">
                <resultMap id="ModuleLinkTaskViewMap" type="com.example.LinkTask" extends="ResultMapWithBLOBs">
                    <association property="task" resultMap="LinkTaskViewMap"/>
                </resultMap>
                <select id="fuzzySearchLinkTaskByPagerExt" resultMap="ModuleLinkTaskViewMap">
                    select t.* from (<include refid="fuzzySearchLinkTaskByPagerExtSql"/>) t
                </select>
            </mapper>
        """.trimIndent()
        val base = myFixture.addFileToProject("mybatis/UserMapper.xml", baseXml) as com.intellij.psi.xml.XmlFile
        val ext = myFixture.addFileToProject("mybatis/UserMapperExt.xml", extXml) as com.intellij.psi.xml.XmlFile
        val extRoot = ext.rootTag!!
        val extended = extRoot.findSubTags("resultMap")
            .first { it.getAttributeValue("id") == "ModuleLinkTaskViewMap" }

        val definitions = MapperXmlResolver.resultMapDefinitions(extended, "ResultMapWithBLOBs")
        assertEquals(1, definitions.size)
        assertEquals("ResultMapWithBLOBs", definitions[0].getAttributeValue("id"))
        assertEquals(base, definitions[0].containingFile)

        // extends 属性值上的真引用也要解析到兄弟文件（否则平台会报 Cannot resolve symbol）
        val offset = ext.text.indexOf("extends=\"ResultMapWithBLOBs\"") + "extends=\"".length
        val refs = when (val reference = ext.findReferenceAt(offset)) {
            is com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference ->
                reference.references.toList()
            null -> emptyList()
            else -> listOf(reference)
        }
        assertTrue("extends 短名引用应解析到基础 XML 的 resultMap", refs.any { it.resolve() == definitions[0] })

        // 嵌套 association / 语句 resultMap / include refid 同样按 namespace 跨文件解析
        val association = extended.findSubTags("association").first()
        assertEquals(1, MapperXmlResolver.resultMapDefinitions(association, "LinkTaskViewMap").size)
        val include = extRoot.findSubTags("select").first().findSubTags("include").first()
        val sql = MapperXmlResolver.sqlDefinitions(include, "fuzzySearchLinkTaskByPagerExtSql")
        assertEquals(1, sql.size)
        assertEquals(base, sql[0].containingFile)

        // 补全候选列兄弟文件的短 id，不必逼用户写全限定名
        assertTrue(MapperXmlResolver.resultMapIdVariants(extended).contains("ResultMapWithBLOBs"))
        assertTrue(MapperXmlResolver.sqlIdVariants(extended).contains("fuzzySearchLinkTaskByPagerExtSql"))

        // 定义侧 Find Usages 也收进兄弟文件里的 extends
        assertTrue(
            MapperXmlResolver.resultMapUsages(definitions[0]).any {
                it.containingFile == ext && it.getAttributeValue("id") == "ModuleLinkTaskViewMap"
            }
        )
    }

    fun `test 短名 resultMap 不串到不同 namespace 的 XML`() {
        myFixture.addFileToProject(
            "mybatis/UserMapper.xml",
            """<mapper namespace="com.example.mapper.UserMapper"><resultMap id="BaseResultMap" type="com.example.User"/></mapper>"""
        ) as com.intellij.psi.xml.XmlFile
        val order = myFixture.addFileToProject(
            "mybatis/OrderMapper.xml",
            """<mapper namespace="com.example.mapper.OrderMapper"><select id="list" resultMap="BaseResultMap">select 1</select></mapper>"""
        ) as com.intellij.psi.xml.XmlFile
        val select = order.rootTag!!.findSubTags("select").first()

        assertTrue(
            "不同 namespace 必须写全限定名，短名不该串过去",
            MapperXmlResolver.resultMapDefinitions(select, "BaseResultMap").isEmpty()
        )
        assertEquals(
            1,
            MapperXmlResolver.resultMapDefinitions(select, "com.example.mapper.UserMapper.BaseResultMap").size
        )
    }

    fun `test 处理器在属性值与定义 id 上都能跳转`() {
        val xmlText = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.mapper.UserMapper">
                <resultMap id="BaseResultMap" type="com.example.User">
                    <id property="id" column="id"/>
                </resultMap>
                <select id="selectById" resultMap="BaseResultMap">select 1</select>
            </mapper>
        """.trimIndent()
        val file = myFixture.configureByText("UserMapper.xml", xmlText) as com.intellij.psi.xml.XmlFile
        val root = file.rootTag!!
        val handler = MapperGotoDeclarationHandler()

        fun valueElementOf(tag: com.intellij.psi.xml.XmlTag, attr: String): com.intellij.psi.xml.XmlAttributeValue {
            val attribute = tag.getAttribute(attr)!!
            return PsiTreeUtil.getChildOfType(attribute, com.intellij.psi.xml.XmlAttributeValue::class.java)!!
        }

        // 语句的 resultMap 属性值 → <resultMap> 定义
        val select = root.findSubTags("select").first()
        val targets = handler.getGotoDeclarationTargets(valueElementOf(select, "resultMap"), 0, null)
        assertEquals(1, targets?.size)
        assertEquals("resultMap", (targets!![0] as com.intellij.psi.xml.XmlTag).name)
        assertEquals("BaseResultMap", (targets[0] as com.intellij.psi.xml.XmlTag).getAttributeValue("id"))

        // <resultMap> 定义的 id 值 → 引用处列表
        val definition = root.findSubTags("resultMap").first()
        val backTargets = handler.getGotoDeclarationTargets(valueElementOf(definition, "id"), 0, null)
        assertEquals(1, backTargets?.size)
        assertEquals("selectById", (backTargets!![0] as com.intellij.psi.xml.XmlTag).getAttributeValue("id"))
    }

    fun `test intention 为参数添加 Param 注解`() {
        myFixture.addFileToProject(
            "mybatis/mysql/demo/UserMapper.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.mapper.UserMapper">
            </mapper>
            """.trimIndent()
        )
        myFixture.configureByText(
            "UserMapper.java",
            """
            package com.example.mapper;

            public interface UserMapper {
                User <caret>selectById(Long id);
            }
            """.trimIndent()
        )

        val intention = myFixture.findSingleIntention("为 Mapper 方法参数添加 @Param")
        myFixture.launchAction(intention)

        val text = (myFixture.file as PsiJavaFile).text
        assertTrue("应生成 @Param 注解", text.contains("@org.apache.ibatis.annotations.Param(\"id\")"))
    }
}
