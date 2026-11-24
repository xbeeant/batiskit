package io.github.xbeeant.batiskit.sequence

import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.xml.XmlTag
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.toUElement
import java.util.concurrent.atomic.AtomicInteger

/**
 * 数据库层追踪的真实 PSI 回归：Mapper XML 语句、SQL 注解、MyBatis-Plus 通用 CRUD，
 * 以及「起点就是 Mapper 方法」「关掉开关」两条边界。
 *
 * 用 Java light fixture 即可：数据库层解析只依赖 Java / XML PSI，与 Kotlin UAST 路径共用
 * 同一份 [DatabaseTrace]。每个用例用独立包名，避免 light project 里残留文件互相干扰。
 */
class DatabaseTraceTest : LightJavaCodeInsightFixtureTestCase() {

    private val caseSeq = AtomicInteger()

    private fun nextPackage(): String = "com.example.db${caseSeq.incrementAndGet()}"

    private fun mapperXml(pkg: String, body: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <mapper namespace="$pkg.UserMapper">
        $body
        </mapper>
        """.trimIndent()

    private fun addProject(pkg: String, java: String, xmlBody: String): PsiJavaFile {
        val path = pkg.replace('.', '/')
        val file = myFixture.addFileToProject("$path/Demo.java", java) as PsiJavaFile
        myFixture.addFileToProject("mybatis/$path/UserMapper.xml", mapperXml(pkg, xmlBody))
        return file
    }

    private fun analyze(file: PsiJavaFile, options: DiagramOptions = DiagramOptions()): SequenceDiagram {
        val method = file.classes[0].findMethodsByName("entry", false).first() as PsiMethod
        return CallChainAnalyzer(project, options).analyze(method)
    }

    private fun sqlMessage(diagram: SequenceDiagram): SeqMessage =
        diagram.messages.firstOrNull { it.kind == MessageKind.SQL }
            ?: error("图里没有 SQL 箭头：${diagram.messages.map { it.kind to it.label }}")

    private fun tableParticipant(diagram: SequenceDiagram): SeqParticipant =
        diagram.participants.firstOrNull { it.isDatabase }
            ?: error("图里没有数据库表参与者：${diagram.participants.map { it.display }}")

    fun `test Mapper XML 语句画出数据库表与 SQL 箭头`() {
        val pkg = nextPackage()
        val file = addProject(
            pkg,
            """
            package $pkg;
            class Demo {
                private final UserMapper mapper = null;
                void entry() { mapper.selectById(1L); }
            }
            interface UserMapper {
                Object selectById(Long id);
            }
            """.trimIndent(),
            """
            <select id="selectById" resultType="java.lang.Object">
                SELECT * FROM sys_user u WHERE u.id = #{id}
            </select>
            """.trimIndent(),
        )

        val diagram = analyze(file)
        val db = tableParticipant(diagram)
        val sql = sqlMessage(diagram)
        assertTrue("参与者应显示 SQL 里的表名：${db.display}", db.display.contains("sys_user"))
        assertEquals(db.qualified, sql.to)
        assertTrue("标签应用真实 SQL：${sql.label}", sql.label.contains("SELECT * FROM sys_user"))
        assertTrue("双击 SQL 箭头应跳到 XML 语句", sql.psiMethod is XmlTag)
        assertTrue(
            "SQL 箭头应落在 Mapper 调用的激活区间内",
            diagram.messages.first { it.kind == MessageKind.CALL }.endIndex >= diagram.messages.indexOf(sql),
        )
    }

    fun `test 关掉开关后退回纯调用链`() {
        val pkg = nextPackage()
        val file = addProject(
            pkg,
            """
            package $pkg;
            class Demo {
                private final UserMapper mapper = null;
                void entry() { mapper.selectById(1L); }
            }
            interface UserMapper {
                Object selectById(Long id);
            }
            """.trimIndent(),
            """
            <select id="selectById">SELECT * FROM sys_user WHERE id = #{id}</select>
            """.trimIndent(),
        )

        val diagram = analyze(file, DiagramOptions(traceDatabase = false))
        assertTrue(diagram.messages.none { it.kind == MessageKind.SQL })
        assertTrue(diagram.participants.none { it.isDatabase })
        assertTrue("普通 Mapper 调用仍在", diagram.messages.any { it.label.startsWith("selectById") })
    }

    fun `test 起点就是 Mapper 接口方法也能看到 SQL`() {
        val pkg = nextPackage()
        val file = addProject(
            pkg,
            """
            package $pkg;
            class Demo { }
            interface UserMapper {
                Object selectById(Long id);
            }
            """.trimIndent(),
            """
            <select id="selectById">SELECT * FROM sys_user WHERE id = #{id}</select>
            """.trimIndent(),
        )
        val method = file.classes.first { it.name == "UserMapper" }
            .findMethodsByName("selectById", false).first()
        val diagram = CallChainAnalyzer(project, DiagramOptions()).analyze(method)

        sqlMessage(diagram)
        assertTrue(
            "不该再提示『无调用链可展开』",
            diagram.messages.none { it.kind == MessageKind.NOTE && it.label.contains("没有方法体") },
        )
    }

    fun `test Select 注解提供 SQL 出处`() {
        val pkg = nextPackage()
        val file = addProject(
            pkg,
            """
            package $pkg;
            class Demo {
                private final UserMapper mapper = null;
                void entry() { mapper.listAll(); }
            }
            interface UserMapper {
                @Select("SELECT * FROM sys_user WHERE id = #{id}")
                Object listAll();
            }
            @interface Select { String value(); }
            """.trimIndent(),
            "",
        )

        val diagram = analyze(file)
        val sql = sqlMessage(diagram)
        assertTrue("标签应取注解里的 SQL：${sql.label}", sql.label.contains("SELECT * FROM sys_user"))
        assertTrue("双击应跳到 SQL 注解", sql.psiMethod is PsiAnnotation)
        assertTrue(diagram.participants.any { it.isDatabase && it.display.contains("sys_user") })
    }

    fun `test UAST 路径同样能落到数据库层`() {
        val pkg = nextPackage()
        val file = addProject(
            pkg,
            """
            package $pkg;
            class Demo {
                private final UserMapper mapper = null;
                void entry() { mapper.selectById(1L); }
            }
            interface UserMapper {
                Object selectById(Long id);
            }
            """.trimIndent(),
            """
            <select id="selectById">SELECT * FROM sys_user WHERE id = #{id}</select>
            """.trimIndent(),
        )
        val method = file.classes[0].findMethodsByName("entry", false).first()
        val root = method.toUElement() as? UMethod ?: error("Java UAST 转换器不可用")
        val diagram = UastCallChainAnalyzer(project, DiagramOptions()).analyze(root)

        sqlMessage(diagram)
        assertTrue(diagram.participants.any { it.isDatabase && it.display.contains("sys_user") })
    }

    fun `test MyBatis-Plus 通用 CRUD 从泛型实体推断表`() {
        val pkg = nextPackage()
        val file = addProject(
            pkg,
            """
            package $pkg;
            class Demo {
                private final UserMapper mapper = null;
                void entry() { mapper.insert(new User()); }
            }
            class User { }
            interface BaseMapper<T> { int insert(T entity); }
            interface UserMapper extends BaseMapper<User> { }
            """.trimIndent(),
            "",
        )

        val diagram = analyze(file)
        val sql = sqlMessage(diagram)
        assertTrue(
            "应标注 MyBatis-Plus 合成标签：${sql.label}",
            sql.label.contains("INSERT INTO user") && sql.label.contains("MyBatis-Plus"),
        )
        assertTrue("双击应跳到继承来的 Mapper 方法", sql.psiMethod is PsiMethod)
        assertTrue(diagram.participants.any { it.isDatabase && it.display.contains("user") })
    }

    /**
     * 用户反馈：`fuzzySearchByPagerExt` 有多个实现，以前 `singleOrNull()` 返回 null，
     * 只画一根接口箭头、一个实现都不展开，各实现体里的 Mapper / SQL 于是一条都出不来
     * （截图状态栏只有「数据库表 1 张」）。这里验证两个实现分别展开、各自带出一张表。
     */
    fun `test 接口多个实现分别展开且各自 SQL 都进图`() {
        val pkg = nextPackage()
        val file = addProject(
            pkg,
            """
            package $pkg;
            class Demo {
                private final FuzzySearch service = null;
                void entry() { service.fuzzySearchByPagerExt(null); }
            }
            interface FuzzySearch {
                Object fuzzySearchByPagerExt(Object q);
            }
            class ImplA implements FuzzySearch {
                private final UserMapper mapper = null;
                @Override public Object fuzzySearchByPagerExt(Object q) { mapper.selectA(); return null; }
            }
            class ImplB implements FuzzySearch {
                private final UserMapper mapper = null;
                @Override public Object fuzzySearchByPagerExt(Object q) { mapper.selectB(); return null; }
            }
            interface UserMapper {
                Object selectA();
                Object selectB();
            }
            """.trimIndent(),
            """
            <select id="selectA">SELECT * FROM table_a WHERE id = #{id}</select>
            <select id="selectB">SELECT * FROM table_b WHERE id = #{id}</select>
            """.trimIndent(),
        )

        val diagram = analyze(file)
        val displays = diagram.participants.map { it.display }
        assertTrue("两个实现都要进图：$displays",
            displays.contains("ImplA") && displays.contains("ImplB"))
        val sqls = diagram.messages.filter { it.kind == MessageKind.SQL }
        assertTrue("每个实现的 SQL 都要出现：" + sqls.map { it.label }, sqls.size >= 2)
        assertTrue("ImplA 的 SQL：" + sqls.map { it.label }, sqls.any { it.label.contains("table_a") })
        assertTrue("ImplB 的 SQL：" + sqls.map { it.label }, sqls.any { it.label.contains("table_b") })
        val tables = diagram.participants.filter { it.isDatabase }.map { it.display }
        assertTrue("两张数据库表都要出现：$tables",
            tables.any { it.contains("table_a") } && tables.any { it.contains("table_b") })
        val implMsgs = diagram.messages.filter { it.branchKind == BranchKind.IMPL }
        assertTrue("多实现消息带 IMPL 分支：" + implMsgs.map { it.guard }, implMsgs.size >= 2)
        assertTrue("每个实现一条互斥分支：" + implMsgs.map { it.guard },
            implMsgs.map { it.guard }.toSet().size >= 2)
        assertTrue("应生成 IMPL 片段框：" + diagram.fragments().map { it.kind },
            diagram.fragments().count { it.kind == BranchKind.IMPL } >= 2)
    }
}
