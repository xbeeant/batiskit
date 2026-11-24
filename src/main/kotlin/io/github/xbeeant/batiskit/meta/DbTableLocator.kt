package io.github.xbeeant.batiskit.meta

import com.intellij.database.model.DasTable
import com.intellij.database.psi.DbPsiFacade
import com.intellij.database.util.DasUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import io.github.xbeeant.batiskit.config.MapperAssistSettings

/**
 * 零前置条件的表结构发现：只要 IDE Database 视图里配置了数据源，就按表名主动查出列清单。
 *
 * 不需要用户先点「生成代码 / 比对已生成代码」，也不连 JDBC、不读 application.yml、不碰连接凭据——
 * 全部来自 Database 插件已经加载好的 DAS 模型（[TableInfoReader] 同源，只是不再依赖用户手动选表）。
 *
 * 全部平台调用都包了兜底：Database 插件不可用、数据源为空、正在异步加载、单个表读取失败，
 * 一律安静返回 null，由 [TableMetadata] 退化到实体类 / resultMap / 离线快照。
 */
object DbTableLocator {

    const val MAX_DATA_SOURCES = 8
    const val MAX_TABLES_PER_SOURCE = 2_000

    /** 返回 null 表示当前拿不到实时表结构（调用方继续走其它列来源） */
    fun columns(project: Project, tableName: String): List<MapperSqlRules.Column>? {
        val wanted = MapperSqlRules.sanitizeIdentifier(tableName)
        if (wanted.isEmpty()) return null
        val facade = runCatching { DbPsiFacade.getInstance(project) }.getOrNull() ?: return null
        val stamp = facade.modificationCount
        val key = wanted.lowercase()
        TableColumnCache.get(project, key, stamp)?.let { return it.columns }

        val sources = runCatching { facade.dataSources }.getOrNull().orEmpty()
        val found = if (sources.isEmpty()) null else discover(sources, wanted)
        TableColumnCache.put(project, key, stamp, found)
        return found
    }

    private fun discover(
        sources: List<com.intellij.database.psi.DbDataSource>,
        wanted: String,
    ): List<MapperSqlRules.Column>? {
        // 上次用过的数据源排最前：同名表在多库里存在时优先取用户最可能想要的那份
        val preferred = MapperAssistSettings.getInstance().state.lastDataSource
        val ordered = if (preferred.isBlank()) sources else {
            sources.sortedBy { if (it.name == preferred) 0 else 1 }
        }
        var best: List<MapperSqlRules.Column>? = null
        var bestScore = -1
        for (source in ordered.take(MAX_DATA_SOURCES)) {
            val tables = runCatching { DasUtil.getTables(source) }.getOrNull() ?: continue
            var scanned = 0
            for (table in tables) {
                if (++scanned > MAX_TABLES_PER_SOURCE) break
                ProgressManager.checkCanceled()
                val score = MapperSqlRules.tableMatchScore(table.name, wanted)
                if (score < 0 || score <= bestScore) continue
                val columns = runCatching { readColumns(table, source.name) }.getOrNull().orEmpty()
                if (columns.isEmpty()) continue
                best = columns
                bestScore = score
                if (score == 100) break
            }
            if (bestScore == 100) break
        }
        return best
    }

    private fun readColumns(table: DasTable, dataSource: String): List<MapperSqlRules.Column> {
        val primaryKeys = runCatching {
            DasUtil.getPrimaryKey(table)?.columnsRef?.names()?.toSet()
        }.getOrNull().orEmpty()
        return DasUtil.getColumns(table)
            .take(MapperSqlRules.MAX_COLUMNS)
            .mapNotNull { column ->
                val name = MapperSqlRules.sanitizeIdentifier(column.name)
                if (name.isEmpty()) return@mapNotNull null
                MapperSqlRules.Column(
                    name = name,
                    jdbcType = runCatching { column.dasType.specification.substringBefore('(').trim() }
                        .getOrNull().orEmpty().take(48),
                    comment = runCatching { column.comment.orEmpty().trim() }.getOrNull().orEmpty().take(120),
                    primaryKey = primaryKeys.any { it.equals(name, ignoreCase = true) },
                    source = dataSource
                )
            }
    }

    /** 设置里关掉自动发现或手动「重新读取」时清缓存 */
    fun invalidate(project: Project) = TableColumnCache.clear(project)
}
