package io.github.xbeeant.batiskit.config

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.ProjectManager
import io.github.xbeeant.batiskit.meta.MapperSqlRules

/** 一张表的列结构快照：来自 Database 视图选中过的表，供字段补全离线复用 */
class TableColumnSnapshot {
    var name: String = ""
    var dataSource: String = ""
    var schema: String = ""
    var columns: MutableList<ColumnSnapshot> = mutableListOf()
}

class ColumnSnapshot {
    var name: String = ""
    var jdbcType: String = ""
    var comment: String = ""
    var primaryKey: Boolean = false
}

/**
 * Mapper 辅助功能的 IDE 级设置：导航图标开关、字段补全、CRUD 前缀规则、表名映射、
 * 以及「最近使用的数据源 / 表 / 列结构快照」。
 *
 * 这里刻意不保存任何数据库连接凭据：表结构一律来自 IDE Database 视图已加载的 DAS 元数据。
 * 默认会主动按表名查找已配置数据源（无需用户先选表），列快照只作为离线兜底与加速手段——
 * 等价于「自动连库 + 记忆设置」，但不引入 JDBC / 驱动 / 密码，也不和 IDE 已有的连接管理打架。
 */
class MapperAssistSettingsState {

    /** 侧栏图标与 Ctrl+B 增强的总开关（避免与 MybatisX 等插件出现两套图标） */
    var navigationIconsEnabled: Boolean = true

    /** 主动从 IDE 已配置的数据源按表名读列；零前置条件，用户不必先手动选表 */
    var autoDiscoverTables: Boolean = true

    /** SQL 正文与 column 属性里的表字段补全 */
    var columnCompletionEnabled: Boolean = true

    /** `#{}` 里基于 SQL 上下文的参数名补全 */
    var sqlContextCompletionEnabled: Boolean = true

    /** Alt+Enter 生成语句时按方法前缀预填 CRUD SQL；关掉后退回旧的空骨架 */
    var crudSqlEnabled: Boolean = true

    /** 前缀表，每行 `前缀1,前缀2=类型` */
    var prefixRules: String = MapperSqlRules.DEFAULT_PREFIX_RULES

    /** 手写表名映射，每行 `namespace或Mapper简单名=表名`；优先级高于自动推断 */
    var tableMappings: String = ""

    /** 最近一次使用的数据源 / schema（只读展示与自动定位用） */
    var lastDataSource: String = ""
    var lastSchema: String = ""

    /** 最近使用过的表名，最近优先 */
    var recentTables: MutableList<String> = mutableListOf()

    /** 列结构快照，最近优先；不存连接信息，只存列名 / 类型 / 注释 */
    var tableSnapshots: MutableList<TableColumnSnapshot> = mutableListOf()

    fun normalize() {
        prefixRules = MapperSqlRules.normalizePrefixRules(prefixRules)
        tableMappings = MapperSqlRules.formatMappings(MapperSqlRules.parseMappings(tableMappings))
        lastDataSource = lastDataSource.trim().take(120)
        lastSchema = lastSchema.trim().take(120)
        recentTables = recentTables.map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { name -> name.lowercase() }
            .take(MapperSqlRules.MAX_RECENT).toMutableList()
        tableSnapshots = tableSnapshots
            .map { snapshot ->
                snapshot.name = MapperSqlRules.sanitizeIdentifier(snapshot.name)
                snapshot.columns = snapshot.columns
                    .map { column ->
                        column.name = MapperSqlRules.sanitizeIdentifier(column.name)
                        column
                    }
                    .filter { it.name.isNotEmpty() }
                    .distinctBy { it.name.lowercase() }
                    .take(MapperSqlRules.MAX_COLUMNS)
                    .toMutableList()
                snapshot
            }
            .filter { it.name.isNotEmpty() }
            .distinctBy { it.name.lowercase() }
            .take(MapperSqlRules.MAX_SNAPSHOTS)
            .toMutableList()
    }
}

@Service(Service.Level.APP)
@State(
    name = "BatisKitMapperAssistSettings",
    storages = [Storage("batiskit-mapper-assist.xml")]
)
class MapperAssistSettings : PersistentStateComponent<MapperAssistSettingsState> {

    /** 保持单例：调用方持有 state 引用，loadState 只能就地拷字段 */
    @JvmField
    val state = MapperAssistSettingsState()

    override fun getState(): MapperAssistSettingsState = state

    override fun loadState(state: MapperAssistSettingsState) {
        MapperAssistSettingsState::class.java.declaredFields.filterNot { it.isSynthetic }.forEach { field ->
            field.isAccessible = true
            field.set(this.state, field.get(state))
        }
        this.state.normalize()
    }

    companion object {
        /** 没有应用容器时（纯 JVM 单测）也不炸，方便纯逻辑链路复用同一份默认值 */
        private val fallback by lazy { MapperAssistSettings() }

        fun getInstance(): MapperAssistSettings =
            ApplicationManager.getApplication()?.getService(MapperAssistSettings::class.java) ?: fallback

        /** 侧栏图标由 daemon 缓存，开关改完立刻重跑所有已打开工程的高亮（无参 restart 兼容 2024.1） */
        fun restartHighlighting() {
            val application = ApplicationManager.getApplication() ?: return
            if (application.isDisposed) return
            for (project in ProjectManager.getInstance().openProjects) {
                if (!project.isDisposed) {
                    DaemonCodeAnalyzer.getInstance(project).restart()
                }
            }
        }
    }
}
