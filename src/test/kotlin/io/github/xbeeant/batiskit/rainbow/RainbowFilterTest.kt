package io.github.xbeeant.batiskit.rainbow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 语言 / 扩展名黑白名单的纯逻辑回归 */
class RainbowFilterTest {

    @Test
    fun `parse 拆分归一化并去重`() {
        assertEquals(
            listOf("java", "kotlin", "pom.xml", "min.js"),
            RainbowFilter.parse("JAVA, kotlin ; pom.xml  *.min.js java"),
        )
        assertEquals(listOf("java"), RainbowFilter.parse(".java"))
        assertEquals(emptyList<String>(), RainbowFilter.parse(null))
        assertEquals(emptyList<String>(), RainbowFilter.parse("  , ; \t\n "))
    }

    @Test
    fun `条目可匹配语言 ID 文件名与扩展名`() {
        assertTrue(RainbowFilter.matches("java", "JAVA", "Foo.kt"))
        assertTrue(RainbowFilter.matches("java", "JAVA", "Foo.java"))
        assertTrue(RainbowFilter.matches("pom.xml", "XML", "pom.xml"))
        assertTrue(RainbowFilter.matches("min.js", "JavaScript", "app.min.js"))
        assertFalse(RainbowFilter.matches("java", "XML", "Foo.xml"))
        assertFalse(RainbowFilter.matches("", "JAVA", "Foo.java"))
    }

    @Test
    fun `白名单留空不限制且大小写不敏感`() {
        assertTrue(RainbowFilter.accepts("JAVA", "Foo.java", "", ""))
        assertTrue(RainbowFilter.accepts("java", "Foo.java", "JAVA,kotlin", ""))
        assertFalse(RainbowFilter.accepts("XML", "pom.xml", "JAVA,kotlin", ""))
        assertTrue(RainbowFilter.accepts("XML", "pom.xml", "*,xml", ""))
    }

    @Test
    fun `黑名单优先于白名单`() {
        assertFalse(RainbowFilter.accepts("JAVA", "Foo.java", "JAVA", "*.java"))
        assertFalse(RainbowFilter.accepts("JAVA", "Foo.java", "", "Foo.java"))
        assertFalse(RainbowFilter.accepts("JAVA", "Foo.java", "JAVA", "JAVA"))
        assertTrue(RainbowFilter.accepts("XML", "pom.xml", "", "*.java"))
    }

    @Test
    fun `扩展名黑名单大小写不敏感`() {
        assertFalse(RainbowFilter.accepts("JavaScript", "APP.MIN.JS", "", "*.min.js"))
        assertTrue(RainbowFilter.accepts("JavaScript", "app.js", "", "*.min.js"))
    }

    @Test
    fun `null 与空白规格都视为不限制`() {
        assertTrue(RainbowFilter.accepts("JAVA", "Foo.java", null, null))
        assertTrue(RainbowFilter.accepts("JAVA", "Foo.java", "  , ; ", " , "))
    }
}
