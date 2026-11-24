package io.github.xbeeant.batiskit.log.ui

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.xbeeant.batiskit.log.MapperStatementNavigator
import io.github.xbeeant.batiskit.log.MyBatisLogBundle.message
import io.github.xbeeant.batiskit.log.MyBatisLogClipboard
import io.github.xbeeant.batiskit.log.MyBatisLogConfig
import io.github.xbeeant.batiskit.log.MyBatisLogEntry
import io.github.xbeeant.batiskit.log.MyBatisLogService
import io.github.xbeeant.batiskit.log.splitFilterKeywords
import io.github.xbeeant.batiskit.support.SensitiveData
import io.github.xbeeant.batiskit.ui.ToolWindowShell
import io.github.xbeeant.batiskit.ui.UiText
import io.github.xbeeant.batiskit.ui.UiTokens
import io.github.xbeeant.batiskit.ui.UiTokens.Density
import io.github.xbeeant.batiskit.ui.components.ContentSwitcher
import io.github.xbeeant.batiskit.ui.components.FilterChipGroup
import io.github.xbeeant.batiskit.ui.components.MethodBadge
import io.github.xbeeant.batiskit.ui.components.SearchField
import io.github.xbeeant.batiskit.ui.components.StatusBarPanel
import io.github.xbeeant.batiskit.util.SearchQuery
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants

/**
 * 「MyBatis Log」工具窗口：捕获页是语句列表 + 着色后的 SQL 预览，文本还原页用于离线粘贴日志。
 *
 * 交互：双击复制、右键复制原始日志 / EXPLAIN / 参数 / 跳转到 Mapper XML 语句、
 * 搜索（AND 关键字 / `-排除` / `/正则/`）、列表排序、格式化开关、暂停捕获、导入剪贴板日志。
 */
class MyBatisLogPanel(private val project: Project) : Disposable {

    private val service = MyBatisLogService.getInstance(project)

    private val settings get() = service.settings

    private val search = SearchField(message("search.placeholder"), ::applyFilter)

    /** 语句类型 chip：按 SQL 首关键字归类，其它类型单列一项避免被静默过滤掉 */
    private val statementChips = FilterChipGroup(STATEMENT_TYPES, STATEMENT_TYPES) { applyFilter() }

    private val model = CollectionListModel<MyBatisLogEntry>()

    private val list = JBList(model)

    private val preview = SqlPreviewPane()

    private val formatBox = JBCheckBox(message("check.format"), settings.formatSql)

    private val pauseBox = JBCheckBox(message("check.pause"), service.paused)

    private val sortBox = ComboBox(SORT_LABELS)

    private lateinit var shell: ToolWindowShell
    private lateinit var switcher: ContentSwitcher
    private lateinit var tabs: JBTabbedPane
    private lateinit var split: JSplitPane

    /** 全量条目（过滤前），与 [model] 的关系由 [applyFilter] 维护 */
    private val all = mutableListOf<MyBatisLogEntry>()

    /** 当前过滤结果，复制全部时用 */
    private var visible: List<MyBatisLogEntry> = emptyList()

    /** 服务回调（EDT），必须在 init 之前初始化 */
    private val listener = object : MyBatisLogService.Listener {
        override fun entriesAdded(entries: List<MyBatisLogEntry>) {
            all.addAll(entries)
            // 与服务端的保留条数保持一致，长时间运行不会把列表撑爆
            val limit = settings.maxEntries.coerceAtLeast(20)
            while (all.size > limit) {
                all.removeAt(0)
            }
            applyFilter(scrollToEnd = true)
        }

        override fun entriesUpdated() {
            list.repaint()
            updatePreview()
        }

        override fun cleared() {
            // 清空与「撤销清空」走同一条路径：重新读服务快照
            all.clear()
            all.addAll(service.snapshot())
            applyFilter()
        }
    }

    val component: JComponent = createComponent()

    init {
        service.addListener(listener, this)
        all.addAll(service.snapshot())
        applyFilter()
    }

    override fun dispose() {
        // 监听随 Disposable 一起摘掉（见 MyBatisLogService.addListener）
    }

    // ------------------------------------------------------------------ UI

    private fun createComponent(): JComponent {
        search.toolTipText = message("search.tip")

        formatBox.toolTipText = message("check.format.tip")
        formatBox.addActionListener {
            // 工具窗口里的开关视为临时覆盖：只改本地勾选状态，不回写全局设置
            updatePreview()
        }
        pauseBox.toolTipText = message("check.pause.tip")
        pauseBox.addActionListener {
            service.paused = pauseBox.isSelected
            applyFilter()
        }
        sortBox.toolTipText = message("combo.sort.tip")
        sortBox.addActionListener { applyFilter() }

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = EntryRenderer(::statementType)
        list.addListSelectionListener { if (!it.valueIsAdjusting) updatePreview() }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && !javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    copySelected()
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showPopup(e)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showPopup(e)
                }
            }
        })
        list.registerKeyboardAction(
            { copySelected() },
            KeyStroke.getKeyStroke(KeyEvent.VK_C, toolkitMenuMask()),
            JComponent.WHEN_FOCUSED
        )

        // SQL 预览是固定结果区，动作就近放在预览标题行，不占用 Header 的瞬时动作位
        val previewActions = JPanel(FlowLayout(FlowLayout.LEFT, UiTokens.scale(4), 0)).apply {
            isOpaque = false
            add(JBLabel(message("label.preview")).apply {
                font = UiTokens.fontSmall()
                foreground = UiTokens.textSecondary
            })
            add(ActionLink(message("link.copy")) { copySelected() })
            add(ActionLink(message("link.jump")) { navigateSelected() })
        }
        val previewPane = JPanel(BorderLayout(0, UiTokens.scale(2))).apply {
            isOpaque = false
            add(previewActions, BorderLayout.NORTH)
            add(
                ScrollPaneFactory.createScrollPane(
                    preview,
                    ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                    ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
                ),
                BorderLayout.CENTER
            )
        }

        split = JSplitPane(JSplitPane.VERTICAL_SPLIT)
        split.topComponent = ScrollPaneFactory.createScrollPane(
            list,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        )
        split.bottomComponent = previewPane
        split.resizeWeight = 0.45
        split.border = JBUI.Borders.empty()
        split.dividerSize = Density.divider

        switcher = ContentSwitcher(split)
        tabs = JBTabbedPane().apply {
            addTab(message("tab.captured"), switcher)
            addTab(message("tab.text"), TextRestorePanel(project))
        }
        shell = ToolWindowShell("MyBatis Log", tabs)
        shell.setActions(
            listOf(
                ToolWindowShell.Action(message("action.copy"), message("action.copy.tip"), ::copySelected),
                ToolWindowShell.Action(message("action.copyAll"), message("action.copyAll.tip"), ::copyAll),
                ToolWindowShell.Action(message("action.navigate"), message("action.navigate.tip"), ::navigateSelected),
                ToolWindowShell.Action(message("action.clear"), message("action.clear.tip"), ::clearWithUndo),
                ToolWindowShell.Action(message("action.import"), message("action.import.tip"), ::importFromClipboard),
                ToolWindowShell.Action(message("action.text"), message("action.text.tip")) { tabs.selectedIndex = TAB_TEXT }
            )
        )
        shell.setSearchField(search)

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, UiTokens.scale(4), 0)).apply {
            isOpaque = false
            add(formatBox)
            add(pauseBox)
            add(JBLabel(message("label.sort")))
            add(sortBox)
        }
        val advanced = JPanel(BorderLayout(0, Density.gap)).apply {
            isOpaque = false
            add(statementChips, BorderLayout.CENTER)
            add(controls, BorderLayout.SOUTH)
        }
        shell.setAdvanced(message("label.type"), advanced) { statementChips.activeCount() }
        shell.onClearFilters(::clearFilters)
        // 窄宽度把主任务区（语句列表）让出来，预览仍可用但只占一小条
        shell.onNarrowChanged { narrow ->
            split.resizeWeight = if (narrow) 0.72 else 0.45
            split.revalidate()
        }

        applyFilter()
        return shell.component
    }

    private fun clearWithUndo() {
        val snapshot = service.snapshot()
        if (snapshot.isEmpty()) return
        service.clear()
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("MyBatis Log", message("notify.cleared", snapshot.size), NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimple(message("action.undo")) { service.restore(snapshot) })
            .notify(project)
    }

    private fun clearFilters() {
        search.text = ""
        statementChips.setAll(true)
    }

    /** SQL 首关键字归类；未识别语句归到「其它」，不会被静默过滤掉 */
    private fun statementType(sql: String): String {
        val first = sql.trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty().uppercase()
        return when {
            first.startsWith("SELECT") || first.startsWith("WITH") -> "SELECT"
            first.startsWith("INSERT") || first.startsWith("REPLACE") -> "INSERT"
            first.startsWith("UPDATE") -> "UPDATE"
            first.startsWith("DELETE") -> "DELETE"
            else -> message("type.other")
        }
    }

    // ------------------------------------------------------------------ 过滤 / 刷新

    private fun applyFilter(scrollToEnd: Boolean = false) {
        val query = SearchQuery.parse(search.text)
        val types = statementChips.selected()
        val matched = all.filter { entry ->
            statementType(entry.sql) in types &&
                // 还原后的 SQL 与原始日志都参与匹配：既能按参数值反查，也能按 ? 占位符搜
                (query.isEmpty || SearchQuery.matches(service.restoredSql(entry, format = false) + "\n" + entry.sql, query))
        }
        // 设置里的关键字过滤是视图级的：条目仍在 all 里，清掉关键字即可恢复，不丢日志
        val keywords = splitFilterKeywords(settings.filterKeywords)
        val keywordVisible = if (keywords.isEmpty()) matched else matched.filterNot { entry ->
            val sql = service.restoredSql(entry, format = false)
            val raw = entry.sql
            keywords.any { sql.contains(it, ignoreCase = true) || raw.contains(it, ignoreCase = true) }
        }
        val hiddenCount = matched.size - keywordVisible.size
        val filtered = sortEntries(keywordVisible)
        visible = filtered
        val selected = list.selectedValue
        model.replaceAll(filtered)
        // 「最新优先」时新语句排在最前，自动滚动要跟着换方向
        val tail = if (sortBox.selectedIndex == SORT_LATEST) 0 else filtered.lastIndex
        if (selected != null && filtered.contains(selected)) {
            list.setSelectedValue(selected, true)
        } else if (filtered.isNotEmpty() && list.selectedIndex < 0) {
            list.selectedIndex = if (scrollToEnd) tail else 0
        }
        if (scrollToEnd && filtered.isNotEmpty()) {
            list.ensureIndexIsVisible(tail)
        }
        val captureOff = !settings.captureEnabled
        val detail = buildString {
            append(message("status.visible", filtered.size, all.size))
            if (formatBox.isSelected) append(message("status.formatOn"))
            if (hiddenCount > 0) append(message("status.hidden", hiddenCount))
        }
        shell.setCount(message("status.count", all.size))
        shell.setStatus(
            if (service.paused || captureOff) StatusBarPanel.State.PAUSED else StatusBarPanel.State.BUSY,
            when {
                service.paused -> message("status.paused")
                captureOff -> message("status.captureOff")
                else -> message("status.capturing")
            },
            detail
        )
        shell.refreshAdvancedToggle()
        when {
            all.isEmpty() -> switcher.showEmpty(
                message("empty.none"),
                message("empty.none.hint"),
                message("action.import"), ::importFromClipboard
            )

            filtered.isEmpty() -> switcher.showEmpty(
                message("empty.filtered"),
                message("empty.filtered.hint"),
                message("empty.clear"), ::clearFilters
            )

            else -> switcher.showContent()
        }
        updatePreview()
    }

    /** 按工具栏下拉重排当前过滤结果；所有比较都带 seq 兜底，顺序稳定 */
    private fun sortEntries(entries: List<MyBatisLogEntry>): List<MyBatisLogEntry> =
        when (sortBox.selectedIndex) {
            SORT_LATEST -> entries.sortedWith(
                compareByDescending<MyBatisLogEntry> { it.timestamp }.thenByDescending { it.seq }
            )

            SORT_INCOMPLETE -> entries.sortedWith(
                compareBy<MyBatisLogEntry> { it.isComplete }.thenBy { it.seq }
            )

            SORT_RESULT -> entries.sortedWith(
                compareByDescending<MyBatisLogEntry> { it.total ?: it.updates ?: -1 }.thenBy { it.seq }
            )

            else -> entries.sortedBy { it.seq }
        }

    private fun updatePreview() {
        val entry = list.selectedValue
        if (entry == null) {
            preview.setHint(message("preview.empty"))
            return
        }
        preview.setSql(service.restoredSql(entry, formatBox.isSelected))
    }

    // ------------------------------------------------------------------ 动作

    private fun copySelected() {
        val entry = list.selectedValue ?: return
        copy(service.restoredSql(entry, formatBox.isSelected), message("notify.copied", entry.seq))
    }

    private fun copyAll() {
        val entries = visible
        if (entries.isEmpty()) {
            return
        }
        val text = entries.joinToString("\n\n") { entry ->
            buildString {
                if (settings.indexLog) append("-- #").append(entry.seq).append('\n')
                append(service.restoredSql(entry, formatBox.isSelected))
                if (settings.impactLog) {
                    entry.total?.let { append("\n-- Total: ").append(it) }
                    entry.updates?.let { append("\n-- Updates: ").append(it) }
                }
            }
        }
        copy(text, message("notify.copiedAll", entries.size))
    }

    /** 标准 MyBatis 日志不带 statement id，按 SQL 文本相似度反查 Mapper XML 语句后跳转 */
    private fun navigateSelected() {
        val entry = list.selectedValue ?: return
        MapperStatementNavigator.navigate(project, entry.sql, ::notify)
    }

    private fun copy(text: String, messageText: String) {
        // 还原后的 SQL 里可能带密码 / token 参数值，复制前遮蔽
        CopyPasteManager.copyTextToClipboard(SensitiveData.maskText(text))
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("MyBatis Log", messageText, NotificationType.INFORMATION)
            .notify(project)
    }

    private fun importFromClipboard() {
        val text = MyBatisLogClipboard.readText()
        if (text.isNullOrBlank()) {
            notify(message("notify.clipboardEmpty"), NotificationType.WARNING)
            return
        }
        service.consume(text, MyBatisLogService.MANUAL_SOURCE)
        applyFilter(scrollToEnd = true)
    }

    private fun showPopup(e: MouseEvent) {
        val index = list.locationToIndex(e.point)
        if (index < 0) {
            return
        }
        list.selectedIndex = index
        val entry = list.model.getElementAt(index)

        val group = DefaultActionGroup()
        group.add(object : AnAction(message("popup.copy")) {
            override fun actionPerformed(event: AnActionEvent) {
                copy(service.restoredSql(entry, formatBox.isSelected), message("notify.copied", entry.seq))
            }
        })
        group.add(object : AnAction(message("popup.explain")) {
            override fun actionPerformed(event: AnActionEvent) {
                copy(explainSql(entry), message("notify.copiedExplain"))
            }
        })
        group.add(object : AnAction(message("popup.raw")) {
            override fun actionPerformed(event: AnActionEvent) {
                copy(originalLog(entry), message("notify.copiedRaw"))
            }
        })
        group.add(object : AnAction(message("popup.params")) {
            override fun actionPerformed(event: AnActionEvent) {
                copy(entry.parameters.joinToString(", ") { it.display }, message("notify.copiedParams"))
            }
        })
        group.add(Separator.getInstance())
        group.add(object : AnAction(message("popup.navigate")) {
            override fun update(event: AnActionEvent) {
                event.presentation.description = message("popup.navigate.tip")
            }

            override fun actionPerformed(event: AnActionEvent) {
                MapperStatementNavigator.navigate(project, entry.sql, ::notify)
            }
        })
        group.add(Separator.getInstance())
        group.add(object : AnAction(message("popup.copyAll")) {
            override fun actionPerformed(event: AnActionEvent) {
                copyAll()
            }
        })

        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                message("popup.title"),
                group,
                SimpleDataContext.getProjectContext(project),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true
            )
            .show(RelativePoint(e.component, e.point))
    }

    /**
     * 还原后的 SQL 加上 EXPLAIN 前缀（执行计划排查慢 SQL 的最小闭环），
     * 没有分号结尾时补一个，方便直接粘贴执行
     */
    private fun explainSql(entry: MyBatisLogEntry): String {
        val sql = service.restoredSql(entry, formatBox.isSelected).trim()
        val body = sql.removeSuffix(";")
        return "EXPLAIN $body;"
    }

    /** 还原成「==> Preparing: … / ==> Parameters: …」的原始两行形式（跟随自定义前缀） */
    private fun originalLog(entry: MyBatisLogEntry): String = buildString {
        val preparing = settings.preparingPrefix.ifBlank { MyBatisLogConfig.DEFAULT_PREPARING }
        val parameters = settings.parametersPrefix.ifBlank { MyBatisLogConfig.DEFAULT_PARAMETERS }
        append("==>  ").append(preparing).append(' ').append(entry.sql)
        if (entry.parameters.isNotEmpty()) {
            append('\n')
            append("==> ").append(parameters).append(' ').append(entry.parameters.joinToString(", ") { it.display })
        }
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("MyBatis Log", content, type)
            .notify(project)
    }

    // ------------------------------------------------------------------ 渲染

    /**
     * 两行渲染：第一行 `#序号 + SQL 首行`（过长省略），第二行参数个数、结果行数、时间与来源。
     * 参数不全（占位符多于参数）时用橙色标出，提示这条日志本身缺参数行。
     */
    private class EntryRenderer(private val typeOf: (String) -> String) :
        JPanel(BorderLayout()), ListCellRenderer<MyBatisLogEntry> {

        private val badge = MethodBadge()
        private val headline = JBLabel()
        private val secondary = JBLabel()
        private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        init {
            isOpaque = true
            headline.isOpaque = false
            headline.font = UiTokens.fontBase()
            headline.foreground = UiTokens.textPrimary
            secondary.isOpaque = false
            secondary.font = UiTokens.fontSmall()
            secondary.foreground = UiTokens.textSecondary
            val firstLine = JPanel(BorderLayout(UiTokens.scale(4), 0)).apply {
                isOpaque = false
                add(badge, BorderLayout.WEST)
                add(headline, BorderLayout.CENTER)
            }
            val lines = JPanel(GridLayout(2, 1)).apply {
                isOpaque = false
                add(firstLine)
                add(secondary)
            }
            add(lines, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out MyBatisLogEntry>,
            value: MyBatisLogEntry?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ): Component {
            if (value != null) {
                badge.badgeText = typeOf(value.sql)
                val pathWidth = (list.width - UiTokens.scale(36) - badge.preferredSize.width).coerceAtLeast(0)
                headline.text = UiText.ellipsize(
                    "#${value.seq}  ${value.headline}",
                    headline.getFontMetrics(headline.font),
                    pathWidth
                )
                headline.toolTipText = value.headline
                val full = buildString {
                    append(message("label.params", value.parameterCount))
                    if (value.missingCount > 0) append(message("label.paramsMissing", value.missingCount))
                    val total = value.total
                    val updates = value.updates
                    when {
                        total != null -> append(" · ").append(message("label.resultRows", total))
                        updates != null -> append(" · ").append(message("label.resultUpdates", updates))
                    }
                    append(" · ").append(timeFormat.format(Date(value.timestamp)))
                    if (value.source.isNotEmpty()) append(" · ").append(value.source)
                }
                secondary.text = UiText.ellipsize(
                    full,
                    secondary.getFontMetrics(secondary.font),
                    (list.width - UiTokens.scale(28)).coerceAtLeast(0)
                )
                secondary.toolTipText = full
                secondary.foreground = if (value.missingCount > 0) UiTokens.warning else UiTokens.textSecondary
            } else {
                badge.badgeText = ""
                headline.text = ""
                secondary.text = ""
            }
            background = if (selected) {
                UIUtil.getListSelectionBackground(hasFocus)
            } else {
                UIUtil.getListBackground()
            }
            foreground = UIUtil.getListForeground()
            border = UiTokens.compound(
                UiTokens.emptyPx(Density.rowPadV, Density.rowPadH),
                UiTokens.line(if (selected) UiTokens.accent else background, left = 2)
            )
            preferredSize = Dimension(list.width.coerceAtLeast(1), Density.listRow)
            return this
        }
    }

    companion object {
        const val NOTIFICATION_GROUP = "MyBatis Log"

        private const val TAB_TEXT = 1

        private val SORT_LABELS = arrayOf(
            message("sort.capture"), message("sort.latest"),
            message("sort.incomplete"), message("sort.result")
        )
        private const val SORT_LATEST = 1
        private const val SORT_INCOMPLETE = 2
        private const val SORT_RESULT = 3

        val STATEMENT_TYPES = listOf("SELECT", "INSERT", "UPDATE", "DELETE", message("type.other"))

        private fun toolkitMenuMask(): Int =
            java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx
    }
}
