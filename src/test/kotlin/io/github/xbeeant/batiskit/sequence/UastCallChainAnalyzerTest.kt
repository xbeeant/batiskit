package io.github.xbeeant.batiskit.sequence

import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElement
import java.util.concurrent.atomic.AtomicInteger

/**
 * UAST 调用链分析器的回归测试。
 *
 * 用 Java fixture 而不是 Kotlin fixture：Java 的 UAST 转换器随 Java 插件一起加载，
 * 不必把 Kotlin 插件拖进测试沙箱；UAST 层是语言无关的，Kotlin 侧只是另一个转换器。
 * 这样深度 / 递归 / 过滤 / 截断等核心行为都有真实 PSI 覆盖。
 *
 * 注意：LightJavaCodeInsightFixtureTestCase 是 JUnit3 体系，只发现 testXxx() 方法，
 * 所以下面统一用 `test ` 前缀而不是 JUnit4 的 @Test 注解，否则会被 TestSuite 报 "No tests found"。
 */
class UastCallChainAnalyzerTest : LightJavaCodeInsightFixtureTestCase() {

    private val fileSeq = AtomicInteger()

    private fun analyze(
        javaText: String,
        methodName: String = "entry",
        options: DiagramOptions = DiagramOptions(),
    ): SequenceDiagram {
        // 对比两组选项的用例会连续调用两次：用唯一包名既避开文件重名 IOException，
        // 也避免源码里的辅助类（如 Helper / Service）在同一包里被定义两次导致类型解析歧义
        val pkg = "com.example.case${fileSeq.incrementAndGet()}"
        val source = javaText.replace("package com.example;", "package $pkg;")
        val file = myFixture.addFileToProject("${pkg.replace('.', '/')}/UastDemo.java", source) as PsiJavaFile
        val method: PsiMethod = file.classes[0].findMethodsByName(methodName, false).first()
        val uMethod = method.toUElement() as? UMethod
            ?: error("Java UAST 转换器不可用，无法把 PsiMethod 转成 UMethod")
        return UastCallChainAnalyzer(project, options).analyze(uMethod)
    }

    fun `test 简单调用链生成参与者与消息`() {
        val diagram = analyze(
            """
            package com.example;
            class UastDemo {
                void entry() { helper(); }
                void helper() { }
            }
            """.trimIndent()
        )
        assertEquals(1, diagram.participants.size)
        assertTrue(diagram.messages.any { it.kind == MessageKind.CALL && it.label == "helper()" })
        assertTrue(diagram.messages.all { it.endIndex >= 0 })
    }

    fun `test 递归调用标回边且不再下钻`() {
        val diagram = analyze(
            """
            package com.example;
            class UastDemo {
                void entry() { recursive(1); }
                void recursive(int n) { if (n > 0) recursive(n - 1); }
            }
            """.trimIndent()
        )
        val backEdge = diagram.messages.firstOrNull { it.recursive }
        assertNotNull("应有一条递归回边", backEdge)
        assertTrue(backEdge!!.label.contains("recursive"))
    }

    fun `test 深度上限阻止无限下钻`() {
        val source = """
            package com.example;
            class UastDemo {
                void entry() { a(); }
                void a() { b(); }
                void b() { c(); }
                void c() { }
            }
            """.trimIndent()
        val shallow = analyze(source, options = DiagramOptions(maxDepth = 1))
        val deep = analyze(source, options = DiagramOptions(maxDepth = 5))
        assertTrue(deep.messages.size > shallow.messages.size)
        assertTrue(shallow.messages.none { it.depth > 1 })
    }

    fun `test 噪声调用按开关过滤`() {
        val source = """
            package com.example;
            class UastDemo {
                void entry() { getName(); work(); }
                String getName() { return "x"; }
                void work() { }
            }
            """.trimIndent()
        val withSkip = analyze(source, options = DiagramOptions(skipTrivial = true))
        val withoutSkip = analyze(source, options = DiagramOptions(skipTrivial = false))
        assertTrue(withSkip.messages.none { it.label.startsWith("getName") })
        assertTrue(withoutSkip.messages.any { it.label.startsWith("getName") })
    }

    fun `test 基础类与工具类调用按开关跳过`() {
        // 与 Java 版一致：造一个真实包名的工具类桩，保证 UAST 能解析到它
        myFixture.addFileToProject(
            "org/springframework/util/CollectionUtils.java",
            """
            package org.springframework.util;

            import java.util.Collection;

            public class CollectionUtils {
                public static boolean isEmpty(Collection<?> c) {
                    return c == null || c.isEmpty();
                }
            }
            """.trimIndent(),
        )
        val source = """
            package com.example;
            class UastDemo {
                void entry() {
                    java.util.List<String> list = new java.util.ArrayList<>();
                    org.springframework.util.CollectionUtils.isEmpty(list);
                    work();
                }
                void work() { }
            }
            """.trimIndent()
        val skipped = analyze(source)
        assertTrue(
            "基础类 / 工具类不应占生命线：" + skipped.participants.map { it.qualified },
            skipped.participants.none {
                it.qualified.startsWith("java.") || it.qualified.startsWith("org.springframework.")
            },
        )
        assertTrue("业务调用仍在", skipped.messages.any { it.label == "work()" })
        assertTrue(
            "工具类调用连箭头都不应出现：" + skipped.messages.map { it.label },
            skipped.messages.none { it.label.startsWith("isEmpty") },
        )

        val kept = analyze(source, options = DiagramOptions(skipBasicClasses = false))
        assertTrue(
            "关闭开关后工具类重新进图：" + kept.participants.map { it.qualified },
            kept.participants.any { it.qualified == "org.springframework.util.CollectionUtils" },
        )
    }

    fun `test 消息上限截断并加备注`() {
        val calls = (1..12).joinToString("\n") { "        void entry$it() { }" }
        val body = (1..12).joinToString(" ") { "entry$it();" }
        val diagram = analyze(
            """
            package com.example;
            class UastDemo {
                void entry() { $body }
                $calls
            }
            """.trimIndent(),
            options = DiagramOptions(maxMessages = 3, showReturns = false),
        )
        assertTrue(diagram.truncated)
        assertTrue(diagram.messages.any { it.kind == MessageKind.NOTE && it.label.contains("上限") })
        assertTrue(diagram.messages.size <= 4)
    }

    fun `test 返回箭头按开关出现`() {
        // 必须是跨类调用：与 Java 版一致，自调用不画返回箭头（caller == callee.qualified）
        val source = """
            package com.example;
            class UastDemo {
                private final Helper helper = new Helper();
                void entry() { helper.value(); }
            }
            class Helper {
                int value() { return 1; }
            }
            """.trimIndent()
        val without = analyze(source, options = DiagramOptions(showReturns = false))
        val withReturns = analyze(source, options = DiagramOptions(showReturns = true))
        assertTrue("value() 调用本身应被解析出来", withReturns.messages.any { it.kind == MessageKind.CALL })
        assertTrue(without.messages.none { it.kind == MessageKind.RETURN })
        assertTrue(withReturns.messages.any { it.kind == MessageKind.RETURN })
    }

    fun `test 空方法体给出说明性备注`() {
        val diagram = analyze(
            """
            package com.example;
            class UastDemo {
                void entry() { }
            }
            """.trimIndent()
        )
        assertTrue(diagram.messages.any { it.kind == MessageKind.NOTE })
        assertEquals(1, diagram.participants.size)
    }

    fun `test 跨对象调用与参数能继续下钻`() {
        // 回归：带接收者的调用在 Java UAST 里是 UQualifiedReferenceExpression，
        // 调用本体藏在 selector 里；只认 toUElement() is UCallExpression 会整类漏掉
        val diagram = analyze(
            """
            package com.example;
            class UastDemo {
                private final Service service = new Service();
                void entry() { service.doWork(42); }
            }
            class Service {
                void doWork(int n) { inner(); }
                void inner() { }
            }
            """.trimIndent()
        )
        val call = diagram.messages.firstOrNull { it.kind == MessageKind.CALL && it.label.startsWith("doWork") }
        assertNotNull("service.doWork(42) 应被解析成跨对象调用", call)
        assertTrue("实参应出现在标签里", call!!.label.contains("42"))
        assertTrue("跨对象调用应继续下钻到目标类内部", diagram.messages.any { it.label.startsWith("inner") })
        assertTrue("应产生两个参与者", diagram.participants.size >= 2)
    }

    fun `test 导航目标是可跳转的 PsiElement`() {
        val diagram = analyze(
            """
            package com.example;
            class UastDemo {
                void entry() { helper(); }
                void helper() { }
            }
            """.trimIndent()
        )
        val call = diagram.messages.first { it.kind == MessageKind.CALL }
        assertTrue(call.psiMethod is com.intellij.psi.PsiElement)
        assertTrue(diagram.participants.first().psiClass is com.intellij.psi.PsiElement)
    }

    fun `test if else 与循环的条件保留在消息上`() {
        val diagram = analyze(
            """
            package com.example;
            class UastDemo {
                void entry(boolean flag) {
                    if (flag) { thenBranch(); } else { elseBranch(); }
                    for (int i = 0; i < 2; i++) { loopBody(); }
                }
                void thenBranch() { }
                void elseBranch() { }
                void loopBody() { }
            }
            """.trimIndent()
        )
        val thenCall = diagram.messages.first { it.label == "thenBranch()" }
        val elseCall = diagram.messages.first { it.label == "elseBranch()" }
        val loopCall = diagram.messages.first { it.label == "loopBody()" }
        assertEquals(BranchKind.IF, thenCall.branchKind)
        assertTrue("条件原文应保留：" + thenCall.guard, thenCall.guard.contains("flag"))
        assertEquals(BranchKind.ELSE, elseCall.branchKind)
        assertEquals(BranchKind.LOOP, loopCall.branchKind)
        val kinds = diagram.fragments().map { it.kind }
        assertTrue("if / else / loop 片段都要有：" + kinds,
            kinds.contains(BranchKind.IF) && kinds.contains(BranchKind.ELSE) && kinds.contains(BranchKind.LOOP))
    }

    fun `test 接口多个实现分别展开且各自方法体都保留`() {
        val diagram = analyze(
            """
            package com.example;
            class UastDemo {
                private final FuzzySearch service = new ImplA();
                void entry() { service.fuzzySearchByPagerExt(null); }
            }
            interface FuzzySearch {
                Object fuzzySearchByPagerExt(Object q);
            }
            class ImplA implements FuzzySearch {
                @Override public Object fuzzySearchByPagerExt(Object q) { fromA(); return null; }
                void fromA() { }
            }
            class ImplB implements FuzzySearch {
                @Override public Object fuzzySearchByPagerExt(Object q) { fromB(); return null; }
                void fromB() { }
            }
            """.trimIndent()
        )
        val displays = diagram.participants.map { it.display }
        assertTrue("两个实现都要进图：$displays",
            displays.contains("ImplA") && displays.contains("ImplB"))
        assertTrue("ImplA 方法体的调用要展开：" + diagram.messages.map { it.label },
            diagram.messages.any { it.label == "fromA()" })
        assertTrue("ImplB 方法体的调用要展开：" + diagram.messages.map { it.label },
            diagram.messages.any { it.label == "fromB()" })
        val implMsgs = diagram.messages.filter { it.branchKind == BranchKind.IMPL }
        assertTrue("多实现消息带 IMPL 分支：" + implMsgs.map { it.guard }, implMsgs.size >= 2)
        assertTrue("每个实现一条互斥分支：" + implMsgs.map { it.guard },
            implMsgs.map { it.guard }.toSet().size >= 2)
    }
}
