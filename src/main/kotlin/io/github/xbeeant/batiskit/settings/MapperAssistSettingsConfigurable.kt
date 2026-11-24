package io.github.xbeeant.batiskit.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.xbeeant.batiskit.config.MapperAssistSettings
import io.github.xbeeant.batiskit.meta.DbTableLocator
import io.github.xbeeant.batiskit.meta.MapperSqlRules
import io.github.xbeeant.batiskit.ui.components.FixedHeightRow
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings → Tools → Batis Kit → Mapper 辅助。
 *
 * 集中管理：导航图标总开关、表字段补全、SQL 上下文参数补全、CRUD 前缀规则、
 * 手写表名映射，以及「最近使用的数据源 / 表 / 列结构快照」的记忆状态。
 */
class MapperAssistSettingsConfigurable : SearchableConfigurable {

    override fun getId(): String = "io.github.xbeeant.batiskit.mapper.assist.settings"

    private val settings = MapperAssistSettings.getInstance()

    private val navigationBox = JBCheckBox("启用 Mapper 侧栏图标与 Ctrl+B 跳转增强（与其它 MyBatis 插件重复图标时关掉）")
    private val discoverBox = JBCheckBox("自动从 IDE 已配置的数据源按表名读取列结构（无需先手动选表）")
    private val columnBox = JBCheckBox("在 SQL 正文与 column 属性里补全表字段")
    private val contextBox = JBCheckBox("在 #{ } 里按 SQL 上下文补全参数名")
    private val crudBox = JBCheckBox("Alt+Enter 生成语句时按方法前缀预填 CRUD SQL")

    private val prefixArea = JBTextArea(8, 40).apply { lineWrap = false }
    private val mappingArea = JBTextArea(5, 40).apply { lineWrap = false }
    private val recentLabel = JBLabel()

    private var panel: JComponent? = null

    override fun getDisplayName(): String = "Mapper 辅助"

    override fun createComponent(): JComponent {
        val root = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }
        listOf(navigationBox, discoverBox, columnBox, contextBox, crudBox).forEach { root.add(fullRow(it)) }

        root.add(Box.createVerticalStrut(JBUI.scale(10)))
        val prefixTitle = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JBLabel("方法名前缀规则（每行：前缀1,前缀2=select/insert/update/delete）"), BorderLayout.CENTER)
            add(JButton("恢复默认前缀").apply {
                addActionListener { prefixArea.text = MapperSqlRules.DEFAULT_PREFIX_RULES }
            }, BorderLayout.EAST)
        }
        root.add(fullRow(prefixTitle))
        root.add(fullRow(JBScrollPane(prefixArea)))

        root.add(Box.createVerticalStrut(JBUI.scale(10)))
        root.add(fullRow(JBLabel("表名映射（每行：namespace 或 Mapper 简单名 = 表名；优先级高于自动推断，支持 # 注释）")))
        root.add(fullRow(JBScrollPane(mappingArea)))

        root.add(Box.createVerticalStrut(JBUI.scale(10)))
        root.add(fullRow(recentLabel))
        root.add(fullRow(JPanel(BorderLayout()).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JButton("清空已记忆的表结构").apply {
                addActionListener {
                    settings.state.tableSnapshots.clear()
                    settings.state.recentTables.clear()
                    settings.state.lastDataSource = ""
                    settings.state.lastSchema = ""
                    ProjectManager.getInstance().openProjects.forEach(DbTableLocator::invalidate)
                    refreshRecent()
                }
            }, BorderLayout.WEST)
            add(JButton("重新读取数据源表结构").apply {
                addActionListener {
                    ProjectManager.getInstance().openProjects.forEach(DbTableLocator::invalidate)
                }
            }, BorderLayout.EAST)
        }))
        root.add(Box.createVerticalStrut(JBUI.scale(8)))
        root.add(
            fullRow(
                JBLabel("只要 IDE Database 视图里配置了数据源，就会按表名自动查找列结构，首次使用无需任何手动选表动作。")
            )
        )
        root.add(
            fullRow(
                JBLabel("没有数据源时自动退回工程实体类 / 当前 resultMap / 已记忆的列快照；不连 JDBC，不保存账号密码。")
            )
        )
        root.add(
            fullRow(
                JBLabel("关闭导航总开关后侧栏图标与 Mapper 跳转增强一并停用，Ctrl+B 回落 IDE 默认行为；改动对所有工程生效。")
            )
        )
        root.add(Box.createVerticalGlue())
        reset()
        return root.also { panel = it }
    }

    override fun isModified(): Boolean {
        val state = settings.state
        return navigationBox.isSelected != state.navigationIconsEnabled ||
            discoverBox.isSelected != state.autoDiscoverTables ||
            columnBox.isSelected != state.columnCompletionEnabled ||
            contextBox.isSelected != state.sqlContextCompletionEnabled ||
            crudBox.isSelected != state.crudSqlEnabled ||
            MapperSqlRules.normalizePrefixRules(prefixArea.text) != state.prefixRules ||
            MapperSqlRules.formatMappings(MapperSqlRules.parseMappings(mappingArea.text)) != state.tableMappings
    }

    override fun apply() {
        val state = settings.state
        val navigationChanged = navigationBox.isSelected != state.navigationIconsEnabled
        val discoverChanged = discoverBox.isSelected != state.autoDiscoverTables
        state.navigationIconsEnabled = navigationBox.isSelected
        state.autoDiscoverTables = discoverBox.isSelected
        state.columnCompletionEnabled = columnBox.isSelected
        state.sqlContextCompletionEnabled = contextBox.isSelected
        state.crudSqlEnabled = crudBox.isSelected
        state.prefixRules = prefixArea.text
        state.tableMappings = mappingArea.text
        state.normalize()
        if (navigationChanged) {
            // 侧栏图标由 daemon 缓存，改完立刻重跑所有已打开工程的高亮
            MapperAssistSettings.restartHighlighting()
        }
        if (discoverChanged) {
            ProjectManager.getInstance().openProjects.forEach(DbTableLocator::invalidate)
        }
        reset()
    }

    override fun reset() {
        val state = settings.state
        navigationBox.isSelected = state.navigationIconsEnabled
        discoverBox.isSelected = state.autoDiscoverTables
        columnBox.isSelected = state.columnCompletionEnabled
        contextBox.isSelected = state.sqlContextCompletionEnabled
        crudBox.isSelected = state.crudSqlEnabled
        prefixArea.text = state.prefixRules
        mappingArea.text = state.tableMappings
        refreshRecent()
    }

    private fun refreshRecent() {
        val state = settings.state
        val source = listOfNotNull(
            state.lastDataSource.takeIf { it.isNotBlank() },
            state.lastSchema.takeIf { it.isNotBlank() }
        ).joinToString(" / ").ifBlank { "尚未记录（不影响自动发现）" }
        val tables = state.recentTables.take(5).joinToString("、").ifBlank { "无" }
        recentLabel.text = "最近数据源 / schema（仅用于优先排序）：$source　·　最近使用的表：$tables　·　离线快照 ${state.tableSnapshots.size} 张"
    }

    override fun disposeUIResources() {
        panel = null
    }

    /** 固定高度行：多余空间交给末尾的 glue，而不是摊到每一行 */
    private fun fullRow(component: JComponent): JComponent = FixedHeightRow(component)
}
