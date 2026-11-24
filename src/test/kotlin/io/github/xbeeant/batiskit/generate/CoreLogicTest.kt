package io.github.xbeeant.batiskit.generate

import io.github.xbeeant.batiskit.config.DateKind
import io.github.xbeeant.batiskit.support.Naming
import io.github.xbeeant.batiskit.support.SensitiveData
import io.github.xbeeant.batiskit.support.TypeMapping
import io.github.xbeeant.batiskit.template.TemplateCatalog
import io.github.xbeeant.batiskit.template.TemplateMigration
import io.github.xbeeant.batiskit.template.TemplateSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 纯逻辑回归：类型映射 / 命名 / 来源标记 / diff / 模板迁移。
 *
 * 这几块以前一条测试都没有，偏偏 CHANGELOG 里修过的 tinyint、unsigned 归一化、
 * 「产物名退出模型」都出在这里。它们不依赖 IDE，跑得又快，适合当改动模板引擎时的第一道闸。
 */
class CoreLogicTest {

    // ---------------------------------------------------------------- 类型映射

    @Test
    fun `tinyint 一律映射 Boolean 且 jdbcType 为 BOOLEAN`() {
        listOf(0, 1, 4).forEach { size ->
            val type = TypeMapping.map("tinyint($size)", size, DateKind.LOCAL_DATE_TIME)
            assertEquals("Boolean", type.simple)
            assertEquals("BOOLEAN", type.jdbcType)
        }
    }

    @Test
    fun `精度与 unsigned 后缀归一化后仍能命中类型`() {
        assertEquals("Long", TypeMapping.map("bigint unsigned", 20, DateKind.LOCAL_DATE_TIME).simple)
        assertEquals("Integer", TypeMapping.map("int(11)", 11, DateKind.LOCAL_DATE_TIME).simple)
        assertEquals("String", TypeMapping.map("varchar(64)", 64, DateKind.LOCAL_DATE_TIME).simple)
    }

    @Test
    fun `decimal 带全限定名与 DECIMAL`() {
        val type = TypeMapping.map("decimal(18,2)", 18, DateKind.LOCAL_DATE_TIME)
        assertEquals("BigDecimal", type.simple)
        assertEquals("java.math.BigDecimal", type.qualified)
        assertEquals("DECIMAL", type.jdbcType)
    }

    @Test
    fun `日期类型跟随 dateKind 配置`() {
        val javaTime = TypeMapping.map("datetime", 0, DateKind.LOCAL_DATE_TIME)
        val utilDate = TypeMapping.map("datetime", 0, DateKind.JAVA_UTIL_DATE)
        assertEquals("LocalDateTime", javaTime.simple)
        assertEquals("java.time.LocalDateTime", javaTime.qualified)
        assertEquals("Date", utilDate.simple)
        assertEquals("java.util.Date", utilDate.qualified)
    }

    @Test
    fun `未知类型兜底成 VARCHAR 字符串`() {
        assertEquals("String", TypeMapping.map("some_vendor_type(3)", 3, DateKind.LOCAL_DATE_TIME).simple)
    }

    // ---------------------------------------------------------------- 命名

    @Test
    fun `表名去前缀后转帕斯卡并拼实体后缀`() {
        assertEquals("User", Naming.entityName("t_user", "t_,sys_", ""))
        assertEquals("UserInfoDO", Naming.entityName("sys_user_info", "t_,sys_", "DO"))
        assertEquals("User", Naming.entityName("user", "t_", ""))
    }

    @Test
    fun `列名转驼峰且避开 Java 关键字`() {
        assertEquals("createTime", Naming.fieldName("create_time"))
        assertEquals("classValue", Naming.fieldName("class"))
        assertTrue(Naming.fieldName("1st_name").startsWith("f"))
    }

    @Test
    fun `短名字与路径名派生`() {
        assertEquals("audit-log", Naming.kebab("AuditLog"))
        assertEquals("access_log", Naming.baseTableName("t_access_log", "t_"))
        assertEquals("user", Naming.applyRename("t_user", "t_", ""))
    }

    // ---------------------------------------------------------------- 来源标记

    private val spec = TemplateSpec(
        id = "entity",
        name = "实体",
        output = "{basePackageDir}/model/{entity}.java",
        set = "mybatis",
        fileName = "entity.tpl",
        body = "package {package};\npublic class {entity} {}\n"
    )

    @Test
    fun `新建 Java 产物加来源标记且幂等`() {
        val source = "package com.example.model;\n\nimport lombok.Data;\n\n@Data\npublic class User {}\n"
        val marked = GeneratedMarker.annotate(source, spec, "mybatis", "User.java")
        assertTrue(marked.contains("@Generated(value = \"batiskit\""))
        assertTrue(marked.contains("comments = \"set=mybatis;template=entity\""))
        assertTrue(marked.contains(GeneratedMarker.IMPORT))
        assertTrue(GeneratedMarker.isMarked(marked))
        assertEquals(marked, GeneratedMarker.annotate(marked, spec, "mybatis", "User.java"))
    }

    @Test
    fun `非 Java 文件与没有类型声明的模板不动`() {
        val xml = "<mapper namespace=\"a.B\"></mapper>"
        assertEquals(xml, GeneratedMarker.annotate(xml, spec, "mybatis", "a.xml"))
        val noType = "package com.example;\n// nothing here\n"
        assertEquals(noType, GeneratedMarker.annotate(noType, spec, "mybatis", "package-info.java"))
    }

    // ---------------------------------------------------------------- diff

    @Test
    fun `内容一致与新建文件的 diff 表现`() {
        assertTrue(TextDiff.unified("a\nb\n", "a\nb\n").contains("内容一致"))
        val created = TextDiff.unified(null, "a\nb\n")
        assertTrue(created.contains("文件不存在"))
        assertTrue(created.contains("+ a"))
    }

    @Test
    fun `新增与删除行都能看出来`() {
        val diff = TextDiff.unified("a\nb\nc\n", "a\nB2\nc\nd\n")
        assertTrue(diff.contains("- b"))
        assertTrue(diff.contains("+ B2"))
        assertTrue(diff.contains("+ d"))
    }

    // ---------------------------------------------------------------- 模板迁移

    @Test
    fun `废弃模型键能定位到行号`() {
        val body = "package x;\npublic class \${mapper} extends \$entityFqn {}\n"
        val findings = TemplateMigration.scan(body)
        assertTrue(findings.any { it.key == "mapper" && it.line == 2 })
        assertTrue(findings.any { it.key == "entityFqn" })
    }

    @Test
    fun `合法模型键不报废弃`() {
        val body = "package {package};\npublic class \${entity} implements \${cfg.basePackage} {}\n"
        assertTrue(TemplateMigration.scan(body).isEmpty())
    }

    @Test
    fun `requires 与当前模型版本不符时给提示`() {
        val old = spec.copy(requires = "1")
        assertNotNull(TemplateMigration.versionWarning(old))
        assertNull(TemplateMigration.versionWarning(spec.copy(requires = TemplateMigration.CURRENT_VERSION)))
        assertNull(TemplateMigration.versionWarning(spec))
    }

    // ---------------------------------------------------------------- 复制脱敏

    @Test
    fun `敏感参数值复制前被遮蔽且不影响普通字段`() {
        assertEquals(SensitiveData.MASK, SensitiveData.mask("password", "123"))
        assertEquals("abc", SensitiveData.mask("userName", "abc"))
        val sql = "select * from t_user where password='secret1' and user_name='bob'"
        val masked = SensitiveData.maskText(sql)
        assertFalse(masked.contains("secret1"))
        assertTrue(masked.contains("user_name='bob'"))
        val curl = "curl 'http://localhost/api?apiKey=abc123&name=bob'"
        assertFalse(SensitiveData.maskText(curl).contains("abc123"))
        assertTrue(SensitiveData.maskText(curl).contains("name=bob"))
    }

    @Test
    fun `未定义根名字与补全候选`() {
        val problems = TemplateCatalog.unknownRoots("public class \$mappper extends \$table.namingBase {}\n")
        assertTrue(problems.any { it.second == "mappper" })
        assertFalse("合法子键不该被误报", problems.any { it.second == "table" })
        assertTrue(TemplateCatalog.candidates("tab").any { it.first == "\${table}" })
    }
}
