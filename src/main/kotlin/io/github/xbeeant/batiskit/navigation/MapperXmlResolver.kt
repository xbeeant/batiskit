package io.github.xbeeant.batiskit.navigation

import com.intellij.openapi.components.service
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.xml.XmlText

/**
 * Mapper 接口（MyBatis / MyBatis-Plus 模式均适用）与 Mapper XML 的双向解析：
 *
 *  - 接口 FQN == XML 根标签的 namespace 属性 → 找到对应 XML 文件；
 *  - 方法名 == 语句标签（select / insert / update / delete）的 id 属性 → 找到语句片段；
 *  - 反向：namespace → 接口、(namespace, id) → 方法。
 *
 * 参考开源实现（MybatisX / free-idea-mybatis）的解析规则。
 *
 * 所有跨文件查询都走 [MapperXmlIndexCache]：一次 pass 只扫一遍 XML，
 * 后续同作用域的查询复用索引，不再按方法 / 标签重复枚举 FileTypeIndex。
 */
object MapperXmlResolver {

    private val STATEMENT_TAGS = setOf("select", "insert", "update", "delete")

    /** 是否为可导航的语句片段（有 id 属性的增删改查标签） */
    fun isStatementTag(tag: XmlTag): Boolean =
        tag.name in STATEMENT_TAGS && !tag.getAttributeValue("id").isNullOrBlank()

    /** mapper 接口对应的 XML 文件（模块范围内按 namespace 匹配，可能有多个） */
    fun xmlFiles(clazz: PsiClass): List<XmlFile> {
        val fqn = clazz.qualifiedName ?: return emptyList()
        return xmlFilesByNamespace(clazz, fqn)
    }

    /** 模块范围内按 namespace 找 Mapper XML 文件（可能有多个） */
    fun xmlFilesByNamespace(anchor: PsiElement, namespace: String): List<XmlFile> {
        val ns = namespace.trim()
        if (ns.isEmpty()) return emptyList()
        return indexOf(anchor).filesByNamespace[ns].orEmpty()
    }

    /**
     * 当前 XML 所在 namespace 下的全部 Mapper XML（含当前文件，按路径去重）。
     *
     * 一个 Mapper 接口可以对应**任意多个** XML 文件：MBG 生成的基础 XML + 手写扩展 XML 共用
     * namespace 是最常见的拆分方式。MyBatis 把 statement / resultMap / sql 统一按
     * `namespace.id` 注册，短名引用会先补上当前 namespace 再查，因此这些文件之间的短名
     * 互相可见——`extends="ResultMapWithBLOBs"` 写在扩展 XML、定义在基础 XML 是合法写法。
     *
     * 当前文件显式放进结果里：内存里 / 刚打开还没进 FileTypeIndex 的文件也要能解析自己的定义。
     */
    private fun sameNamespaceFiles(anchor: XmlTag): List<XmlFile> {
        val file = anchor.containingFile as? XmlFile ?: return emptyList()
        val namespace = file.rootTag?.getAttributeValue("namespace")?.trim().orEmpty()
        val result = LinkedHashMap<String, XmlFile>()
        fun keyOf(f: XmlFile) = f.virtualFile?.path ?: f.name
        result[keyOf(file)] = file
        if (namespace.isNotBlank()) {
            xmlFilesByNamespace(anchor, namespace).forEach { sibling ->
                result.putIfAbsent(keyOf(sibling), sibling)
            }
        }
        return result.values.toList()
    }

    // ------------------------------------------------------------------ resultMap 双向解析

    /**
     * 解析 `resultMap="…"` / `extends="…"` 属性引用，返回对应的 `<resultMap>` 定义。
     *
     * 引用值可以是短名 `userMap`，也可以是跨 namespace 的全限定 `com.e.UserMapper.userMap`：
     *  - 带 namespace 前缀：先按前缀跨文件找；
     *  - 短名（含全限定名找不到时按最后一段兜底）：按**当前 namespace** 在同 namespace 的
     *    所有 XML 里找——一个接口对应多个 XML 时，定义在兄弟文件里同样合法。
     *  - 不同 namespace 的短名不会互相串（MyBatis 短名只补当前 namespace）。
     */
    fun resultMapDefinitions(tag: XmlTag, reference: String): List<XmlTag> {
        val ref = reference.trim()
        if (ref.isBlank()) return emptyList()
        val index = indexOf(tag)
        if (ref.contains('.')) {
            val namespace = ref.substringBeforeLast('.')
            val id = ref.substringAfterLast('.')
            if (id.isNotBlank()) {
                val defs = definitionsIn(index, tag, "resultMap", namespace, id)
                if (defs.isNotEmpty()) {
                    return defs
                }
            }
        }
        val shortId = ref.substringAfterLast('.')
        val ownNamespace = (tag.containingFile as? XmlFile)?.rootTag?.getAttributeValue("namespace").orEmpty()
        return definitionsIn(index, tag, "resultMap", ownNamespace, shortId)
    }

    /**
     * 从索引取定义，并补上当前未进索引的内存文件（与 [sameNamespaceFiles] 的兜底一致）。
     */
    private fun definitionsIn(
        index: MapperXmlIndex,
        anchor: XmlTag,
        tagName: String,
        namespace: String,
        id: String,
    ): List<XmlTag> {
        val out = LinkedHashSet<XmlTag>()
        val root = (anchor.containingFile as? XmlFile)?.rootTag
        if (root?.name == "mapper" &&
            root.getAttributeValue("namespace")?.trim().orEmpty() == namespace.trim()
        ) {
            root.subTags.filterTo(out) {
                it.name == tagName && it.getAttributeValue("id")?.trim() == id
            }
        }
        val fromIndex = when (tagName) {
            "resultMap" -> index.resultMaps
            "sql" -> index.sqlFragments
            else -> emptyMap()
        }[mapperKey(namespace.trim(), id)]
        if (fromIndex != null) out.addAll(fromIndex)
        return out.toList()
    }

    /**
     * `<resultMap>` 定义的所有引用处：语句标签的 `resultMap` 属性、其他 resultMap 的 `extends`、
     * 以及嵌套 `<association resultMap>` / `<collection resultMap>`。
     * 模块范围内所有 Mapper XML 都参与匹配：同 namespace 的兄弟文件用短名，跨 namespace 用全限定名。
     */
    fun resultMapUsages(definition: XmlTag): List<XmlTag> {
        val id = definition.getAttributeValue("id")?.trim().orEmpty()
        if (id.isBlank()) return emptyList()
        val file = definition.containingFile as? XmlFile ?: return emptyList()
        val namespace = file.rootTag?.getAttributeValue("namespace").orEmpty()
        val usages = ArrayList<XmlTag>()
        for (reference in indexOf(definition).resultMapReferences) {
            if (reference.tag === definition) continue
            if (referencesId(reference.raw, reference.namespace, id, namespace)) {
                usages += reference.tag
            }
        }
        return usages
    }

    /**
     * 未限定的引用值只在**同一 namespace** 内有效——MyBatis 按当前 namespace 解析短名，
     * 同 namespace 拆出的多个 XML 都算（不要求同一个文件）；不同 namespace 里恰好同名的
     * resultMap 不能算成引用，跨 namespace 必须写全限定名。
     */
    private fun referencesId(raw: String?, tagNamespace: String, id: String, ownNamespace: String): Boolean {
        if (raw.isNullOrBlank()) return false
        val value = raw.trim()
        if (value == id) {
            return tagNamespace == ownNamespace
        }
        return value == "$ownNamespace.$id" || (tagNamespace.isNotBlank() && value == "$tagNamespace.$id")
    }

    // ------------------------------------------------------------------ <sql> 片段 / <include refid>

    /**
     * 解析 `<include refid="…">`，返回对应的 `<sql id="…">` 定义。
     * 与 resultMap 同样的规则：短名按当前 namespace 在同 namespace 的所有 XML 里找
     * （一个接口拆成多个 XML 时片段可以放在兄弟文件），全限定 `namespace.id` 跨 namespace 找。
     */
    fun sqlDefinitions(tag: XmlTag, reference: String): List<XmlTag> {
        val ref = reference.trim()
        if (ref.isBlank()) return emptyList()
        val index = indexOf(tag)
        if (ref.contains('.')) {
            val namespace = ref.substringBeforeLast('.')
            val id = ref.substringAfterLast('.')
            if (id.isNotBlank()) {
                val defs = definitionsIn(index, tag, "sql", namespace, id)
                if (defs.isNotEmpty()) return defs
            }
        }
        val shortId = ref.substringAfterLast('.')
        val ownNamespace = (tag.containingFile as? XmlFile)?.rootTag?.getAttributeValue("namespace").orEmpty()
        return definitionsIn(index, tag, "sql", ownNamespace, shortId)
    }

    /** `<sql>` 定义的全部 `<include refid>` 引用处（同 namespace 用短名，跨文件写全限定名） */
    fun sqlUsages(definition: XmlTag): List<XmlTag> {
        val id = definition.getAttributeValue("id")?.trim().orEmpty()
        if (id.isBlank()) return emptyList()
        val file = definition.containingFile as? XmlFile ?: return emptyList()
        val namespace = file.rootTag?.getAttributeValue("namespace").orEmpty()
        val usages = ArrayList<XmlTag>()
        for (reference in indexOf(definition).sqlReferences) {
            if (referencesId(reference.raw, reference.namespace, id, namespace)) {
                usages += reference.tag
            }
        }
        return usages
    }

    /**
     * `refid` / `resultMap` / `extends` 补全候选的公共规则：
     *  - 本文件的 id 给短名；
     *  - **同 namespace 的兄弟 XML**（一个接口拆成多个文件）同样给短名，因为 MyBatis 按 namespace 解析短名；
     *  - 其它 namespace 必须写全限定 `namespace.id`。
     *
     * 补全跑在 EDT，限制参与的文件数，避免大工程里全量扫 XML。
     */
    private fun idVariants(anchor: XmlTag, tagName: String): List<String> {
        val file = anchor.containingFile as? XmlFile ?: return emptyList()
        val root = file.rootTag ?: return emptyList()
        val ownNamespace = root.getAttributeValue("namespace")?.trim().orEmpty()
        val own = root.subTags.mapNotNull { tag ->
            tag.takeIf { it.name == tagName }?.getAttributeValue("id")?.trim()?.takeIf(String::isNotEmpty)
        }
        val index = indexOf(anchor)
        val others = mutableListOf<String>()
        var visited = 0
        outer@ for ((namespace, files) in index.filesByNamespace) {
            for (other in files) {
                if (visited++ >= MAX_VARIANT_FILES) break@outer
                if (other === file || (file.virtualFile != null && other.virtualFile == file.virtualFile)) continue
                val prefix = if (namespace == ownNamespace) "" else "$namespace."
                index.idsByNamespaceAndTag[idsKey(namespace, tagName)]?.forEach { id ->
                    others += "$prefix$id"
                }
            }
        }
        return (own + others).distinct()
    }

    /** `refid` 补全候选：本文件 / 同 namespace 兄弟文件的短 id + 其它 namespace 的全限定名 */
    fun sqlIdVariants(anchor: XmlTag): List<String> = idVariants(anchor, "sql")

    /** `resultMap` / `extends` 补全候选，规则与 [sqlIdVariants] 一致 */
    fun resultMapIdVariants(anchor: XmlTag): List<String> = idVariants(anchor, "resultMap")

    /** `id` 补全候选：接口里还没有对应 XML 语句的方法名（比笼统列全部方法更贴近真实用法） */
    fun statementIdVariants(anchor: XmlTag): List<String> {
        val clazz = interfaceOf(anchor) ?: return emptyList()
        val namespace = clazz.qualifiedName ?: return emptyList()
        val index = indexOf(anchor)
        val existing = STATEMENT_TAGS
            .flatMap { index.idsByNamespaceAndTag[idsKey(namespace, it)].orEmpty() }
            .toSet()
        return clazz.methods
            .mapNotNull { it.name.trim().takeIf(String::isNotEmpty) }
            .filterNot { it in existing }
            .distinct()
    }

    /** `namespace` 补全候选：工程内已有 Mapper XML 的 namespace 去重集合 */
    fun namespaceVariants(anchor: PsiElement): List<String> =
        indexOf(anchor).filesByNamespace.keys.take(MAX_VARIANT_FILES).toList()

    private const val MAX_VARIANT_FILES = 200

    /** 接口对应的第一个 XML 根标签（文件级跳转落点） */
    fun xmlRoot(clazz: PsiClass): XmlTag? = xmlFiles(clazz).firstNotNullOfOrNull { it.rootTag }

    /** mapper 方法对应的 XML 语句片段（同名 id 可能出现多个） */
    fun statements(method: PsiMethod): List<XmlTag> {
        val fqn = method.containingClass?.qualifiedName ?: return emptyList()
        return indexOf(method).statements[mapperKey(fqn, method.name)].orEmpty()
    }

    /** XML 语句片段对应的 mapper 方法（namespace + id 反查） */
    fun method(tag: XmlTag): PsiMethod? {
        val id = tag.getAttributeValue("id") ?: return null
        return interfaceOf(tag)?.findMethodsByName(id, false)?.firstOrNull()
    }

    /**
     * 光标所在的 XmlText 属于哪个语句标签（沿父链向上找最近的增删改查标签）。
     *
     * `<sql>` 片段返回 null：它的参数来自 include 它的语句，静态判断不了——
     * 参数绑定检查与 `#{}` / `${}` 补全共用这条规则。
     */
    fun statementOf(text: XmlText): XmlTag? {
        var tag = PsiTreeUtil.getParentOfType(text, XmlTag::class.java) ?: return null
        while (true) {
            if (isStatementTag(tag)) {
                return tag
            }
            if (tag.name == "mapper") {
                return null
            }
            tag = PsiTreeUtil.getParentOfType(tag, XmlTag::class.java) ?: return null
        }
    }

    /** 语句内声明的临时变量：`<foreach item|index>` 与 `<bind name>`，作用域到 [text] 所在节点为止 */
    fun eachVarsOf(text: XmlText, statement: XmlTag): Set<String> {
        val vars = mutableSetOf<String>()
        var tag = PsiTreeUtil.getParentOfType(text, XmlTag::class.java)
        while (tag != null && tag !== statement) {
            when (tag.name) {
                "foreach" -> {
                    tag.getAttributeValue("item")?.trim()?.takeIf { it.isNotEmpty() }?.let { vars += it }
                    tag.getAttributeValue("index")?.trim()?.takeIf { it.isNotEmpty() }?.let { vars += it }
                }

                "bind" -> tag.getAttributeValue("name")?.trim()?.takeIf { it.isNotEmpty() }?.let { vars += it }
            }
            tag = PsiTreeUtil.getParentOfType(tag, XmlTag::class.java)
        }
        return vars
    }

    /**
     * XML 文件根标签的 namespace 对应的接口。
     *
     * 解析不到（namespace 写错、接口不在索引范围内、mapper 来自依赖包）时返回 null——
     * 所有依赖它的检查都必须据此放弃，不能把「查不到」当成「不存在」。
     */
    fun interfaceOf(tag: XmlTag): PsiClass? {
        val namespace = (tag.containingFile as? XmlFile)?.rootTag?.getAttributeValue("namespace")
            ?: return null
        if (namespace.isBlank()) {
            return null
        }
        return JavaPsiFacade.getInstance(tag.project).findClass(namespace, scopeOf(tag))
    }

    /** 让一次 collectSlowLineMarkers pass 只建立一份索引，其余查询直接复用 */
    internal fun prepareIndex(anchor: PsiElement) {
        indexOf(anchor)
    }

    private fun indexOf(element: PsiElement): MapperXmlIndex {
        val project = element.project
        return project.service<MapperXmlIndexCache>().index(scopeKeyOf(element), scopeOf(element))
    }

    private fun scopeKeyOf(element: PsiElement): String =
        ModuleUtilCore.findModuleForPsiElement(element)?.moduleFilePath ?: "project:${element.project.name}"

    private fun scopeOf(element: PsiElement): GlobalSearchScope {
        val module = ModuleUtilCore.findModuleForPsiElement(element)
        return if (module != null) {
            GlobalSearchScope.moduleWithDependenciesScope(module)
        } else {
            GlobalSearchScope.projectScope(element.project)
        }
    }
}
