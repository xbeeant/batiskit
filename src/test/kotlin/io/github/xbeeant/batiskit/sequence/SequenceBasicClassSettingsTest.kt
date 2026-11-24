package io.github.xbeeant.batiskit.sequence

import io.github.xbeeant.batiskit.settings.SettingsBundle
import io.github.xbeeant.batiskit.settings.SettingsSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时序图「基础类 / 工具类」名单可配置后的纯逻辑回归：出厂默认值与旧硬编码行为一致、
 * 设置文本能替换名单、例外名单优先、设置状态与导入导出往返。零 IDE 夹具。
 */
class SequenceBasicClassSettingsTest {

    @Test
    fun `出厂默认规则与旧硬编码行为一致`() {
        val rules = BasicClassRules.DEFAULT
        assertEquals(BasicClassRules.DEFAULT_PACKAGE_PREFIXES, rules.packagePrefixes)
        assertEquals(BasicClassRules.DEFAULT_SIMPLE_NAMES, rules.simpleNames)
        assertTrue(rules.keepNames.isEmpty())

        listOf(
            "java.lang.String", "java.util.HashMap", "kotlin.collections.CollectionsKt",
            "org.springframework.util.CollectionUtils", "org.apache.commons.lang3.StringUtils",
            "com.google.common.collect.Lists", "cn.hutool.core.collection.CollectionUtil",
        ).forEach {
            assertTrue("$it 应命中默认包前缀", rules.isBasic(it))
            assertTrue("$it 不带规则时也应命中", SequenceRules.isBasicClass(it))
        }
        listOf(
            "com.example.util.StringUtils", "org.junit.Assert", "org.slf4j.Logger",
            "com.baomidou.mybatisplus.core.mapper.BaseMapper", "anonymous", "anonymous Runnable",
        ).forEach {
            assertFalse("$it 不应被误杀", rules.isBasic(it))
            assertFalse("$it 不带规则时也不应被误杀", SequenceRules.isBasicClass(it))
        }
        assertTrue(rules.isBasic(null, "List"))
        assertTrue(rules.isBasic("HashMap"))
        assertFalse(rules.isBasic(null, null))
    }

    @Test
    fun `设置文本可替换包前缀与简单类名`() {
        val rules = BasicClassRules.parse("com.acme.lib.\ncom.third.", "MyUtil, InternalHelper")
        assertTrue(rules.isBasic("com.acme.lib.Helper"))
        assertTrue(rules.isBasic("com.third.Api"))
        assertTrue(rules.isBasic("MyUtil"))
        assertTrue(rules.isBasic(null, "InternalHelper"))
        // 名单是替换而不是追加：旧默认值不再生效
        assertFalse(rules.isBasic("java.util.List"))
        assertFalse(rules.isBasic("HashMap"))
        assertFalse(SequenceRules.isBasicClass("java.lang.String", rules = rules))
        assertTrue(SequenceRules.isBasicClass("com.acme.lib.Helper", rules = rules))
    }

    @Test
    fun `例外名单优先于包前缀与简单名`() {
        val rules = BasicClassRules.parse(
            "java.\ncom.acme.lib.",
            "ArrayList, MyUtil, InternalUtil",
            "java.util.ArrayList\ncom.acme.lib.KeepMe, MyUtil"
        )
        assertFalse("全限定名例外", rules.isBasic("java.util.ArrayList"))
        assertFalse("包前缀下的例外", rules.isBasic("com.acme.lib.KeepMe"))
        assertFalse("简单名例外（该类本在简单名名单里）", rules.isBasic(null, "MyUtil"))
        assertFalse("全限定名例外也保住解析不出包名的同类", rules.isBasic("ArrayList"))
        assertTrue("同包其它类仍跳过", rules.isBasic("java.util.HashMap"))
        assertTrue("其它简单名仍跳过", rules.isBasic("InternalUtil"))
        assertFalse("没列进简单名名单的类不跳过", rules.isBasic("HashMap"))
    }

    @Test
    fun `名单文本支持换行逗号分号注释空行与去重`() {
        val entries = BasicClassRules.entries(
            """
            # JDK
            java. ;  kotlin., java.

            org.springframework.util.  # Spring 工具包
            """.trimIndent()
        )
        assertEquals(listOf("java.", "kotlin.", "org.springframework.util."), entries)
        val rules = BasicClassRules.parse("java.\njava.\n# 注释行", "", "")
        assertEquals(listOf("java."), rules.packagePrefixes)
        assertTrue(rules.simpleNames.isEmpty())
    }

    @Test
    fun `名单留空即不过滤任何类`() {
        val rules = BasicClassRules.parse("", "", "")
        listOf("java.lang.String", "java.util.HashMap", "HashMap", null).forEach {
            assertFalse("$it 不应被跳过", rules.isBasic(it, "HashMap"))
        }
        assertFalse(rules.isBasic(null, "HashMap"))
    }

    @Test
    fun `设置服务默认值 loadState 就地拷贝并反映到规则`() {
        val service = SequenceDiagramSettings()
        val original = service.state
        assertEquals(BasicClassRules.DEFAULT_PREFIXES_TEXT, service.state.basicPackagePrefixes)
        assertEquals(BasicClassRules.DEFAULT_SIMPLE_NAMES_TEXT, service.state.basicSimpleNames)
        assertEquals("", service.state.keepClassNames)
        assertTrue(service.rules().isBasic("java.util.HashMap"))

        service.loadState(SequenceDiagramSettingsState().apply {
            basicPackagePrefixes = "com.acme.lib."
            basicSimpleNames = ""
            keepClassNames = "com.acme.lib.KeepMe"
        })
        assertSame("loadState 不能替换 state 实例", original, service.getState())
        val rules = service.rules()
        assertTrue(rules.isBasic("com.acme.lib.Helper"))
        assertFalse(rules.isBasic("java.util.HashMap"))
        assertFalse(rules.isBasic("com.acme.lib.KeepMe"))
    }

    @Test
    fun `名单随设置分组导入导出还原`() {
        val service = SequenceDiagramSettings()
        val sections = listOf(
            SettingsSection(
                "sequence-diagram", "时序图",
                SequenceDiagramSettingsState::class.java, service
            )
        )
        service.state.basicPackagePrefixes = "com.acme.lib.\n# 内部工具包\norg.acme.tools."
        service.state.basicSimpleNames = "MyUtil\nInternalUtil"
        service.state.keepClassNames = "com.acme.lib.KeepMe"
        val xml = SettingsBundle.encode(sections)

        service.state.basicPackagePrefixes = "java."
        service.state.basicSimpleNames = ""
        service.state.keepClassNames = ""
        val report = SettingsBundle.decode(xml, sections)
        assertTrue(report.summary(), report.isSuccess)
        assertEquals(1, report.applied.size)
        assertEquals("com.acme.lib.\n# 内部工具包\norg.acme.tools.", service.state.basicPackagePrefixes)
        assertEquals("MyUtil\nInternalUtil", service.state.basicSimpleNames)
        assertEquals("com.acme.lib.KeepMe", service.state.keepClassNames)
        assertTrue(service.rules().isBasic("com.acme.lib.Helper"))
        assertTrue(service.rules().isBasic("org.acme.tools.Widget"))
        assertTrue(service.rules().isBasic("InternalUtil"))
        assertFalse(service.rules().isBasic("com.acme.lib.KeepMe"))
    }

    @Test
    fun `基础类名单不写进对话框选项的工程记忆串`() {
        val custom = BasicClassRules.parse("com.acme.lib.", "MyUtil")
        assertEquals(DiagramOptions().toName(), DiagramOptions(basicClassRules = custom).toName())
        assertEquals(DiagramOptions(), DiagramOptions.fromName(DiagramOptions(basicClassRules = custom).toName()))
        assertTrue(DiagramOptions(basicClassRules = custom).basicClassRules.isBasic("com.acme.lib.Helper"))
    }
}
