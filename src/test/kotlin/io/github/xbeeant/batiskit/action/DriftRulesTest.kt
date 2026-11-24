package io.github.xbeeant.batiskit.action

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「比对已生成代码」判定口径的回归。
 *
 * 这些规则以前埋在 action 里并且直接拿 `findClass == null` 当「没生成过」，
 * 结果文件明明在磁盘上，只要索引没就绪 / 模块推断不一致就整批误报。
 * 存在性判定与字段比对判定拆开后，在这里锁住关键分支。
 */
class DriftRulesTest {

    @Test
    fun `文件存在但 PSI 读不到时不得判定为未生成`() {
        assertEquals(
            EntityState.MISSING,
            DriftRules.entityState(exists = false, classResolved = false)
        )
        assertEquals(
            EntityState.MISSING,
            DriftRules.entityState(exists = false, classResolved = true)
        )
        // 核心回归：文件在、索引没有，只能跳过字段比对，绝不能报「产物未生成」
        assertEquals(
            EntityState.UNREADABLE,
            DriftRules.entityState(exists = true, classResolved = false)
        )
        assertEquals(
            EntityState.READY,
            DriftRules.entityState(exists = true, classResolved = true)
        )
    }

    @Test
    fun `实体产物只认 java 根下文件名恰好等于实体名的文件`() {
        assertTrue(DriftRules.isEntity("java", "com/demo/model/User.java", "User"))
        assertTrue(DriftRules.isEntity("java", "com/demo/model/UserDO.java", "UserDO"))
        // 旧的 endsWith 写法会把这些也当成实体跳过，导致实体模板定位错或漏报
        assertFalse(DriftRules.isEntity("java", "com/demo/model/AdminUser.java", "User"))
        assertFalse(DriftRules.isEntity("java", "com/demo/dto/UserDTO.java", "User"))
        assertFalse(DriftRules.isEntity("java", "com/demo/mapper/UserMapper.java", "User"))
        assertFalse(DriftRules.isEntity("resources", "mybatis/User.java", "User"))
    }

    @Test
    fun `产物路径推导全限定名只适用于 java 根`() {
        assertEquals("com.demo.model.User", DriftRules.fqnOf("java", "com/demo/model/User.java"))
        assertEquals("User", DriftRules.fqnOf("java", "User.java"))
        assertNull(DriftRules.fqnOf("resources", "mybatis/demo/UserMapper.xml"))
        assertNull(DriftRules.fqnOf("java", ".java"))
    }

    @Test
    fun `被排除模板清单按方案加模板 id 解析`() {
        assertEquals(
            setOf("mybatis:dto", "mybatis-plus:mpEntity"),
            DriftRules.disabledKeys(" mybatis:dto , ,mybatis-plus:mpEntity ")
        )
        assertTrue(DriftRules.disabledKeys("").isEmpty())
    }

    @Test
    fun `报告摘要只统计差异项且干净的报告不含差异`() {
        val report = DriftReport()
        assertTrue(report.isClean)
        assertEquals("未发现漂移", report.summary())

        report.items += DriftItem("t_user", DriftKind.MISSING_FIELD, "age")
        report.items += DriftItem("t_user", DriftKind.MISSING_ARTIFACT, "实体类")
        report.items += DriftItem("t_order", DriftKind.MISSING_ARTIFACT, "Mapper XML")
        report.notes += "索引未就绪，跳过字段比对"

        assertFalse(report.isClean)
        assertEquals("缺字段 1  产物未生成 2", report.summary())
        assertEquals("【t_user】缺字段：age", report.items.first().message)
    }
}
