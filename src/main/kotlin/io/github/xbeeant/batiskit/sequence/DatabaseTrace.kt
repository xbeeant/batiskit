package io.github.xbeeant.batiskit.sequence

import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.DasUtil
import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import io.github.xbeeant.batiskit.config.MapperAssistSettings
import io.github.xbeeant.batiskit.meta.MapperSqlRules
import io.github.xbeeant.batiskit.meta.TableMappingResolver
import io.github.xbeeant.batiskit.meta.TableMetadata
import io.github.xbeeant.batiskit.navigation.MapperXmlResolver
import io.github.xbeeant.batiskit.sequence.DatabaseTraceRules.Framework

/**
 * 把「调用到 Mapper 方法」这一步继续画到**数据库层**：解析出真实 SQL、涉及的表，
 * 在时序图里加一张（或几张）`database:` 参与者与一根 [MessageKind.SQL] 箭头。
 *
 * 只在有 MyBatis / MyBatis-Plus 痕迹的工程里生效，且必须拿到以下任一显式证据，绝不凭类名瞎猜：
 *  - Mapper XML 里 `namespace == 接口 FQN`、`id == 方法名` 的 `<select|insert|update|delete>`；
 *  - 方法上的 `@Select` / `@Insert` / `@Update` / `@Delete`（含 `...Provider`）；
 *  - MyBatis-Plus `BaseMapper` / `IService` / `ServiceImpl` 继承来的通用 CRUD 方法
 *    （没有 SQL 文本，按动词 + 实体表名合成标签）。
 *
 * 表名优先级：SQL 正文里的 `FROM / JOIN / INTO / UPDATE` > 设置页手写映射 >
 * 泛型实体上的 `@TableName` / 实体类名 > namespace 推断。数据库表参与者还会尝试
 * 关联 IDE Database 视图里已加载的 `DbTable`，双击表头可直接跳到那张表；找不到时
 * 只是少了跳转，不影响画图。全程只读 PSI / 索引与 DAS 模型，不连 JDBC。
 *
 * 生命周期：一次调用链分析一个实例，[stepCache] / [tableCache] 只在这张图内复用，
 * 避免同一条链里重复扫 XML 索引和数据源表清单；源码改了重新生成图就会重新解析。
 */
class DatabaseTrace(private val project: Project, private val options: DiagramOptions) {

    private data class DbStep(
        val tables: List<String>,
        /** 图形页签的短标签（SQL 压到 60 字） */
        val label: String,
        /** 文本导出的完整 SQL（剥动态标签、压成一行，但不截断） */
        val fullLabel: String,
        val navigation: PsiElement,
    )

    /** 工程里是否存在 MyBatis / MyBatis-Plus：classpath 标记类优先，Mapper XML 文件名兜底 */
    private val framework: Framework by lazy { detectFramework() }

    private val hasMapperXml: Boolean by lazy { detectMapperXml() }

    /** 没有框架痕迹、也没有 Mapper XML 的纯 Java 工程，整条链都不做数据库层解析 */
    private val active: Boolean
        get() = options.traceDatabase && (framework != Framework.NONE || hasMapperXml)

    private val stepCache = HashMap<String, DbStep?>()
    private val tableCache = HashMap<String, PsiElement?>()
    /** 接口 FQN → 是否绑定了 Mapper XML；按类缓存一次，避免每个方法都扫一遍 XML 索引 */
    private val namespaceCache = HashMap<String, Boolean>()

    /**
     * 在一次 Mapper 方法调用之后追加数据库层消息。
     *
     * @param from         Mapper 参与者的身份键（SQL 箭头由它发出）
     * @param depth        比 Mapper 调用深一层
     * @param method       解析到的 Mapper 方法（智能接口展开后的方法）
     * @param receiverClass 调用接收者的声明类型；`UserMapper extends BaseMapper<User>` 的
     *                      泛型实体只能从这个类型上读到，不能只看 `BaseMapper` 本身
     * @return 最后一条 SQL 消息的下标；没有数据库层证据 / 开关关闭 / 达到消息上限时返回 null
     */
    fun append(
        diagram: SequenceDiagram,
        from: String,
        depth: Int,
        method: PsiMethod,
        receiverClass: PsiClass? = null,
    ): Int? {
        if (!active) return null
        val step = resolve(method, receiverClass) ?: return null
        var last: Int? = null
        for (table in step.tables) {
            if (options.limitReached(diagram.messages.size)) {
                noteTruncated(diagram, from)
                break
            }
            val participant = diagram.addParticipant(
                SeqParticipant(
                    qualified = DatabaseTraceRules.participantKey(table),
                    display = DatabaseTraceRules.displayOf(table),
                    isDatabase = true,
                    psiClass = dbTableOf(table),
                    fullDisplay = DatabaseTraceRules.fullDisplayOf(table),
                ),
            )
            val index = diagram.addMessage(
                SeqMessage(
                    kind = MessageKind.SQL,
                    from = from,
                    to = participant.qualified,
                    label = step.label,
                    depth = depth,
                    psiMethod = step.navigation,
                    fullLabel = step.fullLabel,
                ),
            )
            diagram.messages[index].endIndex = index
            last = index
        }
        return last
    }

    // ---------- 解析 ----------

    private fun resolve(method: PsiMethod, receiverClass: PsiClass?): DbStep? {
        val cls = method.containingClass ?: return null
        val owner = receiverClass ?: cls
        val key = "${cls.qualifiedName.orEmpty()}#${method.name}(${method.parameterList.parametersCount})" +
            "@${owner.qualifiedName.orEmpty()}"
        if (stepCache.containsKey(key)) return stepCache[key]
        val step = runCatching { doResolve(method, cls, owner) }.getOrNull()
        stepCache[key] = step
        return step
    }

    private fun doResolve(method: PsiMethod, cls: PsiClass, owner: PsiClass): DbStep? {
        val sqlAnnotation = method.modifierList?.annotations.orEmpty()
            .firstOrNull { annotationSimpleName(it) in SQL_ANNOTATIONS }
        if (!isCandidate(method, cls, owner, sqlAnnotation != null)) return null

        // XML 扫描要过索引，只对候选方法做一次并缓存
        val statement: XmlTag? = if (sqlAnnotation == null) {
            runCatching { MapperXmlResolver.statements(method).firstOrNull() }.getOrNull()
        } else {
            null
        }

        val names = hierarchyNames(cls, owner)
        // 必须真的处在 MP 继承链上：只因为工程里带了 mybatis-plus 依赖，就给普通
        // `FooService.list()` 之类的同名业务方法配一条 SQL 是误报
        val plusBase = names.any { it in PLUS_BASE_TYPES }
        val inherited = method.body == null &&
            (cls.isInterface || method.hasModifierProperty(PsiModifier.ABSTRACT))
        val plusGeneric = method.name in MP_CRUD_METHODS && inherited && plusBase

        // 必须有 SQL 出处：XML 语句 / SQL 注解 / MP 通用 CRUD；只有 @Mapper 注解时不猜 SQL
        if (statement == null && sqlAnnotation == null && !plusGeneric) return null

        // @SelectProvider 之类只有 type/method 字符串，不是可执行 SQL：
        // 别把它当标签，交给 verbOf 按注解名定动词、再合成「动词 + 表名」的描述
        val isProvider = sqlAnnotation != null &&
            annotationSimpleName(sqlAnnotation).endsWith("Provider")
        val rawSql: String? = when {
            statement != null -> statement.text
            sqlAnnotation != null && !isProvider -> annotationSql(sqlAnnotation)
            else -> null
        }
        val verb = DatabaseTraceRules.verbOf(
            tag = statement?.name,
            annotation = sqlAnnotation?.let { annotationSimpleName(it) },
            methodName = method.name,
            sql = rawSql,
        )

        val fromSql = DatabaseTraceRules.tablesOf(rawSql)
        val tables = if (fromSql.isNotEmpty()) {
            fromSql
        } else {
            val xmlFile = statement?.containingFile as? XmlFile
            val entity = entityOf(owner, cls)
            val namespace = owner.qualifiedName ?: cls.qualifiedName.orEmpty()
            val manual = runCatching {
                MapperSqlRules.mappingFor(
                    namespace, xmlFile?.name.orEmpty(),
                    MapperSqlRules.parseMappings(MapperAssistSettings.getInstance().state.tableMappings),
                )
            }.getOrNull()
            val annotatedTable = entity?.let { runCatching { tableOfEntity(it) }.getOrNull() }
            val mapped = runCatching {
                TableMappingResolver.tableNameFor(
                    project, xmlFile, namespace,
                    entity?.qualifiedName?.let { listOf(it) }.orEmpty(),
                )
            }.getOrNull()
            listOfNotNull(manual ?: annotatedTable ?: mapped)
                .map { MapperSqlRules.sanitizeIdentifier(it) }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(DatabaseTraceRules.MAX_TABLES)
        }
        if (tables.isEmpty()) return null

        val stepFramework = when {
            plusGeneric -> Framework.MYBATIS_PLUS
            framework != Framework.NONE -> framework
            else -> Framework.MYBATIS
        }
        val navigation: PsiElement = when {
            statement != null -> statement
            sqlAnnotation != null -> sqlAnnotation
            else -> method
        }
        return DbStep(
            tables = tables,
            label = DatabaseTraceRules.label(verb, rawSql, tables.first(), stepFramework),
            fullLabel = DatabaseTraceRules.fullLabel(verb, rawSql, tables.first(), stepFramework),
            navigation = navigation,
        )
    }

    /** 便宜的前置判定：拦掉绝大多数普通 Java 方法，避免为每次调用都去扫 XML 索引 */
    private fun isCandidate(method: PsiMethod, cls: PsiClass, owner: PsiClass, hasSqlAnnotation: Boolean): Boolean {
        if (hasSqlAnnotation) return true
        if (hasAnnotation(cls, "Mapper") || hasAnnotation(method, "Mapper")) return true
        if (isFrameworkBase(cls.name) || isFrameworkBase(owner.name)) return true
        if (isMapperLike(cls.name) || isMapperLike(owner.name)) return method.body == null
        // 名字不叫 *Mapper 的接口，只要 namespace 绑定了 XML 也算 Mapper（按类缓存一次索引扫描）
        if (method.body == null && cls.isInterface) return hasBoundXml(cls)
        return false
    }

    private fun hasBoundXml(cls: PsiClass): Boolean {
        val fqn = cls.qualifiedName ?: return false
        return namespaceCache.getOrPut(fqn) {
            runCatching { MapperXmlResolver.xmlFiles(cls).isNotEmpty() }.getOrDefault(false)
        }
    }

    /** 泛型实体：`UserMapper extends BaseMapper<User>` / `ServiceImpl<UserMapper, User>` 里的 `User` */
    private fun entityOf(vararg roots: PsiClass?): PsiClass? {
        val seen = HashSet<String>()
        val queue = java.util.ArrayDeque<Pair<PsiClass, Int>>()
        roots.filterNotNull().forEach { queue.addLast(it to 0) }
        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            if (!seen.add(current.qualifiedName ?: current.name.orEmpty())) continue
            val types = current.extendsListTypes + current.implementsListTypes
            for (type in types) {
                for (parameter in type.parameters) {
                    val resolved = (parameter as? PsiClassType)?.resolve() ?: continue
                    if (resolved is PsiTypeParameter || resolved.isInterface || resolved.isEnum ||
                        resolved.isAnnotationType
                    ) continue
                    val fqn = resolved.qualifiedName.orEmpty()
                    if (fqn.startsWith("java.") || fqn.startsWith("kotlin.")) continue
                    val simple = resolved.name.orEmpty()
                    if (isFrameworkBase(simple) || isMapperLike(simple)) continue
                    return resolved
                }
            }
            if (depth < 3) {
                for (type in types) {
                    val parent = runCatching { type.resolve() }.getOrNull()
                    if (parent != null) queue.addLast(parent to depth + 1)
                }
            }
        }
        return null
    }

    private fun tableOfEntity(entity: PsiClass): String? {
        val annotated = runCatching { TableMetadata.annotatedTableName(entity) }.getOrNull()
        val name = annotated ?: MapperSqlRules.toSnakeCase(entity.name.orEmpty())
        return MapperSqlRules.sanitizeIdentifier(name).takeIf { it.isNotEmpty() }
    }

    /** 继承链上的简单类名（含自身），用于识别 MyBatis-Plus 的基类 */
    private fun hierarchyNames(vararg roots: PsiClass?): Set<String> {
        val result = LinkedHashSet<String>()
        val seen = HashSet<String>()
        val queue = java.util.ArrayDeque<Pair<PsiClass, Int>>()
        roots.filterNotNull().forEach { queue.addLast(it to 0) }
        while (queue.isNotEmpty()) {
            val (current, depth) = queue.removeFirst()
            current.name?.let { result += it }
            if (!seen.add(current.qualifiedName ?: current.name.orEmpty()) || depth >= 3) continue
            val types = current.extendsListTypes + current.implementsListTypes
            for (type in types) {
                val parent = runCatching { type.resolve() }.getOrNull()
                if (parent != null) queue.addLast(parent to depth + 1)
            }
        }
        return result
    }

    private fun annotationSimpleName(annotation: PsiAnnotation): String =
        annotation.nameReferenceElement?.text?.substringAfterLast('.')
            ?: annotation.qualifiedName?.substringAfterLast('.')
            ?: ""

    private fun hasAnnotation(owner: PsiModifierListOwner?, simpleName: String): Boolean =
        owner?.modifierList?.annotations.orEmpty().any { annotationSimpleName(it) == simpleName }

    /** `@Select("…")` / `@Select({"…", "…"})` / `@Select(value = "…")` 都取字符串字面量 */
    private fun annotationSql(annotation: PsiAnnotation): String? {
        val value = runCatching {
            annotation.findAttributeValue("value") ?: annotation.parameterList.attributes.firstOrNull()?.value
        }.getOrNull()
        // 简写注解 `@Select("…")` 在某些 PSI 实现里不产出 name-value pair，退回整段注解文本
        val text = value?.text ?: annotation.text
        val literals = STRING_LITERAL.findAll(text).map { it.groupValues[1] }.toList()
        return (if (literals.isNotEmpty()) literals.joinToString(" ") else text.trim().trim('"'))
            .takeIf { it.isNotBlank() }
    }

    private fun isMapperLike(name: String?): Boolean {
        val value = name ?: return false
        return value.endsWith("Mapper") || value.endsWith("Dao") || value.endsWith("Repository") ||
            value.endsWith("ServiceImpl") || value.endsWith("Service")
    }

    private fun isFrameworkBase(name: String?): Boolean {
        val value = name ?: return false
        return value in PLUS_BASE_TYPES || value in MYBATIS_BASE_TYPES
    }

    // ---------- 工程识别 / Database 视图表 ----------

    private fun detectFramework(): Framework {
        val facade = runCatching { JavaPsiFacade.getInstance(project) }.getOrNull() ?: return Framework.NONE
        val scope = GlobalSearchScope.allScope(project)
        fun present(fqn: String) = runCatching { facade.findClass(fqn, scope) != null }.getOrDefault(false)
        return when {
            present("com.baomidou.mybatisplus.core.mapper.BaseMapper") ||
                present("com.baomidou.mybatisplus.extension.service.IService") -> Framework.MYBATIS_PLUS

            present("org.apache.ibatis.annotations.Mapper") ||
                present("org.apache.ibatis.session.SqlSessionFactory") -> Framework.MYBATIS

            else -> Framework.NONE
        }
    }

    /** 只有 XML 没有显式依赖（例如纯 XML 配置的工程）时，用文件名 / `<mapper>` 根标签兜底 */
    private fun detectMapperXml(): Boolean = runCatching {
        val manager = PsiManager.getInstance(project)
        FileTypeIndex.getFiles(XmlFileType.INSTANCE, GlobalSearchScope.projectScope(project))
            .asSequence()
            .take(200)
            .any { file ->
                file.name.contains("mapper", ignoreCase = true) || file.name.contains("dao", ignoreCase = true) ||
                    runCatching { (manager.findFile(file) as? XmlFile)?.rootTag?.name == "mapper" }
                        .getOrDefault(false)
            }
    }.getOrDefault(false)

    /**
     * 按表名在 IDE 已加载的数据源里找 `DbTable`：命中足够可信（≥70 分）才挂到参与者上，
     * 双击表头会跳到 Database 视图。没有数据源 / 插件不可用时安静返回 null。
     */
    private fun dbTableOf(table: String): PsiElement? {
        val key = table.lowercase()
        if (tableCache.containsKey(key)) return tableCache[key]
        // 只在平台 API 调用处兜底；checkCanceled() 必须留在外层，否则取消异常会被 runCatching 吞掉
        val facade = runCatching { DbPsiFacade.getInstance(project) }.getOrNull()
        val sources = runCatching { facade?.dataSources }.getOrNull().orEmpty()
        var best: PsiElement? = null
        var bestScore = -1
        for (source in sources.take(MAX_DATA_SOURCES)) {
            val tables = runCatching { DasUtil.getTables(source) }.getOrNull() ?: continue
            var scanned = 0
            for (candidate in tables) {
                if (++scanned > MAX_TABLES_PER_SOURCE) break
                ProgressManager.checkCanceled()
                val score = MapperSqlRules.tableMatchScore(candidate.name, table)
                if (score > bestScore) {
                    bestScore = score
                    best = candidate as? PsiElement
                }
                if (score == 100) break
            }
            if (bestScore == 100) break
        }
        val found = if (bestScore >= 70) best else null
        tableCache[key] = found
        return found
    }

    private fun noteTruncated(diagram: SequenceDiagram, at: String) {
        if (diagram.truncated) return
        diagram.truncated = true
        diagram.addMessage(
            SeqMessage(MessageKind.NOTE, at, at, "已达到 ${options.maxMessages} 条消息上限，剩余调用被截断"),
        )
    }

    companion object {
        const val MAX_DATA_SOURCES = 4
        const val MAX_TABLES_PER_SOURCE = 600

        val SQL_ANNOTATIONS = setOf(
            "Select", "Insert", "Update", "Delete",
            "SelectProvider", "InsertProvider", "UpdateProvider", "DeleteProvider",
        )

        val PLUS_BASE_TYPES = setOf(
            "BaseMapper", "IService", "ServiceImpl", "IMybatisPlusDao", "MPJBaseMapper",
        )

        val MYBATIS_BASE_TYPES = setOf(
            "IMybatisPageHelperDao", "SqlSession", "SqlSessionTemplate",
        )

        /** MyBatis-Plus 通用 CRUD：只在「继承来的、无方法体」的方法上生效，避免给业务方法乱配 SQL */
        val MP_CRUD_METHODS = setOf(
            "selectById", "selectBatchIds", "selectByMap", "selectOne", "selectList", "selectMaps",
            "selectObjs", "selectCount", "selectPage", "selectMapsPage", "insert", "insertOrUpdate",
            "deleteById", "deleteByIds", "deleteByMap", "delete", "deleteBatchIds", "updateById", "update",
            "getById", "getOne", "getMap", "getObj", "list", "listByIds", "listByMap", "listMaps",
            "listObjs", "page", "pageMaps", "count", "save", "saveBatch", "saveOrUpdate",
            "saveOrUpdateBatch", "removeById", "removeByIds", "removeByMap", "remove",
            "updateBatchById", "lambdaQuery", "lambdaUpdate",
        )

        val STRING_LITERAL = Regex("\"([^\"]*)\"")
    }
}
