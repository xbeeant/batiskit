package io.github.xbeeant.batiskit.console.ui

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.util.Alarm
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import io.github.xbeeant.batiskit.ui.ToolWindowShell
import io.github.xbeeant.batiskit.ui.UiTokens
import io.github.xbeeant.batiskit.ui.components.BannerPanel
import io.github.xbeeant.batiskit.ui.components.ContentSwitcher
import io.github.xbeeant.batiskit.ui.components.FilterChipGroup
import io.github.xbeeant.batiskit.ui.components.SearchField
import io.github.xbeeant.batiskit.ui.components.StatusBarPanel
import io.github.xbeeant.batiskit.console.FilterQuery
import io.github.xbeeant.batiskit.console.GrepConsoleService
import io.github.xbeeant.batiskit.console.LogEntry
import io.github.xbeeant.batiskit.console.LogLevel
import io.github.xbeeant.batiskit.console.LogLinkScanner
import io.github.xbeeant.batiskit.log.MyBatisLogClipboard
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants

/**
 * 底部「Grep Console」工具窗口：把控制台里的行按级别收集起来，一键只看 ERROR。
 *
 * 控制台本体里的着色与过滤由 `ConsoleLogFilter` / `ConsoleLogInputFilter` 负责；
 * 这里的列表是**只读副本**，暂停、清空、搜索都不会动 Run 窗口里的原始日志。
 */
class GrepConsolePanel(private val project: Project) : Disposable {

    private val service = GrepConsoleService.getInstance(project)
    private val settings get() = service.settings

    private val search = SearchField("搜索日志，空格分隔多个关键字（/…/ 用正则）") { scheduleFilter(false) }

    /** 级别从「最低级别下拉」改为可多选 chip；DEBUG 组含 TRACE 与未识别级别 */
    private val levelChips = FilterChipGroup(LEVEL_CHIPS, LEVEL_CHIPS) { scheduleFilter(false) }

    private val sortBox = ComboBox(arrayOf("捕获顺序", "错误优先", "最新优先", "来源 A→Z"))
    private val errorOnlyBox = JBCheckBox("只看 ERROR")
    private val pauseBox = JBCheckBox("暂停")
    private val model = CollectionListModel<LogEntry>()
    private val list = JBList(model)
    private lateinit var shell: ToolWindowShell
    private lateinit var switcher: ContentSwitcher

    /** 「只看 ERROR」勾选前的级别组合，取消勾选时原样恢复 */
    private var previousLevels: Set<String> = LEVEL_CHIPS.toSet()

    /** 全量条目（过滤前） */
    private val all = mutableListOf<LogEntry>()
    private var visible: List<LogEntry> = emptyList()

    /** 一批日志只排一次 EDT 刷新；高频输出时不做一批一次的全量过滤 + replaceAll */
    private val refreshAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var refreshScrollToEnd = false
    private var allVersion = 0
    private var lastViewKey: String? = null

    /** 级别计数随增删增量维护，不再每次刷新都对全量列表 count 六遍 */
    private val levelCounts = mutableMapOf<LogLevel, Int>()

    /** 搜索表达式按文本缓存，输入 / 刷新时避免重复构造 */
    private var cachedFilterText: String? = null
    private var cachedFilter: FilterQuery? = null

    private val listener = object : GrepConsoleService.Listener {
        override fun linesAdded(lines: List<LogEntry>) {
            if (lines.isEmpty()) return
            for (entry in lines) {
                levelCounts[entry.level] = (levelCounts[entry.level] ?: 0) + 1
            }
            all.addAll(lines)
            val limit = settings.maxEntries
            while (all.size > limit) {
                val removed = all.removeAt(0)
                val left = (levelCounts[removed.level] ?: 1) - 1
                if (left > 0) levelCounts[removed.level] = left else levelCounts.remove(removed.level)
            }
            allVersion++
            scheduleFilter(scrollToEnd = true)
        }

        override fun refreshed() {
            all.clear()
            all.addAll(service.snapshot())
            rebuildCounts()
            allVersion++
            scheduleFilter(scrollToEnd = false)
        }
    }

    val component: JComponent = createComponent()

    init {
        pauseBox.isSelected = service.paused
        service.addListener(listener, this)
        all.addAll(service.snapshot())
        rebuildCounts()
        applyFilter(force = true)
    }

    override fun dispose() {
        // 监听随 Disposable 一起摘掉
    }

    // ------------------------------------------------------------------ UI

    private fun createComponent(): JComponent {
        errorOnlyBox.toolTipText = "一键只看 ERROR，取消后恢复之前的级别组合"
        errorOnlyBox.addActionListener {
            if (errorOnlyBox.isSelected) {
                previousLevels = levelChips.selected()
                levelChips.setAll(false)
                levelChips.setSelected("ERROR", true)
            } else {
                levelChips.setAll(false)
                previousLevels.forEach { levelChips.setSelected(it, true) }
            }
            scheduleFilter(false)
        }
        pauseBox.toolTipText = "暂停后不再收集新的控制台输出（已捕获的不受影响）"
        pauseBox.addActionListener {
            service.paused = pauseBox.isSelected
            updateBanners()
            scheduleFilter(false)
        }
        sortBox.toolTipText = "列表排序方式；切换后当前过滤结果立即重排，捕获顺序下新行自动滚到底"
        sortBox.addActionListener { scheduleFilter(false) }

        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = EntryRenderer()
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && !javax.swing.SwingUtilities.isRightMouseButton(e)) copySelected()
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showPopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showPopup(e)
            }
        })
        list.registerKeyboardAction(
            { copySelected() },
            KeyStroke.getKeyStroke(KeyEvent.VK_C, java.awt.Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx),
            JComponent.WHEN_FOCUSED
        )

        val scroll = ScrollPaneFactory.createScrollPane(
            list,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
        )
        switcher = ContentSwitcher(scroll)
        shell = ToolWindowShell("Grep Console · 只读副本", switcher)
        shell.setActions(
            listOf(
                ToolWindowShell.Action("复制选中", "复制当前选中的一行日志", ::copySelected),
                ToolWindowShell.Action("复制全部", "复制当前过滤结果的全部日志", ::copyAll),
                ToolWindowShell.Action("清空", "清空捕获到的日志，5 秒内可撤销", ::clearWithUndo),
                ToolWindowShell.Action("导入剪贴板日志", "把剪贴板里的文本当成日志导入", ::importFromClipboard)
            )
        )
        shell.setSearchField(search)

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, UiTokens.scale(4), 0)).apply {
            isOpaque = false
            add(errorOnlyBox)
            add(pauseBox)
            add(JBLabel("排序："))
            add(sortBox)
        }
        val advanced = JPanel(BorderLayout(0, UiTokens.scale(2))).apply {
            isOpaque = false
            add(levelChips, BorderLayout.CENTER)
            add(controls, BorderLayout.SOUTH)
        }
        shell.setAdvanced("级别", advanced) { levelChips.activeCount() }
        shell.onClearFilters(::clearFilters)

        updateBanners()
        return shell.component
    }

    private fun clearFilters() {
        search.text = ""
        errorOnlyBox.isSelected = false
        previousLevels = LEVEL_CHIPS.toSet()
        levelChips.setAll(true)
    }

    /** 低风险破坏性操作立即生效，给 5 秒撤销，不弹确认打断排查节奏 */
    private fun clearWithUndo() {
        val snapshot = service.snapshot()
        if (snapshot.isEmpty()) return
        service.clear()
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Grep Console")
            .createNotification("Grep Console", "已清空 ${snapshot.size} 行日志", NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimple("撤销") { service.restore(snapshot) })
            .notify(project)
    }

    /** 暂停 / 捕获关闭是会影响结果的上下文，固定放在 Context Banner */
    private fun updateBanners() {
        when {
            service.paused -> shell.showBanner(
                BannerPanel.Type.WARN,
                "已暂停",
                "不再收集新的控制台输出，已捕获的日志不受影响",
                "继续捕获"
            ) {
                pauseBox.isSelected = false
                service.paused = false
                updateBanners()
                scheduleFilter(false)
            }

            !settings.enabled || !settings.captureEnabled -> shell.showBanner(
                BannerPanel.Type.INFO,
                "捕获已关闭",
                "在 Settings → Tools → Batis Kit → Grep Console 里开启",
                null,
                null
            )

            else -> shell.hideBanner()
        }
    }

    // ------------------------------------------------------------------ 过滤 / 刷新

    /** 同值按 seq 兜底，保证重排结果稳定、堆栈续行不会被打散到别处 */
    private fun sortEntries(entries: List<LogEntry>): List<LogEntry> =
        when (sortBox.selectedIndex) {
            1 -> entries.sortedWith(
                compareByDescending<LogEntry> { it.level.rank }.thenBy { it.seq }
            )
            2 -> entries.sortedWith(
                compareByDescending<LogEntry> { it.time }.thenByDescending { it.seq }
            )
            3 -> entries.sortedWith(
                compareBy<LogEntry> { it.source.lowercase() }.thenBy { it.seq }
            )
            else -> entries.sortedBy { it.seq }
        }

    private fun rebuildCounts() {
        levelCounts.clear()
        for (entry in all) {
            levelCounts[entry.level] = (levelCounts[entry.level] ?: 0) + 1
        }
    }

    /** 高频日志只保留最后一次刷新请求，避免 EDT 被一批批全量过滤占满 */
    private fun scheduleFilter(scrollToEnd: Boolean) {
        refreshScrollToEnd = refreshScrollToEnd || scrollToEnd
        refreshAlarm.cancelAllRequests()
        refreshAlarm.addRequest({
            val scroll = refreshScrollToEnd
            refreshScrollToEnd = false
            applyFilter(scrollToEnd = scroll)
        }, REFRESH_DELAY_MS)
    }

    /** 输入没变且列表没变时直接跳过，避免勾选框 / 初始化触发重复全量过滤 */
    private fun viewKey(): String = listOf(
        allVersion,
        search.text,
        levelChips.selected().sorted(),
        sortBox.selectedIndex,
        errorOnlyBox.isSelected,
        service.paused,
        settings.enabled,
        settings.captureEnabled,
    ).joinToString("\u0000")

    private fun queryFor(text: String): FilterQuery? {
        if (text.isEmpty()) return null
        if (cachedFilterText != text) {
            cachedFilterText = text
            cachedFilter = FilterQuery(text)
        }
        return cachedFilter
    }

    private fun applyFilter(scrollToEnd: Boolean = false, force: Boolean = false) {
        val key = viewKey()
        if (!force && key == lastViewKey) {
            if (scrollToEnd && visible.isNotEmpty()) list.ensureIndexIsVisible(visible.lastIndex)
            return
        }
        lastViewKey = key

        val filter = queryFor(search.text.trim())
        val enabled = levelChips.selected()
        val filtered = sortEntries(all.filter { entry ->
            levelVisible(entry.level, enabled) &&
                (!errorOnlyBox.isSelected || entry.level == LogLevel.ERROR) &&
                (filter == null || filter.matches(entry.text))
        })
        visible = filtered
        val selected = list.selectedValue
        model.replaceAll(filtered)
        if (selected != null && filtered.contains(selected)) {
            list.setSelectedValue(selected, true)
        } else if (filtered.isNotEmpty() && list.selectedIndex < 0) {
            list.selectedIndex = if (scrollToEnd) filtered.lastIndex else 0
        }
        if (scrollToEnd && filtered.isNotEmpty()) list.ensureIndexIsVisible(filtered.lastIndex)

        val captureOff = !settings.enabled || !settings.captureEnabled
        shell.setCount("共 ${all.size} 行")
        shell.setStatus(
            if (service.paused || captureOff) StatusBarPanel.State.PAUSED else StatusBarPanel.State.BUSY,
            when {
                service.paused -> "已暂停"
                captureOff -> "捕获已关闭"
                else -> "正在捕获"
            },
            "显示 ${filtered.size} / ${all.size} 行" +
                " · ERROR ${levelCounts[LogLevel.ERROR] ?: 0}" +
                " · WARN ${levelCounts[LogLevel.WARN] ?: 0}" +
                " · INFO ${levelCounts[LogLevel.INFO] ?: 0}"
        )
        shell.refreshAdvancedToggle()
        when {
            all.isEmpty() -> switcher.showEmpty(
                "还没有捕获到日志",
                "控制台输出的只读副本，不影响原控制台；运行程序后日志会自动出现在这里",
                "导入剪贴板日志", ::importFromClipboard
            )

            filtered.isEmpty() -> switcher.showEmpty(
                "没有匹配的日志",
                "换个关键词，或者把级别筛选恢复成全部",
                "清除筛选", ::clearFilters
            )

            else -> switcher.showContent()
        }
    }

    /** 4 个 chip 覆盖 6 个级别：DEBUG 组含 TRACE 与未识别级别 */
    private fun levelVisible(level: LogLevel, enabled: Set<String>): Boolean = when {
        level == LogLevel.ERROR -> "ERROR" in enabled
        level == LogLevel.WARN -> "WARN" in enabled
        level == LogLevel.INFO -> "INFO" in enabled
        else -> "DEBUG" in enabled
    }

    // ------------------------------------------------------------------ 动作

    private fun copySelected() {
        val entry = list.selectedValue ?: return
        copy(entry.text, "已复制第 ${entry.seq} 行日志")
    }

    private fun copyAll() {
        if (visible.isEmpty()) return
        copy(visible.joinToString("\n") { it.text }, "已复制 ${visible.size} 行日志")
    }

    private fun copy(text: String, message: String) {
        CopyPasteManager.copyTextToClipboard(text)
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Grep Console")
            .createNotification("Grep Console", message, NotificationType.INFORMATION)
            .notify(project)
    }

    private fun importFromClipboard() {
        val text = MyBatisLogClipboard.readText()
        if (text.isNullOrBlank()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Grep Console")
                .createNotification("Grep Console", "剪贴板里没有文本", NotificationType.WARNING)
                .notify(project)
            return
        }
        service.consume(text, GrepConsoleService.MANUAL_SOURCE, flush = true)
        scheduleFilter(scrollToEnd = true)
    }

    private fun showPopup(e: MouseEvent) {
        val index = list.locationToIndex(e.point)
        if (index < 0) return
        list.selectedIndex = index
        val entry = list.model.getElementAt(index)
        val group = DefaultActionGroup()
        group.add(object : AnAction("复制这一行") {
            override fun actionPerformed(event: AnActionEvent) = copy(entry.text, "已复制第 ${entry.seq} 行日志")
        })
        addLinkActions(group, entry.text)
        group.add(object : AnAction("只看 ${entry.level.label}") {
            override fun actionPerformed(event: AnActionEvent) {
                errorOnlyBox.isSelected = false
                levelChips.setAll(false)
                levelChips.setSelected(chipFor(entry.level), true)
                scheduleFilter(false)
            }
        })
        group.add(object : AnAction("复制全部（当前过滤结果）") {
            override fun actionPerformed(event: AnActionEvent) = copyAll()
        })
        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                "日志",
                group,
                SimpleDataContext.getProjectContext(project),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true
            )
            .show(RelativePoint(e.component, e.point))
    }

    // ------------------------------------------------------------------ 链接跳转

    /**
     * 工具窗口是只读列表，平台不会在这里生成超链接；把行里识别出的
     * URL / 文件路径 / Java 引用挂到右键菜单上，菜单文字带原文方便挑选。
     */
    private fun addLinkActions(group: DefaultActionGroup, line: String) {
        val links = LogLinkScanner.scan(line).take(5)
        links.forEach { link ->
            group.add(object : AnAction("${link.kind.label}：${link.text}") {
                override fun actionPerformed(event: AnActionEvent) = openLink(project, link)
            })
        }
    }

    private fun openLink(project: Project, link: LogLinkScanner.Link) {
        when (link.kind) {
            LogLinkScanner.Kind.URL -> BrowserUtil.browse(link.text)
            LogLinkScanner.Kind.FILE_PATH -> openFile(project, link.text)
            LogLinkScanner.Kind.JAVA_CLASS, LogLinkScanner.Kind.JAVA_METHOD -> navigateToReference(project, link.text)
        }
    }

    private fun openFile(project: Project, reference: String) {
        val cleaned = reference.trim()
        val colon = cleaned.lastIndexOf(':')
        val (path, line) = if (colon > 0 && cleaned.substring(colon + 1).all { it.isDigit() }) {
            cleaned.substring(0, colon) to (cleaned.substring(colon + 1).toIntOrNull() ?: 0)
        } else {
            cleaned to 0
        }
        val base = project.basePath ?: return
        val candidates = listOf(java.nio.file.Path.of(base, path), java.nio.file.Path.of(path))
        val file = candidates.firstOrNull { java.nio.file.Files.isRegularFile(it) } ?: return
        val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(file) ?: return
        if (line > 0) {
            OpenFileDescriptor(project, virtualFile, line - 1, 0).navigate(true)
        } else {
            OpenFileDescriptor(project, virtualFile, 0).navigate(true)
        }
    }

    private fun navigateToReference(project: Project, reference: String) {
        val dot = reference.lastIndexOf('.')
        if (dot <= 0) {
            navigateToClass(project, reference)
            return
        }
        val fqn = reference.substring(0, dot)
        val memberName = reference.substring(dot + 1)
        val target = ReadAction.compute<PsiElement?, RuntimeException> {
            val psiClass = JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
                ?: JavaPsiFacade.getInstance(project).findClass(reference, GlobalSearchScope.allScope(project))
            if (psiClass == null) {
                null
            } else {
                psiClass.findMethodsByName(memberName, true).firstOrNull()
                    ?: psiClass.findFieldByName(memberName, true)
                    ?: psiClass.findInnerClassByName(memberName, false)
                    ?: psiClass
            }
        }
        if (target != null) {
            navigateToElement(project, target)
        }
    }

    private fun navigateToClass(project: Project, fqn: String) {
        val target = ReadAction.compute<PsiClass?, RuntimeException> {
            JavaPsiFacade.getInstance(project).findClass(fqn, GlobalSearchScope.allScope(project))
        }
        if (target != null) {
            navigateToElement(project, target)
        }
    }

    /** 与 ConsoleLinkFilter 同款：用 OpenFileDescriptor 落到元素所在文件与偏移 */
    private fun navigateToElement(project: Project, element: PsiElement) {
        val file = element.containingFile?.virtualFile ?: return
        OpenFileDescriptor(project, file, element.textOffset).navigate(true)
    }

    // ------------------------------------------------------------------ 渲染

    private inner class EntryRenderer : ColoredListCellRenderer<LogEntry>() {
        private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS")

        override fun customizeCellRenderer(
            list: JList<out LogEntry>,
            value: LogEntry?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ) {
            val entry = value ?: return
            val color = io.github.xbeeant.batiskit.console.GrepConsoleSettings.parseColor(
                settings.colors()[entry.level]
            )
            val levelAttrs = if (color != null && !selected) {
                SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, color)
            } else {
                SimpleTextAttributes.GRAYED_ATTRIBUTES
            }
            append("${entry.level.label.padEnd(5)} ", levelAttrs)
            append("${timeFormat.format(Date(entry.time))} ", SimpleTextAttributes.GRAY_ATTRIBUTES)
            append("${entry.source} ", SimpleTextAttributes.GRAY_ATTRIBUTES)
            append(entry.text, if (color != null && !selected) {
                SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color)
            } else {
                SimpleTextAttributes.REGULAR_ATTRIBUTES
            })
        }
    }

    companion object {
        /** DEBUG chip 同时覆盖 TRACE 与 OTHER，保证 6 个级别都能被筛到 */
        val LEVEL_CHIPS = listOf("ERROR", "WARN", "INFO", "DEBUG")

        private const val REFRESH_DELAY_MS = 60L

        fun chipFor(level: LogLevel): String = when (level) {
            LogLevel.ERROR -> "ERROR"
            LogLevel.WARN -> "WARN"
            LogLevel.INFO -> "INFO"
            else -> "DEBUG"
        }
    }
}
