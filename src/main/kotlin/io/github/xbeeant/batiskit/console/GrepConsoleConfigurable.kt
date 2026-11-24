package io.github.xbeeant.batiskit.console

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.ColorPicker
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.xbeeant.batiskit.ui.TableSearchSupport
import io.github.xbeeant.batiskit.ui.UiTokens
import io.github.xbeeant.batiskit.ui.components.ColorSpecField
import io.github.xbeeant.batiskit.ui.components.ColorSwatchIcon
import io.github.xbeeant.batiskit.ui.components.ColorUsage
import io.github.xbeeant.batiskit.ui.components.ConsoleColorPreviewPanel
import io.github.xbeeant.batiskit.ui.components.FixedHeightRow
import io.github.xbeeant.batiskit.ui.components.fixedHeight
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultCellEditor
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.UIManager
import javax.swing.border.TitledBorder
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Settings → Tools → Batis Kit → Grep Console。
 *
 * 设置是 IDE 级的：一套颜色 / 规则在所有工程里生效，不用每个项目配一遍。
 * 颜色写 `浅色hex|深色hex`，只填一个时亮暗主题共用；每一项都能点色块选色，
 * 下方预览按当前表单值实时渲染浅 / 深两套控制台效果。
 */
class GrepConsoleConfigurable(private val project: Project) : SearchableConfigurable {

    override fun getId(): String = "io.github.xbeeant.batiskit.console.settings"

    private val settings = GrepConsoleSettings.getInstance()

    private val enabledBox = JBCheckBox("启用 Grep Console（着色、过滤、日志收集）")
    private val levelBox = JBCheckBox("按级别给整行着色（ERROR / WARN / INFO / DEBUG / TRACE）")
    private val keywordBox = JBCheckBox("高亮自定义关键词")
    private val captureBox = JBCheckBox("自动捕获 Run / Debug 控制台输出到工具窗口")
    private val linkBox = JBCheckBox("控制台里的类 / 方法 / 相对路径可点击导航（URL 与绝对路径由平台自带）")
    private val maxEntriesField = JBTextField(6)
    private val modeBox = ComboBox(ConsoleFilterMode.entries.map { it.label }.toTypedArray())
    private val patternField = JBTextField()
    private val ruleFilterBox = JBCheckBox("启用过滤规则（表达式规则链，可移除 / 改写输出；丢弃的行不可恢复）")
    private val colorFields = mutableMapOf<LogLevel, ColorSpecField>()
    private val previewPanel = ConsoleColorPreviewPanel()
    private val matchColorField = ColorSpecField(
        usage = ColorUsage.BACKGROUND,
        allowEmpty = false,
        defaultSpec = GrepHighlightColor.DEFAULT,
        onChange = ::refreshPreview,
    )
    private val rulesModel = RulesTableModel()
    private val rulesTable = JBTable(rulesModel)
    private val filterRulesModel = FilterRulesTableModel()
    private val filterRulesTable = JBTable(filterRulesModel)
    private val rulesSearch = TableSearchSupport(rulesTable, rulesModel)
    private val filterRulesSearch = TableSearchSupport(filterRulesTable, filterRulesModel)

    private var panel: JComponent? = null

    override fun getDisplayName(): String = "Grep Console"

    override fun createComponent(): JComponent {
        val general = group("常规") {
            add(fullRow(enabledBox))
            add(fullRow(levelBox))
            add(fullRow(keywordBox))
            add(fullRow(captureBox))
            add(fullRow(linkBox))
            val rows = JPanel(GridBagLayout())
            val gbc = GridBagConstraints().apply { anchor = GridBagConstraints.WEST; insets = JBUI.insets(2, 0) }
            rows.add(JBLabel("最多保留行数："), gbc)
            rows.add(maxEntriesField, gbc)
            rows.add(Box.createHorizontalStrut(JBUI.scale(16)), gbc)
            rows.add(JBLabel("控制台过滤："), gbc)
            rows.add(modeBox, gbc)
            add(fullRow(rows))
            add(fullRow(JBLabel("过滤关键词（空格分隔全部命中，或 /正则/；仅上面两种关键词模式用）")))
            add(fullRow(patternField))
            add(fullRow(JBLabel("过滤会丢弃控制台里的行，关掉后不会恢复；排查问题建议用工具窗口的「只看 ERROR」。")))
            add(fullRow(JBLabel("Grep 子控制台的表达式预设与最近使用记录在这里不展示（在子控制台顶部管理），不会被本页 Apply 清掉。")))
        }

        val colors = group("颜色（点色块选色；每行右侧是浅 / 深色主题下的实时预览）") {
            val defaults = GrepConsoleSettingsState.defaultLevelColors()
            listOf(LogLevel.ERROR, LogLevel.WARN, LogLevel.INFO, LogLevel.DEBUG, LogLevel.TRACE).forEach { level ->
                val field = ColorSpecField(
                    usage = ColorUsage.FOREGROUND,
                    defaultSpec = defaults[level.name],
                    onChange = ::refreshPreview,
                )
                colorFields[level] = field
                val row = JPanel(BorderLayout(JBUI.scale(8), 0))
                row.add(JBLabel(level.label), BorderLayout.WEST)
                row.add(field, BorderLayout.CENTER)
                add(fullRow(row))
            }
            val matchRow = JPanel(BorderLayout(JBUI.scale(8), 0))
            matchRow.add(JBLabel("命中片段底色"), BorderLayout.WEST)
            matchRow.add(matchColorField, BorderLayout.CENTER)
            add(fullRow(matchRow))
            add(fullRow(JBLabel("效果预览（浅色 / 深色主题各一份，与控制台实际着色一致）").apply {
                foreground = UiTokens.textSecondary
            }))
            add(previewPanel.fixedHeight())
            add(fullRow(JBLabel("「浅色」「深色」分别选对应主题的颜色；「自动」由浅色派生深色；Grep 子控制台命中底色改完立即生效。")))
        }

        rulesTable.rowHeight = JBUI.scale(24)
        rulesTable.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        rulesModel.setColumnWidths(rulesTable)
        val colorRenderer = ColorCellRenderer()
        rulesTable.columnModel.getColumn(RULE_COLOR_COLUMN).cellRenderer = colorRenderer
        rulesTable.columnModel.getColumn(RULE_BACKGROUND_COLUMN).cellRenderer = colorRenderer
        rulesTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 2 || event.button != MouseEvent.BUTTON1) return
                val column = rulesTable.convertColumnIndexToModel(rulesTable.columnAtPoint(event.point))
                if (column != RULE_COLOR_COLUMN && column != RULE_BACKGROUND_COLUMN) return
                val viewRow = rulesTable.rowAtPoint(event.point)
                if (viewRow < 0) return
                pickRuleColor(rulesTable.convertRowIndexToModel(viewRow), column)
            }
        })
        val tablePane = JBScrollPane(rulesTable)
        tablePane.preferredSize = JBUI.size(640, 140)
        val buttons = JPanel()
        buttons.add(JButton("新增规则").apply { addActionListener { rulesModel.add(); } })
        buttons.add(JButton("删除选中").apply {
            addActionListener {
                val row = rulesTable.selectedRow
                // 装了 RowSorter 之后 selectedRow 是视图索引，删模型数据前必须转回去
                if (row >= 0) rulesModel.remove(rulesTable.convertRowIndexToModel(row))
            }
        })
        buttons.add(JButton("恢复默认").apply { addActionListener { rulesModel.reset(); } })
        buttons.add(JButton("选色…").apply {
            toolTipText = "为选中规则的颜色 / 背景色打开颜色选择器（也可双击单元格）"
            addActionListener { pickSelectedRuleColor() }
        })
        val rules = group("关键词高亮规则（先声明的优先）") {
            val searchRow = JPanel(BorderLayout(JBUI.scale(8), 0))
            searchRow.add(rulesSearch.field, BorderLayout.CENTER)
            searchRow.add(rulesSearch.countLabel, BorderLayout.EAST)
            add(fullRow(searchRow))
            add(tablePane.fixedHeight())
            add(fullRow(buttons))
            add(fullRow(JBLabel("表达式可以是字面量，也可以勾「正则」；颜色列带色块预览，双击单元格或选中后点「选色…」即可改色；背景色可留空；勾「整行」要求整行匹配并给整行着色。")))
            add(fullRow(JBLabel("搜索与列头排序只影响显示；Apply 仍按表格里的全部规则保存，顺序不受排序影响。")))
        }

        filterRulesTable.rowHeight = JBUI.scale(24)
        filterRulesTable.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
        filterRulesModel.setColumnWidths(filterRulesTable)
        val filterTablePane = JBScrollPane(filterRulesTable)
        filterTablePane.preferredSize = JBUI.size(640, 170)
        val filterButtons = JPanel()
        filterButtons.add(JButton("新增规则").apply { addActionListener { filterRulesModel.add() } })
        filterButtons.add(JButton("删除选中").apply {
            addActionListener {
                val row = filterRulesTable.selectedRow
                if (row >= 0) filterRulesModel.remove(filterRulesTable.convertRowIndexToModel(row))
            }
        })
        filterButtons.add(JButton("恢复示例").apply { addActionListener { filterRulesModel.reset() } })
        val filterRules = group("过滤规则（写进控制台之前生效，语义对齐 Grep Console 插件）") {
            add(fullRow(ruleFilterBox))
            val searchRow = JPanel(BorderLayout(JBUI.scale(8), 0))
            searchRow.add(filterRulesSearch.field, BorderLayout.CENTER)
            searchRow.add(filterRulesSearch.countLabel, BorderLayout.EAST)
            add(fullRow(searchRow))
            add(filterTablePane.fixedHeight())
            add(fullRow(filterButtons))
            add(fullRow(JBLabel("规则按顺序评估，命中后默认停止（勾「继续匹配」才会继续）；「移除，除非前面规则已命中」配合一条「无动作」规则即可只保留目标行。")))
            add(fullRow(JBLabel("「替换为格式化模板」把命中文本按模板改写：$0 整个命中，$1..$9 捕获组，模板留空即删除命中片段。")))
            add(fullRow(JBLabel("搜索与列头排序只影响显示；规则实际执行顺序仍是表格里的原始声明顺序。")))
        }

        val root = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        root.add(general)
        root.add(colors)
        root.add(rules)
        root.add(filterRules)
        root.add(Box.createVerticalGlue())
        levelBox.addActionListener { refreshPreview() }
        keywordBox.addActionListener { refreshPreview() }
        rulesModel.addTableModelListener { refreshPreview() }
        refreshPreview()
        return JBScrollPane(root).also { panel = it }
    }

    override fun isModified(): Boolean = fingerprint(uiState()) != fingerprint(settings.config)

    override fun apply() {
        val maxEntries = maxEntriesField.text.trim().toIntOrNull()
        if (maxEntries == null || maxEntries !in 100..100000) {
            throw com.intellij.openapi.options.ConfigurationException("保留行数必须是 100 ~ 100000 之间的整数")
        }
        val ui = uiState()
        val state = settings.config
        state.enabled = ui.enabled
        state.colorizeLevel = ui.colorizeLevel
        state.highlightKeywords = ui.highlightKeywords
        state.captureEnabled = ui.captureEnabled
        state.linkNavigation = ui.linkNavigation
        state.maxEntries = ui.maxEntries
        state.filterMode = ui.filterMode
        state.filterPattern = ui.filterPattern
        state.ruleFilterEnabled = ui.ruleFilterEnabled
        state.levelColors = LinkedHashMap(ui.levelColors)
        state.rules = ui.rules.map { it.copy() }.toMutableList()
        state.filterRules = ui.filterRules.map { it.copy() }.toMutableList()
        state.expressionPresets = ui.expressionPresets.map { it.copy() }.toMutableList()
        state.recentExpressions = ui.recentExpressions.toMutableList()
        state.matchHighlightColor = ui.matchHighlightColor
        state.normalize()
        GrepConsoleService.getInstance(project).settingsChanged()
        reset()
    }

    override fun reset() {
        val state = settings.config
        enabledBox.isSelected = state.enabled
        levelBox.isSelected = state.colorizeLevel
        keywordBox.isSelected = state.highlightKeywords
        captureBox.isSelected = state.captureEnabled
        linkBox.isSelected = state.linkNavigation
        maxEntriesField.text = state.maxEntries.toString()
        modeBox.selectedItem = state.mode().label
        patternField.text = state.filterPattern
        ruleFilterBox.isSelected = state.ruleFilterEnabled
        colorFields.forEach { (level, field) -> field.spec = state.levelColors[level.name].orEmpty() }
        matchColorField.spec = state.matchHighlightColor
        rulesModel.load(state.rules)
        filterRulesModel.load(state.filterRules)
        refreshPreview()
    }

    override fun disposeUIResources() {
        panel = null
    }

    /** 表单任何一处颜色 / 规则变动后重绘预览：与控制台共用 LogHighlighter，所见即所得 */
    private fun refreshPreview() {
        previewPanel.update(
            ConsoleColorPreviewPanel.PreviewState(
                levelColors = colorFields.map { (level, field) -> level to field.spec }.toMap(),
                rules = rulesModel.rules(),
                matchColor = matchColorField.spec,
                colorizeLevel = levelBox.isSelected,
                highlightKeywords = keywordBox.isSelected,
            )
        )
    }

    /** 选中单元格不是颜色列时，默认改「颜色」列，免得按钮点了没反应 */
    private fun pickSelectedRuleColor() {
        val viewRow = rulesTable.selectedRow
        if (viewRow < 0) return
        val viewColumn = rulesTable.selectedColumn
        val converted = if (viewColumn >= 0) rulesTable.convertColumnIndexToModel(viewColumn) else -1
        val modelColumn = if (converted == RULE_COLOR_COLUMN || converted == RULE_BACKGROUND_COLUMN) {
            converted
        } else {
            RULE_COLOR_COLUMN
        }
        pickRuleColor(rulesTable.convertRowIndexToModel(viewRow), modelColumn)
    }

    /** 色板选的是当前 IDE 主题对应的那一份，另一份保留 / 自动派生 */
    private fun pickRuleColor(row: Int, column: Int) {
        if (row < 0 || row >= rulesModel.rowCount) return
        val current = rulesModel.getValueAt(row, column) as? String ?: ""
        val title = if (column == RULE_COLOR_COLUMN) "高亮前景色" else "高亮背景色"
        val initial = ColorSpecs.color(current, isDarkTheme()) ?: Color(0xFFE9A8)
        val picked = ColorPicker.showDialog(rulesTable, title, initial, true, null, true) ?: return
        rulesModel.setValueAt(ColorSpecs.withThemeColor(current, picked, isDarkTheme()), row, column)
    }

    private fun uiState(): GrepConsoleSettingsState = GrepConsoleSettingsState().apply {
        enabled = enabledBox.isSelected
        colorizeLevel = levelBox.isSelected
        highlightKeywords = keywordBox.isSelected
        captureEnabled = captureBox.isSelected
        linkNavigation = linkBox.isSelected
        maxEntries = maxEntriesField.text.trim().toIntOrNull() ?: 2000
        filterMode = ConsoleFilterMode.entries.firstOrNull { it.label == modeBox.selectedItem }?.name
            ?: ConsoleFilterMode.OFF.name
        filterPattern = patternField.text.trim()
        ruleFilterEnabled = ruleFilterBox.isSelected
        levelColors = colorFields.map { (level, field) -> level.name to field.spec }
            .toMap(LinkedHashMap())
        rules = rulesModel.rules().toMutableList()
        filterRules = filterRulesModel.rules().toMutableList()
        // 运行时在子控制台里维护，本页不编辑但要原样带回去，否则一次 Apply 就把预设清空了
        expressionPresets = settings.config.expressionPresets.map { it.copy() }.toMutableList()
        recentExpressions = settings.config.recentExpressions.toMutableList()
        // 与 state.normalize() 同一套规范化，否则「输入无 # / 小写」会让 isModified 一直为真
        matchHighlightColor = GrepHighlightColor.normalize(matchColorField.spec)
    }

    /** 比较时用规范化后的指纹，避免 hex 大小写 / 顺序差异导致「总是未保存」 */
    private fun fingerprint(state: GrepConsoleSettingsState): String = buildString {
        append(state.enabled).append(state.colorizeLevel).append(state.highlightKeywords)
        append(state.captureEnabled).append(state.linkNavigation).append(state.maxEntries)
        append(state.filterMode).append(state.filterPattern.trim())
        append(state.ruleFilterEnabled)
        LogLevel.entries.forEach {
            append(it.name).append('=').append(colorFingerprint(state.levelColors[it.name])).append(';')
        }
        append("match=").append(colorFingerprint(state.matchHighlightColor)).append(';')
        state.rules.forEach {
            append(it.enabled).append(it.pattern.trim()).append(it.regex).append(it.caseSensitive)
                .append(it.wholeLine)
                .append(colorFingerprint(it.color)).append(colorFingerprint(it.background))
                .append(it.bold).append(it.italic).append(';')
        }
        state.filterRules.forEach {
            append(it.enabled).append(it.pattern.trim()).append(it.unless.trim())
                .append(it.regex).append(it.caseSensitive).append(it.wholeLine)
                .append(it.action).append(it.replacement).append(it.continueMatching).append(';')
        }
    }

    /** 颜色比较前统一规范化，避免大小写 / 单段与双段写法让设置页一直显示未保存 */
    private fun colorFingerprint(spec: String?): String {
        val trimmed = spec?.trim().orEmpty()
        return if (ColorSpecs.isInvalid(trimmed)) "!$trimmed!" else ColorSpecs.normalize(trimmed).lowercase()
    }

    /** 分组整体也固定高度，否则 BoxLayout 会把组与组之间的距离拉开 */
    private fun group(title: String, content: JPanel.() -> Unit): JComponent = FixedHeightRow(
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = TitledBorder(title)
            alignmentX = JComponent.LEFT_ALIGNMENT
            content()
        }
    )

    /** 固定高度行：多余空间交给末尾的 glue，而不是摊到每一行 */
    private fun fullRow(component: JComponent): JComponent = FixedHeightRow(component)

    /** 规则表：直接改单元格，不弹二级对话框 */
    private class RulesTableModel : AbstractTableModel() {

        private val columns = arrayOf("启用", "表达式 / 正则", "正则", "区分大小写", "整行", "颜色", "背景色", "加粗", "斜体")
        private val rows: MutableList<HighlightRule> = GrepConsoleSettingsState.defaultRules()

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            0, 2, 3, 4, 7, 8 -> Boolean::class.javaObjectType
            else -> String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val rule = rows[rowIndex]
            return when (columnIndex) {
                0 -> rule.enabled
                1 -> rule.pattern
                2 -> rule.regex
                3 -> rule.caseSensitive
                4 -> rule.wholeLine
                5 -> rule.color
                6 -> rule.background
                7 -> rule.bold
                8 -> rule.italic
                else -> ""
            }
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            val rule = rows[rowIndex]
            when (columnIndex) {
                0 -> rule.enabled = value as? Boolean ?: return
                1 -> rule.pattern = value as? String ?: return
                2 -> rule.regex = value as? Boolean ?: return
                3 -> rule.caseSensitive = value as? Boolean ?: return
                4 -> rule.wholeLine = value as? Boolean ?: return
                5 -> rule.color = value as? String ?: return
                6 -> rule.background = value as? String ?: return
                7 -> rule.bold = value as? Boolean ?: return
                8 -> rule.italic = value as? Boolean ?: return
            }
            fireTableCellUpdated(rowIndex, columnIndex)
        }

        fun add() {
            rows += HighlightRule(pattern = "新关键词")
            fireTableRowsInserted(rows.lastIndex, rows.lastIndex)
        }

        fun remove(index: Int) {
            rows.removeAt(index)
            fireTableRowsDeleted(index, index)
        }

        fun reset() {
            load(GrepConsoleSettingsState.defaultRules())
        }

        fun load(source: List<HighlightRule>) {
            rows.clear()
            rows += source.map { it.copy() }
            fireTableDataChanged()
        }

        fun rules(): List<HighlightRule> = rows.map { it.copy() }

        fun setColumnWidths(table: JTable) {
            val widths = intArrayOf(50, 200, 50, 90, 50, 130, 110, 50, 50)
            widths.forEachIndexed { index, width ->
                table.columnModel.getColumn(index).preferredWidth = JBUI.scale(width)
            }
        }
    }

    /**
     * 过滤规则表（对照原版插件的 Filtering 表）：
     * 启用 / 表达式 / Unless / 正则 / 整行 / 大小写 / 动作 / 格式化模板 / 继续匹配。
     */
    private class FilterRulesTableModel : AbstractTableModel() {

        private val columns = arrayOf(
            "启用", "表达式 / 正则", "Unless（例外）", "正则", "整行", "区分大小写", "动作", "格式化模板", "继续匹配",
        )
        private val rows: MutableList<FilterRule> = FilterRule.defaultRules()

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            0, 3, 4, 5, 8 -> Boolean::class.javaObjectType
            else -> String::class.java
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = true

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val rule = rows[rowIndex]
            return when (columnIndex) {
                0 -> rule.enabled
                1 -> rule.pattern
                2 -> rule.unless
                3 -> rule.regex
                4 -> rule.wholeLine
                5 -> rule.caseSensitive
                6 -> rule.actionType().label
                7 -> rule.replacement
                8 -> rule.continueMatching
                else -> ""
            }
        }

        override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
            val rule = rows[rowIndex]
            when (columnIndex) {
                0 -> rule.enabled = value as? Boolean ?: return
                1 -> rule.pattern = value as? String ?: return
                2 -> rule.unless = value as? String ?: return
                3 -> rule.regex = value as? Boolean ?: return
                4 -> rule.wholeLine = value as? Boolean ?: return
                5 -> rule.caseSensitive = value as? Boolean ?: return
                6 -> rule.action = FilterAction.entries.firstOrNull { it.label == value as? String }?.name
                    ?: FilterAction.REMOVE.name
                7 -> rule.replacement = value as? String ?: return
                8 -> rule.continueMatching = value as? Boolean ?: return
            }
            fireTableCellUpdated(rowIndex, columnIndex)
        }

        fun actionComboBox(): JComboBox<String> = JComboBox(FilterAction.entries.map { it.label }.toTypedArray())

        fun add() {
            rows += FilterRule(pattern = ".*DEBUG.*", regex = true, wholeLine = true)
            fireTableRowsInserted(rows.lastIndex, rows.lastIndex)
        }

        fun remove(index: Int) {
            rows.removeAt(index)
            fireTableRowsDeleted(index, index)
        }

        fun reset() {
            load(FilterRule.defaultRules())
        }

        fun load(source: List<FilterRule>) {
            rows.clear()
            rows += source.map { it.copy() }
            fireTableDataChanged()
        }

        fun rules(): List<FilterRule> = rows.map { it.copy() }

        fun setColumnWidths(table: JTable) {
            val widths = intArrayOf(50, 170, 140, 45, 45, 85, 190, 110, 70)
            widths.forEachIndexed { index, width ->
                table.columnModel.getColumn(index).preferredWidth = JBUI.scale(width)
            }
            table.columnModel.getColumn(6).cellEditor = DefaultCellEditor(actionComboBox())
        }
    }

    /** 颜色列：色块 + hex 文本并列，文字仍由 Swing 画，不会被色块盖住 */
    private class ColorCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int,
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val spec = value as? String
            icon = ColorSwatchIcon(ColorSpecs.color(spec, isDarkTheme()))
            iconTextGap = 6
            toolTipText = if (ColorSpecs.isInvalid(spec)) {
                "格式应为 #RRGGBB|#RRGGBB，当前值不会生效"
            } else {
                "双击打开颜色选择器；也可选中后点「选色…」"
            }
            return this
        }
    }

    companion object {
        private const val RULE_COLOR_COLUMN = 5
        private const val RULE_BACKGROUND_COLUMN = 6

        /** 不依赖 Application 服务：直接看当前 LAF 的面板底色，纯 Swing 测试里也能用 */
        private fun isDarkTheme(): Boolean {
            val background = UIManager.getColor("Panel.background") ?: return false
            return (background.red * 299 + background.green * 587 + background.blue * 114) / 1000 < 128
        }
    }
}
