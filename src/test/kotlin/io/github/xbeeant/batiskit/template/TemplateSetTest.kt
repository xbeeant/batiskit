package io.github.xbeeant.batiskit.template

import io.github.xbeeant.batiskit.config.GenerationConfig
import io.github.xbeeant.batiskit.config.TargetFramework
import io.github.xbeeant.batiskit.config.frameworkEnum
import io.github.xbeeant.batiskit.generate.GenContext
import io.github.xbeeant.batiskit.model.ColumnInfo
import io.github.xbeeant.batiskit.model.JavaType
import io.github.xbeeant.batiskit.model.TableInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板方案：`templates/` 下的**文件夹即方案**。
 *
 * 这些用例都是纯逻辑（不碰 IDE），用户模板通过 [TemplateSettings] 的兜底实例注入，
 * 每个用例结束后清空，避免互相影响。
 */
class TemplateSetTest {

    @After
    fun clearUserTemplates() {
        TemplateSettings.getInstance().state.templates.clear()
    }

    private fun userTemplate(id: String, set: String = "", framework: String = "any", body: String = "") {
        val header = buildString {
            append("---\n")
            append("id: ").append(id).append('\n')
            append("name: ").append(id).append('\n')
            append("output: {basePackageDir}/model/{entity}.java\n")
            if (set.isNotBlank()) {
                append("set: ").append(set).append('\n')
            }
            if (framework.isNotBlank()) {
                append("framework: ").append(framework).append('\n')
            }
            append("---\n")
        }
        TemplateSettings.getInstance().state.templates.add(
            TemplateSettings.UserTemplate().apply {
                this.id = id
                this.text = header + body.ifBlank { "package {package};\npublic class {entity} {}\n" }
            }
        )
    }

    @Test
    fun `内置方案来自 templates 下的文件夹`() {
        val sets = TemplateRegistry.sets()
        // 原有两套（依赖内部基类）+ 两套纯模板（自包含、不继承父类）
        assertEquals(
            listOf("mybatis-plus-pure", "mybatis-pure"),
            sets.map { it.id }
        )
        assertEquals(TargetFramework.MYBATIS, TemplateRegistry.frameworkOf("mybatis-pure"))
        assertEquals(TargetFramework.MYBATIS_PLUS, TemplateRegistry.frameworkOf("mybatis-plus-pure"))
        assertEquals(7, TemplateRegistry.forSet("mybatis-pure").size)
        assertEquals(6, TemplateRegistry.forSet("mybatis-plus-pure").size)
    }

    /**
     * 排版块在 `resources/blocks/`，不在 `templates/` 下 —— 这里守的是「别把它挪回去」：
     * 一旦回去，`templates/` 里就得多一个不是方案的目录，扫描又得开例外。
     */
    @Test
    fun `排版块目录不算方案`() {
        assertFalse("blocks" in TemplateRegistry.sets().map { it.id })
        assertFalse("blocks" in TemplateRegistry.all().map { it.set })
    }

    @Test
    fun `全是 any 时推不出框架`() {
        userTemplate("onlyMine", set = "mine", framework = "any")
        assertNull(TemplateRegistry.frameworkOf("mine"))
    }

    @Test
    fun `自定义套的框架取首个具体声明`() {
        userTemplate("mixA", set = "mix", framework = "any")
        userTemplate("mixB", set = "mix", framework = "mybatis-plus")
        assertEquals(TargetFramework.MYBATIS_PLUS, TemplateRegistry.frameworkOf("mix"))
    }

    @Test
    fun `选了方案就用它`() {
        userTemplate("legacyEntity", set = "legacy", framework = "mybatis")
        val config = GenerationConfig().apply {
            frameworkEnum = TargetFramework.MYBATIS_PLUS
            templateSet = "legacy"
        }
        assertEquals("legacy", TemplateRegistry.resolveSet(config))
    }

    // ------------------------------------------------------------------ 包从模板来

    // ------------------------------------------------------------------ 整套冒烟

    /**
     * 两套方案的每一份模板都要能真的渲染出来：路径不含大括号残留、java 模板正文有 package 行。
     *
     * 这是「目录即方案」最基础的护栏——新建一个文件夹忘了改 `output` 里的占位符，
     * 这里会立刻指出来，而不是等用户在 IDE 里看到 `{basePackageDir}` 这样的目录名。
     */
    @Test
    fun `两套内置模板都能渲染出路径与包名`() {
        val table = TableInfo(
            tableName = "t_user",
            entityName = "User",
            comment = "用户表",
            columns = listOf(
                ColumnInfo(
                    columnName = "id",
                    fieldName = "id",
                    dbType = "bigint",
                    javaType = JavaType("Long", null, "BIGINT"),
                    comment = "主键",
                    primaryKey = true,
                    autoIncrement = true,
                    nullable = false
                )
            )
        )
        listOf("mybatis", "mybatis-plus").forEach { setId ->
            val ctx = GenContext(
                GenerationConfig().apply {
                    basePackage = "com.example.demo"
                    frameworkEnum = TemplateRegistry.frameworkOf(setId) ?: TargetFramework.MYBATIS
                }
            )
            val pathModel = TemplateModel.build(table, ctx)
            TemplateRegistry.forSet(setId).forEach { spec ->
                val path = TemplateRegistry.renderPath(spec, pathModel)
                assertFalse("$setId/${spec.fileName} 的 output 还有未替换的占位符：$path", path.contains('{'))
                if (spec.root == TemplateSpec.ROOT_JAVA) {
                    assertTrue("$setId/${spec.fileName} 应落在源码包下：$path", path.startsWith("com/example/demo/"))
                }
            }
        }
    }

    /**
     * 正文也要真的渲染出来：不留 `${...}` / `{...}` 占位符、不留 `#if` 之类的指令、没有未定义变量告警。
     *
     * 这一条比路径冒烟重要得多——模板里少删一个 `#if($cfg.useLombok)` 就会留下半截括号，
     * 或者引用一个已经从模型里删掉的键（渲染成空串，产物静静地少一段）。告警里能直接看到变量名。
     */
    @Test
    fun `两套内置模板都能渲染出正文且无告警`() {
        listOf("mybatis", "mybatis-plus").forEach { setId ->
            val ctx = GenContext(
                GenerationConfig().apply {
                    basePackage = "com.example.demo"
                    frameworkEnum = TemplateRegistry.frameworkOf(setId) ?: TargetFramework.MYBATIS
                }
            )
            val specs = TemplateRegistry.forSet(setId)
            val model = TemplateModel.build(sampleTable, ctx)

            specs.forEach { spec ->
                val path = TemplateRegistry.renderPath(spec, model)
                // 与 CodeGenerator 同一条链路：把 package 也放进作用域，再编译正文
                val scoped = HashMap(model)
                scoped["package"] = TemplateRegistry.packageOf(spec, path)
                val result = Template.compile(spec.body, spec.fileName, TemplatePlaceholders.namesOf(scoped))
                    .render(scoped)
                val text = result.text

                assertTrue("$setId/${spec.fileName} 有未定义变量：${result.warnings}", result.warnings.isEmpty())
                assertFalse("$setId/${spec.fileName} 正文残留美元占位符", text.contains("\${"))
                assertFalse("$setId/${spec.fileName} 正文残留指令", text.contains("#if") || text.contains("#foreach"))
                if (spec.root == TemplateSpec.ROOT_JAVA) {
                    assertTrue(
                        "$setId/${spec.fileName} 正文缺少 package 行",
                        text.contains("package " + TemplateRegistry.packageOf(spec, path) + ";")
                    )
                    assertTrue("$setId/${spec.fileName} 正文缺少类名 User", text.contains("User"))
                }
            }
        }
    }

    /**
     * 类级 JavaDoc = 表注释 + 后缀，后缀是**恒追加**的，不是「注释为空时的兜底」。
     *
     * 之前第二个参数是兜底、第三个才是后缀，导致有注释的表生成出来后缀全丢，
     * 所有类的注释长得一模一样。
     */
    @Test
    fun `JavaDoc 由表注释加后缀组成`() {
        val ctx = GenContext(
            GenerationConfig().apply {
                basePackage = "com.example.demo"
                frameworkEnum = TargetFramework.MYBATIS
            }
        )
        val model = TemplateModel.build(sampleTable, ctx)
        val javaDoc = (model["util"] as Map<*, *>)["javaDoc"] as TemplateFunction

        val text = javaDoc.call(listOf("用户表", "DTO")).toString()
        assertTrue("表注释与后缀都要出现：$text", text.contains(" * 用户表 DTO"))

        // 表注释为空时只剩后缀，且不能留下前导空格
        val blank = javaDoc.call(listOf("", "DTO")).toString()
        assertTrue("空注释时只剩后缀：$blank", blank.contains(" * DTO"))
        assertFalse("空注释时不该出现两个空格：$blank", blank.contains(" *  DTO"))
    }

    private val sampleTable = TableInfo(
        tableName = "t_user",
        entityName = "User",
        comment = "用户表",
        columns = listOf(
            ColumnInfo(
                columnName = "id",
                fieldName = "id",
                dbType = "bigint",
                javaType = JavaType("Long", null, "BIGINT"),
                comment = "主键",
                primaryKey = true,
                autoIncrement = true,
                nullable = false
            ),
            ColumnInfo(
                columnName = "user_name",
                fieldName = "userName",
                dbType = "varchar(64)",
                javaType = JavaType("String", null, "VARCHAR"),
                comment = "用户名",
                primaryKey = false,
                autoIncrement = false,
                nullable = true
            )
        )
    )
}
