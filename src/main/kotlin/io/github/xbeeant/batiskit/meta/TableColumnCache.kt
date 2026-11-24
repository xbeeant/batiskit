package io.github.xbeeant.batiskit.meta

import com.intellij.openapi.project.Project
import java.util.Collections
import java.util.WeakHashMap

/**
 * project 级表结构缓存，供补全 / CRUD 预填这类高频 EDT 调用复用。
 *
 * - key 用 [Project] 的弱引用，工程关闭后条目自动回收，不额外注册 projectService；
 * - 正结果靠 [com.intellij.database.psi.DbPsiFacade] 的 modificationCount 失效，
 *   IDE 里新增 / 刷新数据源后下一次补全自动重查；
 * - 负结果只缓存很短时间：数据源刚配置好、模型还在异步加载时，别把「暂时查不到」记成永久结论。
 */
internal object TableColumnCache {

    private const val NEGATIVE_TTL_MS = 3_000L
    private const val MAX_ENTRIES = 64

    data class Cached(val columns: List<MapperSqlRules.Column>?)

    private data class Entry(
        val stamp: Long,
        val columns: List<MapperSqlRules.Column>?,
        val at: Long,
    )

    private val entries: MutableMap<Project, MutableMap<String, Entry>> =
        Collections.synchronizedMap(WeakHashMap())

    fun get(project: Project, key: String, stamp: Long): Cached? {
        val bucket = synchronized(entries) { entries[project] } ?: return null
        val entry = synchronized(bucket) { bucket[key] } ?: return null
        if (entry.stamp != stamp) return null
        if (entry.columns == null && System.currentTimeMillis() - entry.at > NEGATIVE_TTL_MS) return null
        return Cached(entry.columns)
    }

    fun put(project: Project, key: String, stamp: Long, columns: List<MapperSqlRules.Column>?) {
        val bucket = synchronized(entries) {
            entries.getOrPut(project) { Collections.synchronizedMap(LinkedHashMap()) }
        }
        synchronized(bucket) {
            if (bucket.size >= MAX_ENTRIES) bucket.clear()
            bucket[key] = Entry(stamp, columns, System.currentTimeMillis())
        }
    }

    fun clear(project: Project) {
        synchronized(entries) { entries.remove(project) }
    }
}
