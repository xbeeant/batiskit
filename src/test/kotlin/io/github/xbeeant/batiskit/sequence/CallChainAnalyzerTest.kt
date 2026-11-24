package io.github.xbeeant.batiskit.sequence

import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import java.io.File

/** 调用链分析器：走 PSI，用 light fixture 断言参与者与消息的语义 */
class CallChainAnalyzerTest : LightJavaCodeInsightFixtureTestCase() {

    private fun analyzeMethod(
        javaText: String,
        methodName: String = "entry",
        options: DiagramOptions = DiagramOptions(),
        path: String = "com/example/Demo.java",
    ): SequenceDiagram {
        val file = myFixture.addFileToProject(path, javaText) as PsiJavaFile
        val method = file.classes[0].findMethodsByName(methodName, false).first() as PsiMethod
        return CallChainAnalyzer(project, options).analyze(method)
    }

    fun `test 简单调用链与激活区间`() {
        val diagram = analyzeMethod(
            """
            package com.example;

            class Demo {
                private final Service service = new Service();

                void entry() {
                    service.doWork();
                }
            }

            class Service {
                void doWork() {
                    inner();
                }
                void inner() {
                }
            }
            """.trimIndent(),
        )
        assertEquals(listOf("Demo", "Service"), diagram.participants.map { it.display })
        // doWork()（Demo → Service）+ inner()（Service 自调用）共 2 条
        assertEquals(2, diagram.messages.size)
        val call = diagram.messages[0]
        assertEquals("doWork()", call.label)
        // doWork 里还有一次 self 调用，激活区间延伸到那条消息
        assertEquals(1, call.endIndex)
    }

    fun `test self 调用与递归回边`() {
        val diagram = analyzeMethod(
            """
            package com.example;

            class Demo {
                void entry() {
                    step(3);
                }
                void step(int n) {
                    if (n > 0) {
                        step(n - 1);
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, diagram.participants.size)
        assertEquals(2, diagram.messages.size)
        val back = diagram.messages[1]
        assertTrue(back.recursive)
        assertEquals("step(n - 1)", back.label)
    }

    fun `test 智能接口自动展开唯一实现`() {
        val diagram = analyzeMethod(
            """
            package com.example;

            class Demo {
                private Fruit fruit = new Apple();

                void entry() {
                    fruit.eat();
                }
            }

            interface Fruit {
                int eat();
            }

            class Apple implements Fruit {
                @Override
                public int eat() {
                    return 5;
                }
            }
            """.trimIndent(),
        )
        // fruit.eat() 解析到接口方法，唯一实现 Apple 自动展开
        assertEquals("Apple", diagram.participants[1].display)
        assertTrue(diagram.participants[1].psiClass != null)
        assertEquals(1, diagram.messages.size)
        assertTrue(diagram.messages[0].dashed)
        // Apple.eat 没有内部调用，激活区间就停在自身
        assertEquals(0, diagram.messages[0].endIndex)
    }

    fun `test 跳过 getter 与日志调用`() {
        val diagram = analyzeMethod(
            """
            package com.example;

            class Demo {
                private final Service service = new Service();
                private final org.slf4j.Logger log = null;

                void entry() {
                    service.getName();
                    service.setName("x");
                    log.info("hello {}", "world");
                    service.doWork();
                }
            }

            class Service {
                String getName() {
                    return "";
                }
                void setName(String name) {
                }
                void doWork() {
                }
            }
            """.trimIndent(),
        )
        // 只剩 doWork() 一条
        assertEquals(listOf("doWork()"), diagram.messages.map { it.label })
    }

    fun `test 跳过基础类与工具类调用`() {
        // 造一个与真实包同名的工具类桩，验证「包名前缀」规则（fixture 里没有 spring 依赖）
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

            import java.util.ArrayList;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;

            class Demo {
                private final Service service = new Service();

                void entry() {
                    String name = "abc".substring(1);
                    List<String> list = new ArrayList<>();
                    list.add(name);
                    Map<String, String> map = new HashMap<>();
                    map.put("k", "v");
                    org.springframework.util.CollectionUtils.isEmpty(list);
                    service.doWork();
                }
            }

            class Service {
                void doWork() {
                }
            }
            """.trimIndent()

        val skipped = analyzeMethod(source)
        // String / List / HashMap / CollectionUtils 连箭头与生命线都不生成，业务调用照旧
        assertEquals(listOf("Demo", "Service"), skipped.participants.map { it.display })
        assertEquals(listOf("doWork()"), skipped.messages.map { it.label })

        // 关掉开关后恢复旧行为：工具类重新成为参与者
        val kept = analyzeMethod(
            source,
            options = DiagramOptions(skipBasicClasses = false),
            path = "com/example/DemoKept.java",
        )
        assertTrue(
            "关闭开关后工具类重新进图：" + kept.participants.map { it.qualified },
            kept.participants.any { it.qualified == "org.springframework.util.CollectionUtils" },
        )
        assertTrue(
            "关闭开关后基础容器重新画箭头：" + kept.messages.map { it.label },
            kept.messages.any { it.label.startsWith("new HashMap") },
        )
    }

    fun `test 库调用只画箭头不下钻`() {
        // 生产环境的库代码来自 jar（binary，无 PSI 方法体）。测试环境跑在 JBR 上没有 javac，
        // 直接把类路径上现成的 junit jar 挂成 library，语义与生产完全一致：
        // 调用解析得到，但没有方法体可下钻
        // JBR 下 protectionDomain.codeSource 可能为 null，直接从测试类路径里找现成的 junit jar
        val junitJar = System.getProperty("java.class.path")
            .split(File.pathSeparator)
            .map(::File)
            .firstOrNull { it.name.startsWith("junit") && it.extension == "jar" }
            ?: error("测试类路径里找不到 junit jar")
        // 本地沙箱会把 junit jar 放进 .intellijPlatform 目录，不一定在测试框架默认的 VFS 允许根里
        VfsRootAccess.allowRootAccess(testRootDisposable, junitJar.parent)
        PsiTestUtil.addLibrary(module, "junit-lib", junitJar.parent, junitJar.name)

        val diagram = analyzeMethod(
            """
            package com.example;

            import org.junit.Assert;

            class Demo {
                void entry() {
                    Assert.assertTrue(true);
                }
            }
            """.trimIndent(),
        )
        // 库方法（binary jar）只画一根箭头：没有方法体可下钻，也不产生第二条消息
        assertEquals(1, diagram.messages.size)
        assertEquals("assertTrue(true)", diagram.messages[0].label)
    }

    fun `test 构造调用画创建消息`() {
        val diagram = analyzeMethod(
            """
            package com.example;

            class Demo {
                void entry() {
                    Service s = new Service();
                }
            }

            class Service {
                Service() {
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, diagram.messages.size)
        assertEquals(MessageKind.CREATE, diagram.messages[0].kind)
        assertEquals("new Service()", diagram.messages[0].label)
    }

    fun `test 深度上限截断`() {
        val diagram = analyzeMethod(
            """
            package com.example;

            class Demo {
                void entry() {
                    a1();
                }
                void a1() { a2(); }
                void a2() { a3(); }
                void a3() { a4(); }
                void a4() { a5(); }
                void a5() {
                }
            }
            """.trimIndent(),
            options = DiagramOptions(maxDepth = 2),
        )
        // entry(0) → a1（深度 0 的调用）→ a2（深度 1 的调用）；a3 在深度 2 被上限拦住
        assertEquals(listOf("a1()", "a2()"), diagram.messages.map { it.label })
    }

    fun `test 消息条数上限截断并备注`() {
        val diagram = analyzeMethod(
            """
            package com.example;

            class Demo {
                void entry() {
                    one();
                }
                void one() { two(); }
                void two() { three(); }
                void three() {
                }
            }
            """.trimIndent(),
            options = DiagramOptions(maxDepth = 5, maxMessages = 2),
        )
        assertTrue(diagram.truncated)
        assertTrue(diagram.messages.any { it.kind == MessageKind.NOTE && it.label.contains("截断") })
    }

    fun `test 接口方法没有方法体给备注`() {
        val diagram = analyzeMethod(
            """
            package com.example;

            interface Demo {
                void entry();
            }
            """.trimIndent(),
        )
        assertEquals(1, diagram.messages.size)
        assertEquals(MessageKind.NOTE, diagram.messages[0].kind)
        assertTrue(diagram.messages[0].label.contains("没有方法体"))
    }

    fun `test 无调用的方法给备注`() {
        val diagram = analyzeMethod(
            """
            package com.example;

            class Demo {
                void entry() {
                    int x = 1;
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, diagram.messages.size)
        assertEquals(MessageKind.NOTE, diagram.messages[0].kind)
        assertTrue(diagram.messages[0].label.contains("没有发现"))
    }

    fun `test 选项的记忆格式往返`() {
        val options = DiagramOptions(maxDepth = 7, skipTrivial = false, smartInterface = false, projectOnly = false, showReturns = true)
        assertEquals(options, DiagramOptions.fromName(options.toName()))
        assertEquals(DiagramOptions(), DiagramOptions.fromName(null))
        assertFalse(DiagramOptions.fromName("bad,input").skipTrivial != true)
    }

    fun `test if else 与循环的条件和调用都保留`() {
        val diagram = analyzeMethod(
            """
            package com.example;

            class Demo {
                private final Service service = new Service();

                void entry(boolean flag) {
                    if (flag && service.check()) {
                        service.thenBranch();
                    } else {
                        service.elseBranch();
                    }
                    for (int i = 0; i < 2; i++) {
                        service.loopBody();
                    }
                }
            }

            class Service {
                boolean check() { return true; }
                void thenBranch() { }
                void elseBranch() { }
                void loopBody() { }
            }
            """.trimIndent(),
        )
        val thenCall = diagram.messages.first { it.label == "thenBranch()" }
        val elseCall = diagram.messages.first { it.label == "elseBranch()" }
        val loopCall = diagram.messages.first { it.label == "loopBody()" }
        assertEquals(BranchKind.IF, thenCall.branchKind)
        assertTrue("条件原文应完整保留：" + thenCall.guard, thenCall.guard.contains("service.check()"))
        assertEquals(BranchKind.ELSE, elseCall.branchKind)
        assertEquals(BranchKind.LOOP, loopCall.branchKind)
        assertTrue("条件里的业务调用也要画出来，不能被丢弃", diagram.messages.any { it.label == "check()" })
        val kinds = diagram.fragments().map { it.kind }
        assertTrue("if 片段：" + kinds, kinds.contains(BranchKind.IF))
        assertTrue("else 片段：" + kinds, kinds.contains(BranchKind.ELSE))
        assertTrue("loop 片段：" + kinds, kinds.contains(BranchKind.LOOP))
    }
}
