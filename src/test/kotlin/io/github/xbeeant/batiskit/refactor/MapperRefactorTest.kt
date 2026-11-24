package io.github.xbeeant.batiskit.refactor

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttributeValue
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** Mapper XML 引用体系与重命名重构（对标 rockjava/intellij-mybatis-plugin 的 rename / find usage） */
class MapperRefactorTest : BasePlatformTestCase() {

    private val mapperJava = """
        package com.example.mapper;

        public interface UserMapper {
            User selectById(Long id);

            int insert(User record);
        }
    """.trimIndent()

    private val mapperXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <mapper namespace="com.example.mapper.UserMapper">
            <resultMap id="BaseResultMap" type="com.example.User">
                <id property="id" column="id"/>
            </resultMap>
            <select id="selectById" resultMap="BaseResultMap">select 1</select>
            <insert id="insert">insert into t_user values (1)</insert>
        </mapper>
    """.trimIndent()

    private fun addMapper(): Pair<PsiJavaFile, XmlFile> {
        val javaFile = myFixture.addFileToProject("com/example/mapper/UserMapper.java", mapperJava) as PsiJavaFile
        val xmlFile = myFixture.addFileToProject("mybatis/mysql/demo/UserMapper.xml", mapperXml) as XmlFile
        return javaFile to xmlFile
    }

    /** 取属性值（含引号的 XmlAttributeValue）元素 */
    private fun valueOf(file: XmlFile, marker: String): XmlAttributeValue {
        val offset = file.text.indexOf(marker)
        assertTrue("XML 里找不到 $marker", offset >= 0)
        val leaf = file.findElementAt(offset + marker.length / 2)!!
        return PsiTreeUtil.getParentOfType(leaf, XmlAttributeValue::class.java)!!
    }

    private fun referencesAt(file: XmlFile, marker: String): List<PsiReference> {
        val offset = file.text.indexOf(marker) + marker.length / 2
        val reference = file.findReferenceAt(offset) ?: return emptyList()
        return if (reference is com.intellij.psi.impl.source.resolve.reference.impl.PsiMultiReference) {
            reference.references.toList()
        } else {
            listOf(reference)
        }
    }

    // ------------------------------------------------------------------ 引用解析

    fun `test 语句 id 引用解析到接口方法`() {
        val (javaFile, xmlFile) = addMapper()
        val method = javaFile.classes[0].findMethodsByName("selectById", false).first()

        val refs = referencesAt(xmlFile, "\"selectById\"")
        val resolved = refs.mapNotNull { it.resolve() }
        assertTrue("语句 id 应能解析到接口方法", resolved.contains(method))
    }

    fun `test namespace 引用解析到接口类`() {
        val (javaFile, xmlFile) = addMapper()
        // marker 取 FQN 最后一段：namespace 引用只覆盖简单名部分
        val refs = referencesAt(xmlFile, "mapper.UserMapper")
        val resolved = refs.mapNotNull { it.resolve() }
        assertTrue("namespace 应能解析到接口类", resolved.contains(javaFile.classes[0]))
    }

    fun `test resultMap 引用解析到定义`() {
        val (_, xmlFile) = addMapper()
        val refs = referencesAt(xmlFile, "resultMap=\"BaseResultMap\"")
        val resolved = refs.mapNotNull { it.resolve() as? XmlTag }
        assertEquals(1, resolved.size)
        assertEquals("resultMap", resolved[0].name)
        assertEquals("BaseResultMap", resolved[0].getAttributeValue("id"))
    }

    fun `test 解析不到的 resultMap 与 refid 引用是软引用`() {
        // 定义可能在同 namespace 的兄弟 XML、依赖 jar 或还没进索引的文件里：
        // 「静态解析不到」不等于「写错了」，软引用才不会让平台报 Cannot resolve symbol
        val broken = myFixture.addFileToProject(
            "mybatis/BrokenMapper.xml",
            """<mapper namespace="com.example.mapper.BrokenMapper"><select id="x" resultMap="NoSuchMap"><include refid="NoSuchFragment"/></select></mapper>"""
        ) as XmlFile
        // 标记只取属性值（含引号），中点一定落在值内部；取「属性名=值」整段时中点可能落到引号上
        val markers = listOf("\"NoSuchMap\"", "\"NoSuchFragment\"")
        markers.forEach { marker ->
            val refs = referencesAt(broken, marker)
            assertTrue("应生成引用：$marker", refs.isNotEmpty())
            assertTrue("$marker 不应解析到定义", refs.none { it.resolve() != null })
            assertTrue("$marker 应是软引用", refs.all { it.isSoft })
        }
    }

    fun `test resultType 属性解析到 Java 类`() {
        myFixture.addFileToProject("com/example/User.java", "package com.example;\n\npublic class User {}")
        val (_, xmlFile) = addMapper()
        // marker 取类名最后一段：JavaClassReference 按点号分段，中段解析到的是 package
        val refs = referencesAt(xmlFile, "User\"")
        val resolved = refs.mapNotNull { it.resolve() as? PsiClass }
        assertTrue("resultMap type 应能解析到 Java 类", resolved.any { it.qualifiedName == "com.example.User" })
    }

    fun `test resultType 属性类名补全`() {
        myFixture.addFileToProject("com/example/User.java", "package com.example;\n\npublic class User {}")
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.mapper.UserMapper">
                <select id="selectById" resultType="com.example.U<caret>">select 1</select>
            </mapper>
        """.trimIndent()
        myFixture.configureByText("UserMapper.xml", xml)
        myFixture.complete(CompletionType.BASIC, 1)
        val strings = myFixture.lookupElementStrings
        val fileText = myFixture.file.text
        // 唯一候选时平台会自动插入而不显示 lookup，两种表现都算补全生效
        val shown = !strings.isNullOrEmpty() &&
            (strings.contains("User") || strings.contains("com.example.User"))
        val inserted = fileText.contains("com.example.User")
        assertTrue("应补全出 User 类：lookup=$strings, file=$fileText", shown || inserted)
    }

    fun `test 非 mapper XML 不生成引用`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <configuration>
                <select id="selectById">not a mapper</select>
            </configuration>
        """.trimIndent()
        val file = myFixture.configureByText("plain.xml", xml) as XmlFile
        assertTrue(referencesAt(file, "\"selectById\"").isEmpty())
    }

    // ------------------------------------------------------------------ Find Usages

    fun `test 接口方法能查到 XML 语句引用`() {
        val (javaFile, _) = addMapper()
        val method = javaFile.classes[0].findMethodsByName("selectById", false).first()
        val usages = ReferencesSearch.search(method).findAll()
        assertTrue("Find Usages 应包含 XML 语句 id", usages.any { it.element is XmlAttributeValue })
    }

    // ------------------------------------------------------------------ 重命名

    fun `test 重命名方法同步改写 XML 语句 id`() {
        val (javaFile, xmlFile) = addMapper()
        val method = javaFile.classes[0].findMethodsByName("selectById", false).first()
        myFixture.renameElement(method, "fetchById")

        val text = xmlFile.text
        assertTrue("XML 语句 id 应跟随方法重命名", text.contains("id=\"fetchById\""))
        assertFalse("旧 id 不应残留", text.contains("selectById"))
    }

    fun `test 重命名接口同步 namespace`() {
        val (javaFile, xmlFile) = addMapper()
        myFixture.renameElement(javaFile.classes[0], "AccountMapper")
        val namespace = xmlFile.rootTag!!.getAttributeValue("namespace")
        assertEquals("namespace 应跟随接口重命名", "com.example.mapper.AccountMapper", namespace)
    }

    fun `test 重命名接口同步 XML 文件名`() {
        val (javaFile, xmlFile) = addMapper()
        val vf = xmlFile.virtualFile!!
        myFixture.renameElement(javaFile.classes[0], "AccountMapper")
        assertEquals("绑定的 XML 文件应跟随接口重命名", "AccountMapper.xml", vf.name)
    }

    fun `test 文件名与类名不一致的 XML 不被重命名`() {
        val javaFile = myFixture.addFileToProject("com/example/mapper/UserMapper.java", mapperJava) as PsiJavaFile
        val xmlFile = myFixture.addFileToProject("mybatis/mysql/demo/custom-mapper.xml", mapperXml) as XmlFile
        myFixture.renameElement(javaFile.classes[0], "AccountMapper")
        assertEquals("文件名与类名不一致时保持原样", "custom-mapper.xml", xmlFile.virtualFile!!.name)
    }

    fun `test resultMap 定义重命名同步引用处`() {
        val (_, xmlFile) = addMapper()
        val definition = xmlFile.rootTag!!.findSubTags("resultMap").first()
        MapperRenameSupport.renameResultMap(project, definition, "UserMap")

        val text = xmlFile.text
        assertTrue("定义 id 应更新", text.contains("id=\"UserMap\""))
        assertTrue("未限定引用应更新", text.contains("resultMap=\"UserMap\""))
        assertFalse(text.contains("BaseResultMap"))
    }

    fun `test resultMap 定义重命名同步全限定引用`() {
        val (_, xmlFile) = addMapper()
        // 加一条全限定引用
        val root = xmlFile.rootTag!!
        com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction(project) {
            val fragment = com.intellij.psi.PsiFileFactory.getInstance(project).createFileFromText(
                "frag.xml", com.intellij.ide.highlighter.XmlFileType.INSTANCE,
                "<mapper><select id=\"qualified\" resultMap=\"com.example.mapper.UserMapper.BaseResultMap\">select 2</select></mapper>"
            )
            root.addSubTag((fragment as XmlFile).rootTag!!.subTags.first().copy() as XmlTag, true)
        }
        val definition = root.findSubTags("resultMap").first()
        MapperRenameSupport.renameResultMap(project, definition, "UserMap")

        val text = xmlFile.text
        assertTrue("全限定引用应更新最后一段", text.contains("com.example.mapper.UserMapper.UserMap"))
        assertFalse(text.contains("BaseResultMap"))
    }

    fun `test 重命名目标解析`() {
        val (javaFile, xmlFile) = addMapper()

        // 语句 id → 方法
        val statementValue = valueOf(xmlFile, "\"selectById\"")
        val method = javaFile.classes[0].findMethodsByName("selectById", false).first()
        assertEquals(MethodRenameTarget(method), MapperRenameSupport.renameTargetOf(statementValue))

        // resultMap 定义 id → 定义标签
        val definitionValue = valueOf(xmlFile, "id=\"BaseResultMap\"")
        val definition = xmlFile.rootTag!!.findSubTags("resultMap").first()
        assertEquals(ResultMapRenameTarget(definition), MapperRenameSupport.renameTargetOf(definitionValue))

        // resultMap 引用处 → 同一个定义
        val usageValue = valueOf(xmlFile, "resultMap=\"BaseResultMap\"")
        assertEquals(ResultMapRenameTarget(definition), MapperRenameSupport.renameTargetOf(usageValue))
    }

    fun `test 处理器在语句 id 与 resultMap 上可用`() {
        val javaFile = myFixture.addFileToProject("com/example/mapper/UserMapper.java", mapperJava) as PsiJavaFile
        assertNotNull(javaFile)
        val xmlFile = myFixture.configureByText("UserMapper.xml", mapperXml) as XmlFile
        val handler = MapperRenameHandler()

        fun dataContext() = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, myFixture.editor)
            .add(CommonDataKeys.PSI_FILE, xmlFile)
            .build()

        // 光标移到语句 id 上
        val statementOffset = xmlFile.text.indexOf("\"selectById\"") + 3
        myFixture.editor.caretModel.moveToOffset(statementOffset)
        assertTrue("语句 id 上应可发起重命名", handler.isAvailableOnDataContext(dataContext()))

        // 光标移到 resultMap 定义 id 上
        val resultMapOffset = xmlFile.text.indexOf("id=\"BaseResultMap\"") + 3
        myFixture.editor.caretModel.moveToOffset(resultMapOffset)
        assertTrue("resultMap 定义上应可发起重命名", handler.isAvailableOnDataContext(dataContext()))

        // 光标在无关位置（insert 语句正文里）→ 不可用
        val bodyOffset = xmlFile.text.indexOf("insert into t_user")
        myFixture.editor.caretModel.moveToOffset(bodyOffset)
        assertFalse(handler.isAvailableOnDataContext(dataContext()))
    }

    // ------------------------------------------------------------------ @Mapper 注解

    fun `test 为未注解的 mapper 接口添加 Mapper 注解`() {
        myFixture.configureByText(
            "UserDao.java",
            """
            package com.example.mapper;

            public interface UserDao {
                int <caret>insert(User record);
            }
            """.trimIndent()
        )
        val intention = myFixture.findSingleIntention("为 Mapper 接口添加 @Mapper 注解")
        myFixture.launchAction(intention)

        val clazz = (myFixture.file as PsiJavaFile).classes[0]
        assertTrue("应添加 @Mapper 注解", AddMapperAnnotationIntention.hasMapperAnnotation(clazz))
        // 测试环境里 addAnnotation 保留全限定名形式，两种写法都算补上了
        assertTrue(
            "应补 import（或全限定写法）",
            myFixture.file.text.contains("import org.apache.ibatis.annotations.Mapper") ||
                myFixture.file.text.contains("@org.apache.ibatis.annotations.Mapper")
        )
    }

    fun `test 已有 Mapper 注解时不提供修复`() {
        myFixture.configureByText(
            "UserMapper.java",
            """
            package com.example.mapper;

            import org.apache.ibatis.annotations.Mapper;

            @Mapper
            public interface UserMapper {
                int insert(User record);
            }
            """.trimIndent()
        )
        assertTrue("已有 @Mapper 时不应出现修复", myFixture.availableIntentions.none { it.text.contains("@Mapper") })
    }
}
