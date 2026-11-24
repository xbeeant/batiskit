package io.github.xbeeant.batiskit.console.grep

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunContentManager
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentFactory
import java.awt.BorderLayout
import java.io.OutputStream
import javax.swing.JPanel

/**
 * 对标原版 `OpenGrepConsoleAction`：控制台里选中内容（或留空）→ 新开一个「Grep 过滤控制台」tab。
 *
 * 子控制台是**真正的 ConsoleView**，寄生在父控制台的输入过滤管道上——父打印的每段文本先经
 * [MyGrepFilter] 分发，命中的行带着原始 contentType 打印进来，颜色 / 错误流样式与父控制台一致；
 * Apply / Reload / 清空 / 父会话结束 (Inactive) 的语义与原版 Grep Console 插件相同。
 */
class OpenGrepTabAction :
    AnAction("Grep（过滤控制台）", "以选中文本为表达式，在 Run / Debug 页面开启一个只显示命中行日志的子控制台", AllIcons.General.Filter) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val parent = GrepConsoleLocator.topParent(event.getData(LangDataKeys.CONSOLE_VIEW))
        val supported = project != null && parent != null &&
            GrepFilterRegistry.getInstance(project).filterOf(parent) != null
        event.presentation.isEnabledAndVisible = supported
    }

    override fun actionPerformed(event: AnActionEvent) {
        // 该 action 注册了全局快捷键，update() 只负责隐藏菜单项，并不能保证快捷键在
        // 不可用上下文里不被派发。这里对每一种不可用情形都给通知，避免按下去静默无响应。
        val project = event.project
        if (project == null) {
            notify(null, "当前没有打开的项目，无法开启 Grep 过滤控制台。", NotificationType.WARNING)
            return
        }
        val parent = GrepConsoleLocator.topParent(event.getData(LangDataKeys.CONSOLE_VIEW))
        if (parent == null) {
            notify(project, "当前没有可用的控制台，无法开启 Grep 过滤控制台。", NotificationType.WARNING)
            return
        }
        val registry = GrepFilterRegistry.getInstance(project)
        val grepFilter = registry.filterOf(parent)
        if (grepFilter == null) {
            notify(project, "该控制台不受 Grep Console 管理，无法开启过滤控制台。", NotificationType.WARNING)
            return
        }
        // 与原版一致：表达式取选中文本（可为空，打开后手动输入）
        var expression = event.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText?.trim().orEmpty()
        if (expression.endsWith("\n")) {
            expression = expression.removeSuffix("\n")
        }

        val descriptor = GrepConsoleLocator.findDescriptor(project, parent)
        val toolWindow = event.getData(PlatformDataKeys.TOOL_WINDOW)
            ?: descriptor?.let { RunContentManager.getInstanceIfCreated(project)?.getToolWindowByDescriptor(it) }
        val ui = GrepConsoleLocator.runnerLayoutUi(project, descriptor, parent)
        if (ui == null && toolWindow == null) {
            notify(project, "没有找到该控制台所在的 Run / Debug 页面，无法停靠过滤控制台。", NotificationType.WARNING)
            return
        }

        // 子控制台 + 转发监听器 + 表达式面板（对标原版 createGrepConsole）
        val handler = LightProcessHandler()
        val console = MyGrepConsoleView(project, false, parent)
        val listener = GrepSubConsoleListener(console::print)
        // 只给子控制台挂片段高亮，表达式始终取 listener 当前 Apply 的那一份
        console.addMessageFilter(GrepMatchHighlightFilter(queryProvider = { listener.query }))
        console.attachToProcess(handler)
        val panel = GrepPanel(
            parentConsole = parent,
            console = console,
            initialExpression = expression,
            replayFn = {
                listener.clear()
                GrepReplay.grepThroughExistingText(parent, listener)
            },
        )
        listener.onMatchCountChanged = { count -> panel.setMatchCount(count) }
        console.grepPanel = panel
        registry.registerChild(parent, console)

        val consolePanel = GrepConsolePanel()
        consolePanel.add(panel.rootComponent, BorderLayout.NORTH)
        consolePanel.add(console.component, BorderLayout.CENTER)

        val title = GrepPanel.title(expression, "")
        val content: Content = if (ui != null) {
            val created = ui.createContent(ExecutionConsole.CONSOLE_CONTENT_ID, consolePanel, title, AllIcons.General.Filter, consolePanel)
            ui.addContent(created)
            ui.selectAndFocus(created, true, true)
            Disposer.register(created, consolePanel)
            created
        } else {
            val created = ContentFactory.getInstance().createContent(consolePanel, title, true)
            created.setDisposer(consolePanel)
            val contentManager = toolWindow!!.contentManager
            contentManager.addContent(created)
            contentManager.setSelectedContent(created)
            created
        }

        panel.closeFn = {
            if (ui != null) {
                ui.removeContent(content, true)
            } else {
                toolWindow?.contentManager?.removeContent(content, true)
            }
        }
        panel.selectSourceFn = { GrepConsoleLocator.selectSource(ui, toolWindow, parent) }

        // Apply / Reload 的落地：更新监听器表达式 + tab 标题 + Source 描述
        panel.applyCallback = { query, newTitle ->
            listener.query = query
            listener.resetCount()
            content.setDisplayName(newTitle)
            val source = if (parent is MyGrepConsoleView) {
                "Source: '${parent.grepPanel?.cachedTitle ?: "Grep"}'"
            } else {
                "Source: Main Console"
            }
            content.setDescription(source)
        }
        panel.apply()

        // 父控制台清空 → 子控制台跟着清空（对标 autoClearChildConsoles）
        if (parent is ConsoleViewImpl) {
            parent.editor?.document?.addDocumentListener(object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (event.newLength == 0 && event.document.textLength == 0) {
                        console.clear()
                    }
                }
            }, parent)
        }

        // 与原版顺序一致：先回放已有文本，再挂上实时转发（避免新输出重复）
        GrepReplay.grepThroughExistingText(parent, listener)
        grepFilter.addListener(listener)

        // 生命周期：tab 关闭 → 子控制台销毁、监听器从过滤器摘除
        Disposer.register(consolePanel, console)
        Disposer.register(consolePanel, listener)
        Disposer.register(consolePanel, Disposable { grepFilter.removeListener(listener) })

        // 父会话结束 → tab 标记 (Inactive)
        val parentDisposable: Disposable = descriptor ?: parent
        Disposer.register(parentDisposable, Disposable {
            content.setDisplayName(content.displayName + " (Inactive)")
        })
    }

    /** project 为 null 时走全局通知（Notification#notify 允许 null） */
    private fun notify(project: Project?, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Grep Console")
            .createNotification("Grep Console", message, type)
            .notify(project)
    }

    /** 对标原版 LightProcessHandler：子控制台 attach 用的空壳进程句柄（不产出任何输入） */
    class LightProcessHandler : ProcessHandler() {
        override fun destroyProcessImpl() {
            // 空壳进程没有任何资源要释放：抛异常会被平台当成进程销毁失败并弹进 IDE 日志
            notifyProcessTerminated(0)
        }

        override fun detachProcessImpl() {
            notifyProcessTerminated(0)
        }

        override fun detachIsDefault(): Boolean = false

        override fun getProcessInput(): OutputStream? = null
    }

    /** 对标原版 MyJPanel：可 Disposable 的面板，tab 关闭时作为整棵销毁链的根 */
    private class GrepConsolePanel : JPanel(BorderLayout()), Disposable {
        override fun dispose() {
            removeAll()
        }
    }
}
