package io.github.xbeeant.batiskit.template

import io.github.xbeeant.batiskit.config.DateKind
import io.github.xbeeant.batiskit.config.GenerationConfig
import io.github.xbeeant.batiskit.config.dateKindEnum
import io.github.xbeeant.batiskit.generate.GenContext
import io.github.xbeeant.batiskit.model.ColumnInfo
import io.github.xbeeant.batiskit.model.JavaType
import io.github.xbeeant.batiskit.model.TableInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 大括号占位符的短名字与排版块（`{fields}` / `{accessors}` / `{mapperMethods}`）。
 *
 * 这两个能力是同一个诉求的两半：模板作者只想要「填空题」——
 * 短名字负责把数据库里的值填进去，排版块负责把按列展开的长段落一次搬过来。
 * （Mapper XML 的语句不在这个机制里：它们直接写在 `mapperXml.tpl` 里，改一个文件即可。）
 */
class TemplateBlocksTest {

    private val table = TableInfo(
        tableName = "t_user",
        entityName = "User",
        comment = "用户表",
        columns = listOf(
            ColumnInfo("id", "id", "bigint", JavaType("Long", null, "BIGINT"), "主键", true, true, false),
            ColumnInfo("user_name", "userName", "varchar(64)", JavaType("String", null, "VARCHAR"), "用户名", false, false, true),
            ColumnInfo(
                "create_time", "createTime", "datetime",
                JavaType("LocalDateTime", "java.time.LocalDateTime", "TIMESTAMP"), "创建时间", false, false, true
            )
        )
    )

    private fun config(block: GenerationConfig.() -> Unit = {}): GenerationConfig =
        GenerationConfig().apply {
            basePackage = "com.example.demo"
            tablePrefixes = "t_,sys_"
            author = "tester"
            dateKindEnum = DateKind.LOCAL_DATE_TIME
            block()
        }

    private fun model(cfg: GenerationConfig = config()): Map<String, Any?> =
        TemplateModel.build(table, GenContext(cfg))

    // ------------------------------------------------------------------ 短名字

    @Test
    fun `短名字是数据库相关的值`() {
        val model = model()
        assertEquals("User", model["Model"])
        assertEquals("user", model["model"])
        assertEquals("用户表", model["TableComment"])
        // 去掉配置里的表前缀，保持库里的蛇形写法
        assertEquals("user", model["tableName"])
        assertEquals("user", model["tableNameKebab"])
        assertEquals("Long", model["key"])
    }

    @Test
    fun `短名字可以被模板直接渲染`() {
        // package 由 TemplateRegistry.render 按模板 output 路径注入，这里手工给一个。
        // 产物类名不在模型里：像 Mapper / Service / Controller 这种「基础名 + 固定后缀」的名字由模板自己拼
        val text = Template.compile(
            """
            package {package};

            public class {table.namingBase}RestController extends Base<{Model}, {key}> {
            }
            """.trimIndent()
        ).render(model() + ("package" to "com.example.demo.rest")).text

        assertTrue(text.contains("package com.example.demo.rest;"))
        assertTrue(text.contains("public class UserRestController extends Base<User, Long> {"))
    }

    // ------------------------------------------------------------------ 排版块

    @Test
    fun `字段块按列展开并带区间字段`() {
        val fields = model()["fields"] as String
        assertTrue(fields.contains("/**\n     * 用户名\n     */"))
        assertTrue(fields.contains("private String userName;"))
        assertTrue(fields.contains("private LocalDateTime createTime;"))
        // 日期列生成 Begin/End（供条件查询用）
        assertTrue(fields.contains("private LocalDateTime createTimeBegin;"))
        assertTrue(fields.contains("private LocalDateTime createTimeEnd;"))
    }

    /**
     * 字段块不再有开关：日期区间字段恒生成，`@Schema` 不再出现
     * （lombok / swagger 这些风格开关已随「行为开关」一起从配置里删掉，风格由模板自己写）。
     */
    @Test
    fun `字段块不带风格开关`() {
        val fields = model()["fields"] as String
        assertTrue(fields.contains("private LocalDateTime createTimeBegin;"))
        assertTrue(!fields.contains("@Schema"))
        assertTrue(!fields.contains("@Getter"))
    }

    @Test
    fun `访问器块生成 getter 与 setter`() {
        val accessors = model()["accessors"] as String
        assertTrue(accessors.contains("public String getUserName() {"))
        assertTrue(accessors.contains("public void setUserName(String userName) {"))
    }

    /**
     * 方法块现在恒展开：内置 mapper 模板继承了基类所以不引用它，
     * 但用户模板拿这个块去写自包含接口时必须拿到完整清单。
     */
    @Test
    fun `Mapper 方法块恒展开为完整清单`() {
        val methods = model()["mapperMethods"] as String
        assertTrue(methods.contains("selectByPrimaryKey("))
        assertTrue(methods.contains("int insertSelective(User record);"))
        assertTrue(methods.contains("fuzzySearchByPager"))
        assertTrue(methods.contains("batchInsertSelective"))
    }

    @Test
    fun `排版块加载失败会指到具体文件`() {
        val error = try {
            TemplateBlocks.render("不存在的块", emptyMap())
            null
        } catch (e: TemplateException) {
            e
        }
        assertTrue(error != null && error.templateName == "blocks/不存在的块.tpl")
    }
}
