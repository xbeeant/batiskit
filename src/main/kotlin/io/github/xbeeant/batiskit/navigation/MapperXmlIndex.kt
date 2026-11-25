package io.github.xbeeant.batiskit.navigation

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/** 一条 `resultMap` / `refid` 引用；namespace 与原始属性一起保留，查询时按 MyBatis 规则匹配 */
internal data class MapperReference(
    val tag: XmlTag,
    val namespace: String,
    val raw: String?,
)

/**
 * 一次扫描得到的 Mapper XML 索引。只保存字符串与 PSI 引用，
 * 整个索引跟随工程的 PSI / 根目录修改计数失效，不会跨编辑持有过期元素。
 */
internal class MapperXmlIndex(
    val filesByNamespace: Map<String, List<XmlFile>>,
    val statements: Map<String, List<XmlTag>>,
    val resultMaps: Map<String, List<XmlTag>>,
    val sqlFragments: Map<String, List<XmlTag>>,
    val idsByNamespaceAndTag: Map<String, Set<String>>,
    val resultMapReferences: List<MapperReference>,
    val sqlReferences: List<MapperReference>,
)

/**
 * Mapper XML 的项目级索引缓存：同一个作用域里的所有查询共用一次 FileTypeIndex 枚举 + 标签遍历。
 *
 * 之前 `MapperLineMarkerProvider` 给每个 Mapper 方法都调用一次
 * `FileTypeIndex.getFiles(...)` 并遍历全部 XML，方法数 × XML 数会直接放大到 EDT 卡顿。
 * 这里作为 project service 存活，按「模块作用域」缓存整份索引，PSI / 根目录一变整体重建。
 */
@Service(Service.Level.PROJECT)
internal class MapperXmlIndexCache(private val project: Project) : Disposable {

    private class Entry(val stamp: Long, val index: MapperXmlIndex)

    private val byScope = HashMap<String, Entry>()

    fun index(scopeKey: String, scope: GlobalSearchScope): MapperXmlIndex {
        val stamp = PsiModificationTracker.getInstance(project).modificationCount * 31 +
            ProjectRootManager.getInstance(project).modificationCount
        synchronized(this) {
            val cached = byScope[scopeKey]
            if (cached != null && cached.stamp == stamp) return cached.index
            val index = build(project, scope)
            byScope[scopeKey] = Entry(stamp, index)
            return index
        }
    }

    override fun dispose() {
        synchronized(this) { byScope.clear() }
    }

    companion object {
        private val STATEMENT_TAGS = setOf("select", "insert", "update", "delete")

        private fun build(project: Project, scope: GlobalSearchScope): MapperXmlIndex {
            val manager = PsiManager.getInstance(project)
            val filesByNamespace = LinkedHashMap<String, MutableList<XmlFile>>()
            val statements = LinkedHashMap<String, MutableList<XmlTag>>()
            val resultMaps = LinkedHashMap<String, MutableList<XmlTag>>()
            val sqlFragments = LinkedHashMap<String, MutableList<XmlTag>>()
            val ids = LinkedHashMap<String, LinkedHashSet<String>>()
            val resultMapReferences = ArrayList<MapperReference>()
            val sqlReferences = ArrayList<MapperReference>()

            for (virtualFile in FileTypeIndex.getFiles(XmlFileType.INSTANCE, scope)) {
                val file = manager.findFile(virtualFile) as? XmlFile ?: continue
                val root = file.rootTag ?: continue
                if (root.name != "mapper") continue
                val namespace = root.getAttributeValue("namespace")?.trim().orEmpty()
                if (namespace.isEmpty()) continue
                filesByNamespace.getOrPut(namespace) { mutableListOf() } += file

                for (tag in root.subTags) {
                    val id = tag.getAttributeValue("id")?.trim().orEmpty()
                    if (id.isEmpty()) continue
                    when (tag.name) {
                        in STATEMENT_TAGS -> statements.getOrPut(mapperKey(namespace, id)) { mutableListOf() } += tag
                        "resultMap" -> resultMaps.getOrPut(mapperKey(namespace, id)) { mutableListOf() } += tag
                        "sql" -> sqlFragments.getOrPut(mapperKey(namespace, id)) { mutableListOf() } += tag
                        else -> continue
                    }
                    ids.getOrPut(idsKey(namespace, tag.name)) { LinkedHashSet() } += id
                }

                walkTags(root) { tag ->
                    when (tag.name) {
                        in STATEMENT_TAGS -> tag.getAttributeValue("resultMap")?.let {
                            resultMapReferences += MapperReference(tag, namespace, it)
                        }

                        "resultMap" -> tag.getAttributeValue("extends")?.let {
                            resultMapReferences += MapperReference(tag, namespace, it)
                        }

                        "association", "collection" -> tag.getAttributeValue("resultMap")?.let {
                            resultMapReferences += MapperReference(tag, namespace, it)
                        }

                        "include" -> tag.getAttributeValue("refid")?.let {
                            sqlReferences += MapperReference(tag, namespace, it)
                        }
                    }
                }
            }

            return MapperXmlIndex(
                filesByNamespace = filesByNamespace.mapValues { it.value.toList() },
                statements = statements.mapValues { it.value.toList() },
                resultMaps = resultMaps.mapValues { it.value.toList() },
                sqlFragments = sqlFragments.mapValues { it.value.toList() },
                idsByNamespaceAndTag = ids.mapValues { it.value.toSet() },
                resultMapReferences = resultMapReferences,
                sqlReferences = sqlReferences,
            )
        }

        private fun walkTags(tag: XmlTag, action: (XmlTag) -> Unit) {
            action(tag)
            for (sub in tag.subTags) {
                walkTags(sub, action)
            }
        }
    }
}

internal fun mapperKey(namespace: String, id: String): String = "$namespace.$id"

internal fun idsKey(namespace: String, tagName: String): String = "$namespace\u0000$tagName"
