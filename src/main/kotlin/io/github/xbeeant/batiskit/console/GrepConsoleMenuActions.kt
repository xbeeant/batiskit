package io.github.xbeeant.batiskit.console

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView
import com.intellij.execution.ui.ConsoleView
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.ColorPicker
import io.github.xbeeant.batiskit.console.grep.OpenGrepTabAction

/**
 * 控制台右键菜单里的「Grep Console」分组（对照原版插件的 Grep 菜单）：
 * 为选中内容添加高亮、开启过滤 Tab、打开设置、快速切换输入过滤、查看级别统计。
 *
 * 过滤修改会立即生效——但控制台过滤丢弃的行不可恢复，所以菜单项上直接写明风险。
 */
class GrepConsoleMenuGroup : DefaultActionGroup() {
    init {
        add(GrepAddHighlightAction())
        add(OpenGrepTabAction())
        addSeparator()
        add(GrepConsoleOpenSettingsAction())
        addSeparator()
        add(GrepConsoleErrorsOnlyAction())
        add(GrepConsoleClearFilterAction())
        addSeparator()
        add(GrepConsoleStatsAction())
    }
}

/**
 * 对标原版 Grep Console 的「Add Highlight」：控制台里选中一段内容，右键一键把它创建成高亮规则，
 * 弹色板选背景色后命中片段立即着色——已打印的行也会立即重新着色（原版 Rehighlighter 的做法）。
 */
class GrepAddHighlightAction : AnAction("添加高亮（Add Highlight）") {

    init {
        templatePresentation.description = "把控制台里选中的内容创建为关键词高亮规则，命中片段立即着色（含已打印的行）"
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val selection = event.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText?.trim()
        event.presentation.isEnabledAndVisible = event.project != null &&
            event.getData(LangDataKeys.CONSOLE_VIEW) != null &&
            !selection.isNullOrBlank()
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val console = event.getData(LangDataKeys.CONSOLE_VIEW) ?: return
        val pattern = event.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText?.trim().orEmpty()
        if (pattern.isEmpty()) {
            return
        }
        val state = GrepConsoleSettings.getInstance().config
        if (state.rules.any { it.pattern == pattern }) {
            notify(project, "高亮规则已存在：$pattern（可在 Settings → Tools → Batis Kit → Grep Console 管理）", NotificationType.INFORMATION)
            return
        }
        // 对标原版：色板选背景色，默认色在调色板里轮换
        val dialogParent = rootComponent(project) ?: return
        val color = ColorPicker.showDialog(dialogParent, "背景颜色", nextColor(), true, null, true) ?: return
        // 对标原版 addExpressionItem：色板选的是**背景色**，写进 background（`浅色|深色`），
        // 前景保持控制台默认色 —— 以前误写进 color，浅色主题下亮色当文字色会看不清，且背景根本没生效
        state.rules.add(0, HighlightRule(pattern = pattern, background = GrepHighlightColor.fromColor(color)))
        GrepConsoleService.getInstance(project).settingsChanged()
        rehighlight(console)
        notify(project, "已添加高亮规则：$pattern（可在 Settings → Tools → Batis Kit → Grep Console 修改或删除）")
    }

    /** 已打印的行立即重新着色（对标原版 Rehighlighter.resetHighlights） */
    private fun rehighlight(console: ConsoleView) {
        var current: ConsoleView = console
        if (current is BaseTestsOutputConsoleView) {
            current = current.console
        }
        (current as? ConsoleViewImpl)?.rehighlightHyperlinksAndFoldings()
    }

    private fun rootComponent(project: Project): java.awt.Component? {
        val frame = WindowManager.getInstance().getIdeFrame(project) ?: return null
        return frame.component
    }

    private fun nextColor(): java.awt.Color = java.awt.Color.decode(
        HIGHLIGHT_COLORS[colorIndex++ % HIGHLIGHT_COLORS.size],
    )

    private companion object {
        /** 轮换默认背景色（对标原版 Utils.nextColor） */
        val HIGHLIGHT_COLORS = listOf(
            "#FFF3BF",
            "#BDE3FF",
            "#D6F5C6",
            "#F6D9F6",
        )
        var colorIndex = 0
    }
}

/** 打开 Settings → Tools → Batis Kit → Grep Console */
class GrepConsoleOpenSettingsAction : AnAction("打开 Grep Console 设置…") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, GrepConsoleConfigurable::class.java)
    }
}

/** 控制台里一键切到「只显示 ERROR」（等同设置页的下拉，但不用翻设置） */
class GrepConsoleErrorsOnlyAction : AnAction("只显示 ERROR（会丢弃其他日志）") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        // action 跑在 BGT，弹确认框必须切回 EDT
        ApplicationManager.getApplication().invokeLater {
            val answer = Messages.showYesNoDialog(
                project,
                "切换后新产生的非 ERROR 日志会被直接丢弃，且无法恢复（关掉过滤也不会回来）。" +
                    "只想临时筛查建议用底部工具窗口或 Grep 子控制台。确认切换？",
                "只显示 ERROR（丢弃其他日志）",
                Messages.getWarningIcon(),
            )
            if (answer != Messages.YES) return@invokeLater
            val state = GrepConsoleSettings.getInstance().config
            state.filterMode = ConsoleFilterMode.ERRORS_ONLY.name
            GrepConsoleService.getInstance(project).settingsChanged()
            notify(project, "控制台过滤已切到「只显示 ERROR」。丢弃的行不可恢复；恢复不过滤请选「恢复不过滤」。")
        }
    }
}

/** 恢复不过滤（级别 / 关键词模式与规则过滤一起关） */
class GrepConsoleClearFilterAction : AnAction("恢复不过滤") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val state = GrepConsoleSettings.getInstance().config
        state.filterMode = ConsoleFilterMode.OFF.name
        state.ruleFilterEnabled = false
        // 通知工具窗口等订阅者刷新勾选态，否则 UI 与实际过滤状态会不一致
        GrepConsoleService.getInstance(project).settingsChanged()
        notify(project, "控制台过滤已全部关闭。")
    }

    override fun update(event: AnActionEvent) {
        val state = GrepConsoleSettings.getInstance().config
        event.presentation.isEnabled =
            state.mode() != ConsoleFilterMode.OFF || state.ruleFilterEnabled
    }
}

/** 弹出当前捕获日志的级别统计（数据与底部工具窗口一致） */
class GrepConsoleStatsAction : AnAction("显示日志统计") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val entries = GrepConsoleService.getInstance(project).snapshot()
        if (entries.isEmpty()) {
            notify(project, "还没有捕获到日志。运行程序后底部「Grep Console」工具窗口会自动收集。", NotificationType.INFORMATION)
            return
        }
        val counts = LogLevel.entries.associateWith { level -> entries.count { it.level == level } }
        val detail = LogLevel.entries
            .filter { it != LogLevel.OTHER && (counts[it] ?: 0) > 0 }
            .joinToString(" · ") { "${it.label} ${counts[it]}" }
        notify(project, "已捕获 ${entries.size} 行：$detail")
    }
}

private fun notify(project: Project, message: String, type: NotificationType = NotificationType.INFORMATION) {
    NotificationGroupManager.getInstance()
        .getNotificationGroup("Grep Console")
        .createNotification("Grep Console", message, type)
        .notify(project)
}
