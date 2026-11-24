package io.github.xbeeant.batiskit.inspect

/**
 * Mapper 接口方法（只保留与「是否需要一条 XML 语句」有关的信息）。
 *
 * [hasBody] 为 true 表示方法本身带实现（`default` / `static` / Java 9 私有方法），
 * 这类方法由 Java 侧执行，不需要 MyBatis 绑定；[inherited] 为 true 表示方法来自父接口
 * （`BaseMapper` / 自定义基接口），语句由父接口的 XML 或 MyBatis-Plus 注入负责。
 */
data class MapperMethod(
    val name: String,
    val annotations: Set<String> = emptySet(),
    val hasBody: Boolean = false,
    val inherited: Boolean = false
)

/**
 * Mapper XML 语句与接口方法的对应关系校验（纯逻辑，不依赖 IDE）。
 *
 * 四条判定，口径都取「宁漏不误」：
 *  1. 只有**直接声明在接口里、且没有方法体、且没写 SQL 注解**的方法才需要一条语句；
 *  2. 方法名与语句 id 精确相等才算绑定（MyBatis 不支持重载，同名方法只认第一条）；
 *  3. 语句 id 是否「孤立」按**含继承**的方法全集判断——父接口声明的方法也是合法的绑定目标；
 *  4. 重复 id 只在同一 namespace 内算重复，跨 namespace 同名是允许的。
 */
object StatementBinding {

    /** 直接把 SQL 写在方法上的注解：写了这些就不需要 XML 语句 */
    val STATEMENT_ANNOTATIONS = setOf(
        "Select", "Insert", "Update", "Delete",
        "SelectProvider", "InsertProvider", "UpdateProvider", "DeleteProvider"
    )

    /** 语句标签种类（与 mapper 根标签的直接子标签对应） */
    val STATEMENT_TAGS = setOf("select", "insert", "update", "delete")

    /** 注解的简单名：`org.apache.ibatis.annotations.Select` → `Select` */
    fun annotationName(qualifiedNameOrText: String): String = qualifiedNameOrText.substringAfterLast('.')

    /** 方法是否需要一条 XML 语句 */
    fun needsStatement(method: MapperMethod): Boolean =
        !method.inherited &&
            !method.hasBody &&
            method.annotations.none { annotationName(it) in STATEMENT_ANNOTATIONS }

    /**
     * 接口里有、XML 里没有的方法。
     *
     * [ids] 是该 namespace 下所有 XML 文件里定义的语句 id 全集。
     */
    fun missingStatements(methods: List<MapperMethod>, ids: Set<String>): List<MapperMethod> =
        methods.filter { needsStatement(it) && it.name !in ids }

    /**
     * XML 里有、接口里没有同名方法的语句 id（按出现顺序去重）。
     *
     * [methodNames] 要传**含继承**的方法名全集。
     */
    fun orphanStatements(ids: List<String>, methodNames: Set<String>): List<String> =
        ids.filterNot { it in methodNames }.distinct()

    /** 同一 namespace 内重复出现的语句 id（按出现顺序，id → 次数） */
    fun duplicatedIds(ids: List<String>): Map<String, Int> =
        ids.groupingBy { it }.eachCount().filterValues { it > 1 }
}
