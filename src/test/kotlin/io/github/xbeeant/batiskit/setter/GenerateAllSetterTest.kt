package io.github.xbeeant.batiskit.setter

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * 在真实 Java PSI 环境里验证「对象一键赋值」：解析光标 / 选区、收集 setter、写回源码、补 import。
 *
 * 走 Alt+Enter 的 intention 入口（与右键动作共用 [SetterResolver] + [AllSetterGenerator]），
 * 另外单独断言非对象位置解析不到目标。
 */
class GenerateAllSetterTest : BasePlatformTestCase() {

    private val intentionText = "为对象生成所有 Setter"

    private fun addUserPojo() {
        myFixture.addFileToProject(
            "com/example/Status.java",
            """
            package com.example;
            public enum Status { ENABLED, DISABLED }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/example/Address.java",
            """
            package com.example;
            public class Address {
                private String city;
                public String getCity() { return city; }
                public void setCity(String city) { this.city = city; }
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/example/User.java",
            """
            package com.example;

            import java.math.BigDecimal;
            import java.time.LocalDateTime;
            import java.util.List;

            public class User {
                private Long id;
                private String userName;
                private int age;
                private boolean deleted;
                private BigDecimal amount;
                private LocalDateTime createTime;
                private List<String> tags;
                private byte[] avatar;
                private Status status;
                private Address address;

                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
                public String getUserName() { return userName; }
                public void setUserName(String userName) { this.userName = userName; }
                public int getAge() { return age; }
                public void setAge(int age) { this.age = age; }
                public boolean isDeleted() { return deleted; }
                public void setDeleted(boolean deleted) { this.deleted = deleted; }
                public BigDecimal getAmount() { return amount; }
                public void setAmount(BigDecimal amount) { this.amount = amount; }
                public LocalDateTime getCreateTime() { return createTime; }
                public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
                public List<String> getTags() { return tags; }
                public void setTags(List<String> tags) { this.tags = tags; }
                public byte[] getAvatar() { return avatar; }
                public void setAvatar(byte[] avatar) { this.avatar = avatar; }
                public Status getStatus() { return status; }
                public void setStatus(Status status) { this.status = status; }
                public Address getAddress() { return address; }
                public void setAddress(Address address) { this.address = address; }
            }
            """.trimIndent()
        )
    }

    private fun usage(body: String): String = """
        package com.example;

        class Fixture {
            void test() {
        $body
            }
        }
        """.trimIndent()

    /** 触发 Alt+Enter 并返回生成后的文件文本 */
    private fun generate(): String {
        myFixture.launchAction(myFixture.findSingleIntention(intentionText))
        return myFixture.file.text
    }

    fun `test 全量 setter 按类型填默认值并补 import`() {
        addUserPojo()
        myFixture.configureByText("Fixture.java", usage("        User u<caret>ser = new User();"))
        val text = generate()

        assertTrue("Long -> 0L：$text", text.contains("user.setId(0L)"))
        assertTrue("String -> 空串：$text", text.contains("user.setUserName(\"\")"))
        assertTrue("int -> 0：$text", text.contains("user.setAge(0)"))
        assertTrue("boolean -> false：$text", text.contains("user.setDeleted(false)"))
        assertTrue("数组 -> 空数组：$text", text.contains("user.setAvatar(new byte[0])"))
        assertTrue("枚举 -> 第一个常量：$text", text.contains("user.setStatus("))
        assertTrue("BigDecimal：$text", text.contains("user.setAmount("))
        assertTrue("LocalDateTime：$text", text.contains("user.setCreateTime("))
        assertTrue("List：$text", text.contains("user.setTags("))
        assertTrue("未知 POJO 有 public 无参构造时 new：$text", text.contains("user.setAddress("))
        assertEquals("只生成一次", 1, Regex("user\\.setId\\(").findAll(text).count())
        assertTrue("声明语句必须保留：$text", text.contains("User user = new User();"))

        // 默认值用短名，并补上对应 import；同包的 Status / Address 不需要 import
        assertTrue("BigDecimal 应缩短：$text", text.contains("user.setAmount(BigDecimal.ZERO)"))
        assertTrue("LocalDateTime 应缩短：$text", text.contains("user.setCreateTime(LocalDateTime.now())"))
        assertTrue("ArrayList 应缩短：$text", text.contains("user.setTags(new ArrayList<>())"))
        assertTrue("补 java.math import：$text", text.contains("import java.math.BigDecimal;"))
        assertTrue("补 java.time import：$text", text.contains("import java.time.LocalDateTime;"))
        assertTrue("补 java.util import：$text", text.contains("import java.util.ArrayList;"))
        assertFalse("同包不该补 import：$text", text.contains("import com.example.Status;"))
    }

    fun `test 继承与链式 setter 不重复`() {
        myFixture.addFileToProject(
            "com/example/Base.java",
            """
            package com.example;
            public class Base {
                private Long id;
                public Long getId() { return id; }
                public void setId(Long id) { this.id = id; }
            }
            """.trimIndent()
        )
        myFixture.addFileToProject(
            "com/example/Child.java",
            """
            package com.example;
            public class Child extends Base {
                private String name;
                public String getName() { return name; }
                public Child setName(String name) { this.name = name; return this; }
            }
            """.trimIndent()
        )
        myFixture.configureByText("Fixture.java", usage("        Child c<caret>hild = new Child();"))
        val text = generate()

        assertTrue("继承来的 setter：$text", text.contains("child.setId(0L)"))
        assertTrue("链式 setter 也照常调用：$text", text.contains("child.setName(\"\")"))
        assertEquals("同签名只出现一次", 1, Regex("child\\.setId\\(").findAll(text).count())
    }

    fun `test Lombok Data 没有手写 setter 也能生成`() {
        myFixture.addFileToProject("lombok/Data.java", "package lombok;\npublic @interface Data {}\n")
        myFixture.addFileToProject(
            "com/example/UserDTO.java",
            """
            package com.example;

            import lombok.Data;

            @Data
            public class UserDTO {
                private static final long serialVersionUID = 1L;
                private final String immutable = "x";
                private String userName;
                private Integer age;
            }
            """.trimIndent()
        )
        myFixture.configureByText("Fixture.java", usage("        UserDTO d<caret>to = new UserDTO();"))
        val text = generate()

        assertTrue("字段 setter：$text", text.contains("dto.setUserName(\"\")"))
        assertTrue("包装类零值：$text", text.contains("dto.setAge(0)"))
        assertFalse("static 字段不该有 setter：$text", text.contains("serialVersionUID"))
        assertFalse("final 字段不该有 setter：$text", text.contains("dto.setImmutable("))
    }

    fun `test 裸 new 语句换成局部变量声明`() {
        addUserPojo()
        myFixture.configureByText(
            "Fixture.java",
            usage("        <caret>new User();\n        User user = null;")
        )
        val text = generate()

        assertTrue("裸语句应改成声明：$text", text.contains("User user1 = new User();"))
        assertFalse("不该留下裸语句：$text", text.contains("        new User();"))
        assertTrue("按新变量名调用：$text", text.contains("user1.setUserName(\"\")"))
    }

    fun `test 选区表达式作为接收者`() {
        addUserPojo()
        myFixture.addFileToProject(
            "com/example/Holder.java",
            """
            package com.example;
            public class Holder {
                public User getUser() { return null; }
            }
            """.trimIndent()
        )
        myFixture.configureByText(
            "Fixture.java",
            usage("        Holder holder = null;\n        <selection>holder.getUser()</selection>.toString();")
        )
        val text = generate()

        assertTrue("按选区原文复用：$text", text.contains("holder.getUser().setUserName(\"\")"))
    }

    fun `test 光标在参数上时插到方法体开头`() {
        addUserPojo()
        myFixture.configureByText(
            "FixtureParams.java",
            """
            package com.example;

            class FixtureParams {
                void test(User u<caret>ser) {
                }
            }
            """.trimIndent()
        )
        val text = generate()
        assertTrue("参数也可作为目标：$text", text.contains("user.setUserName(\"\")"))
        assertTrue("插在方法体内：$text", text.indexOf("user.setUserName") > text.indexOf("void test("))
    }

    fun `test import 同名冲突时退回全限定写法`() {
        addUserPojo()
        myFixture.addFileToProject(
            "com/other/BigDecimal.java",
            "package com.other;\npublic class BigDecimal {}\n"
        )
        myFixture.configureByText(
            "FixtureConflict.java",
            """
            package com.example;

            import com.other.BigDecimal;

            class FixtureConflict {
                void test() {
                    User u<caret>ser = new User();
                }
            }
            """.trimIndent()
        )
        val text = generate()

        assertTrue("已有同名类时不能缩短成 BigDecimal.ZERO：$text",
            text.contains("user.setAmount(java.math.BigDecimal.ZERO)"))
        assertFalse("不该覆盖用户已有的 import：$text", text.contains("import java.math.BigDecimal;"))
        assertTrue("其它类型照常补 import：$text", text.contains("import java.util.ArrayList;"))
    }

    fun `test 接口与抽象类不硬造实例`() {
        myFixture.addFileToProject("com/example/Repo.java", "package com.example;\npublic interface Repo {}\n")
        myFixture.addFileToProject(
            "com/example/AbstractThing.java",
            "package com.example;\npublic abstract class AbstractThing {}\n"
        )
        myFixture.addFileToProject(
            "com/example/Bean.java",
            """
            package com.example;

            public class Bean {
                private Repo repo;
                private AbstractThing thing;
                public void setRepo(Repo repo) { this.repo = repo; }
                public void setThing(AbstractThing thing) { this.thing = thing; }
            }
            """.trimIndent()
        )
        myFixture.configureByText("Fixture.java", usage("        Bean b<caret>ean = new Bean();"))
        val text = generate()

        assertTrue("接口没有构造器，给 null：$text", text.contains("bean.setRepo(null)"))
        assertTrue("抽象类给 null：$text", text.contains("bean.setThing(null)"))
    }

    fun `test 弃用与多参数方法不生成`() {
        myFixture.addFileToProject(
            "com/example/Legacy.java",
            """
            package com.example;

            public class Legacy {
                private String name;
                public void setName(String name) { this.name = name; }
                @Deprecated
                public void setLegacy(String value) { }
                public void setBoth(String a, int b) { }
                public static void setGlobal(String value) { }
            }
            """.trimIndent()
        )
        myFixture.configureByText("Fixture.java", usage("        Legacy l<caret>egacy = new Legacy();"))
        val text = generate()

        assertTrue("正常 setter：$text", text.contains("legacy.setName(\"\")"))
        assertFalse("@Deprecated setter 不该生成：$text", text.contains("legacy.setLegacy("))
        assertFalse("多参数方法不该生成：$text", text.contains("legacy.setBoth("))
        assertFalse("static setter 不该生成：$text", text.contains("legacy.setGlobal("))
    }

    fun `test 非对象位置解析不到目标`() {
        myFixture.configureByText("Fixture.java", usage("        int c<caret>ount = 1;"))
        val plan = SetterResolver.resolve(myFixture.editor, myFixture.file)
        assertNull("int 不是 POJO，不该生成：$plan", plan)

        myFixture.configureByText("plain.txt", "hello <caret>world")
        assertNull("非 Java 文件不生成", SetterResolver.resolve(myFixture.editor, myFixture.file))
    }
}
