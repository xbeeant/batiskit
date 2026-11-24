package io.github.xbeeant.batiskit.lombok

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * 一键转成 Lombok 的 PSI 级测试：类级 @Getter / @Setter（含显式 import）、
 * 规范形态的 getter / setter 与 serialVersionUID 删除、带逻辑的访问器保留。
 */
class ConvertToLombokTest : BasePlatformTestCase() {

    /** lombok 不在测试工程依赖里，塞注解桩让 @Getter / @Setter 可解析 */
    private fun addLombokStubs() {
        myFixture.addFileToProject(
            "lombok/Getter.java",
            "package lombok;\n\npublic @interface Getter {\n}\n"
        )
        myFixture.addFileToProject(
            "lombok/Setter.java",
            "package lombok;\n\npublic @interface Setter {\n}\n"
        )
    }

    fun `test 转换加注解删访问器与 serial 并保留带逻辑的方法`() {
        addLombokStubs()
        myFixture.configureByText(
            "User.java",
            """
            package com.example;

            public class User implements java.io.Serializable {
                private static final long serialVersionUID = 1L;

                <caret>
                private Long id;
                private String userName;
                private boolean deleted;

                public Long getId() {
                    return id;
                }

                public void setId(Long id) {
                    this.id = id;
                }

                public String getUserName() {
                    return this.userName;
                }

                public void setUserName(String userName) {
                    this.userName = userName;
                }

                public boolean isDeleted() {
                    return deleted;
                }

                public void setDeleted(boolean deleted) {
                    this.deleted = deleted;
                }

                public String getDisplayName() {
                    return "[" + userName + "]";
                }

                public void rename(String name) {
                    userName = name.toUpperCase();
                }
            }
            """.trimIndent()
        )
        myFixture.launchAction(myFixture.findSingleIntention("转成 Lombok"))
        val text = myFixture.file.text

        // 注解与 import
        assertTrue("应有类级 @Getter：$text", text.contains("@Getter"))
        assertTrue("应有类级 @Setter：$text", text.contains("@Setter"))
        assertTrue("应补 lombok import：$text", text.contains("import lombok.Getter;") && text.contains("import lombok.Setter;"))

        // 规范访问器删除
        assertFalse("getId 应被删除：$text", text.contains("public Long getId()"))
        assertFalse("setId 应被删除：$text", text.contains("public void setId("))
        assertFalse("getUserName（return this.userName）应被删除：$text", text.contains("getUserName()"))
        assertFalse("isDeleted 应被删除：$text", text.contains("isDeleted()"))

        // serialVersionUID 删除
        assertFalse("serialVersionUID 应被删除：$text", text.contains("serialVersionUID"))

        // 带逻辑的方法保留
        assertTrue("带逻辑的 getDisplayName 应保留：$text", text.contains("getDisplayName()"))
        assertTrue("无关方法 rename 应保留：$text", text.contains("rename("))
    }

    fun `test 已有注解时不重复添加`() {
        addLombokStubs()
        myFixture.configureByText(
            "User.java",
            """
            package com.example;

            import lombok.Getter;
            import lombok.Setter;

            @Getter
            @Setter
            public class User {
                <caret>
                private Long id;

                public Long getId() {
                    return id;
                }

                public void setId(Long id) {
                    this.id = id;
                }
            }
            """.trimIndent()
        )
        myFixture.launchAction(myFixture.findSingleIntention("转成 Lombok"))
        val text = myFixture.file.text

        assertEquals("@Getter 只该有一个", 1, Regex("@Getter").findAll(text).count())
        assertEquals("@Setter 只该有一个", 1, Regex("@Setter").findAll(text).count())
        assertFalse("访问器应被删除：$text", text.contains("public Long getId()"))
    }

    fun `test 工程没有 lombok 依赖也用短名并显式 import`() {
        // 故意不塞注解桩：以前靠 shortenClassReferences，解析不到时就留下 @lombok.Getter
        myFixture.configureByText(
            "User.java",
            """
            package com.example;

            public class User {
                <caret>
                private Long id;

                public Long getId() {
                    return id;
                }

                public void setId(Long id) {
                    this.id = id;
                }
            }
            """.trimIndent()
        )
        myFixture.launchAction(myFixture.findSingleIntention("转成 Lombok"))
        val text = myFixture.file.text
        val lines = text.lines().map { it.trim() }

        assertTrue("应补 import lombok.Getter：$text", "import lombok.Getter;" in lines)
        assertTrue("应补 import lombok.Setter：$text", "import lombok.Setter;" in lines)
        assertTrue("注解应是短名 @Getter：$text", "@Getter" in lines)
        assertTrue("注解应是短名 @Setter：$text", "@Setter" in lines)
        assertFalse("不应留下全限定写法：$text", text.contains("@lombok."))
        assertFalse("访问器应被删除：$text", text.contains("public Long getId()"))
    }

    fun `test 已有的全限定 lombok 注解规范化成短名加 import`() {
        myFixture.configureByText(
            "User.java",
            """
            package com.example;

            @lombok.Getter
            @lombok.Setter
            public class User {
                <caret>
                private Long id;

                public Long getId() {
                    return id;
                }
            }
            """.trimIndent()
        )
        myFixture.launchAction(myFixture.findSingleIntention("转成 Lombok"))
        val text = myFixture.file.text
        val lines = text.lines().map { it.trim() }

        assertTrue("应补 import lombok.Getter：$text", "import lombok.Getter;" in lines)
        assertTrue("应补 import lombok.Setter：$text", "import lombok.Setter;" in lines)
        assertTrue("@Getter 应改成短名：$text", "@Getter" in lines)
        assertTrue("@Setter 应改成短名：$text", "@Setter" in lines)
        assertFalse("不应留下全限定写法：$text", text.contains("@lombok."))
        assertEquals("@Getter 只该有一个", 1, lines.count { it == "@Getter" })
        assertEquals("@Setter 只该有一个", 1, lines.count { it == "@Setter" })
    }

    fun `test 已有同名类 import 时退回全限定名`() {
        myFixture.addFileToProject(
            "com/example/Getter.java",
            "package com.example;\n\npublic @interface Getter {\n}\n"
        )
        myFixture.configureByText(
            "User.java",
            """
            package com.example;

            import com.example.Getter;

            public class User {
                <caret>
                private Long id;

                public Long getId() {
                    return id;
                }
            }
            """.trimIndent()
        )
        myFixture.launchAction(myFixture.findSingleIntention("转成 Lombok"))
        val text = myFixture.file.text
        val lines = text.lines().map { it.trim() }

        assertTrue("短名有冲突时应保留 @lombok.Getter：$text", "@lombok.Getter" in lines)
        assertFalse("不应 import lombok.Getter 造成冲突：$text", "import lombok.Getter;" in lines)
        assertTrue("无冲突的 Setter 仍应补 import：$text", "import lombok.Setter;" in lines)
        assertTrue("无冲突的 Setter 仍用短名：$text", "@Setter" in lines)
    }

    fun `test 已有 lombok 通配 import 时不重复添加`() {
        myFixture.configureByText(
            "User.java",
            """
            package com.example;

            import lombok.*;

            public class User {
                <caret>
                private Long id;

                public Long getId() {
                    return id;
                }
            }
            """.trimIndent()
        )
        myFixture.launchAction(myFixture.findSingleIntention("转成 Lombok"))
        val text = myFixture.file.text
        val lines = text.lines().map { it.trim() }

        assertEquals("不该再补显式 import：$text", 1, lines.count { it.startsWith("import lombok.") })
        assertTrue("@Getter 短名可直接使用：$text", "@Getter" in lines)
        assertTrue("@Setter 短名可直接使用：$text", "@Setter" in lines)
    }
}
