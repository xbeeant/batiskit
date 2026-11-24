package io.github.xbeeant.batiskit.console.grep

import com.intellij.execution.console.ConsoleViewWrapperBase
import com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.xdebugger.XDebuggerManager
import java.awt.Component

/**
 * 原版 `OpenGrepConsoleAction` 里「定位运行会话」工具的移植：
 * 解包各类包装控制台后按身份比对 descriptor，取 RunnerLayoutUi（Debug 会话兜底），
 * 以及 Source 动作的「跳回来源控制台」。
 */
internal object GrepConsoleLocator {

    /** 链式 grep 时沿 parentConsoleView 找到最初的控制台（对标 getTopParentConsoleView） */
    fun topParent(console: ConsoleView?): ConsoleView? {
        var current = console
        while (current is MyGrepConsoleView) {
            current = current.parentConsoleView
        }
        return current
    }

    /** 对标原版 getRunContentDescriptor：可见的选中内容优先，其余遍历全部 descriptor */
    fun findDescriptor(project: Project, console: ConsoleView): RunContentDescriptor? {
        val manager = RunContentManager.getInstanceIfCreated(project) ?: return null
        val selected = manager.selectedContent
        if (selected != null) {
            val toolWindow = manager.getToolWindowByDescriptor(selected)
            if (toolWindow != null && toolWindow.isVisible && isSameConsole(selected, console, true)) {
                return selected
            }
        }
        return manager.allDescriptors.firstOrNull { isSameConsole(it, console, true) }
    }

    /** 对标原版 isSameConsole(RunContentDescriptor, ...)：Grep 子控制台沿父链归位 */
    fun isSameConsole(descriptor: RunContentDescriptor, console: ConsoleView, orChild: Boolean): Boolean {
        if (console is MyGrepConsoleView && orChild) {
            return isSameConsole(descriptor, console.parentConsoleView, orChild)
        }
        return unwrap(descriptor.executionConsole) === console
    }

    /** 解包测试输出控制台与 JavaEE 式包装控制台（对标原版同名逻辑） */
    fun unwrap(console: ExecutionConsole?): ExecutionConsole? {
        var current = console
        while (true) {
            current = when (val c = current) {
                is BaseTestsOutputConsoleView -> c.console
                is ConsoleViewWrapperBase -> c.delegate
                else -> return c
            }
        }
    }

    /** 对标原版 getRunnerLayoutUi：descriptor → Debug 会话 UI 兜底 */
    fun runnerLayoutUi(project: Project, descriptor: RunContentDescriptor?, console: ConsoleView): RunnerLayoutUi? {
        descriptor?.runnerLayoutUi?.let {
            return it
        }
        val manager = XDebuggerManager.getInstance(project)
        manager.getDebugSession(console)?.ui?.let {
            return it
        }
        return manager.currentSession?.ui
    }

    /** Source：把 Run / Debug 页面切回来源控制台所在的 tab（对标 SelectSourceActionListener） */
    fun selectSource(ui: RunnerLayoutUi?, toolWindow: ToolWindow?, console: ConsoleView) {
        if (ui != null) {
            val target = ui.contents.firstOrNull { containsComponent(it.component, console.component) }
            if (target != null) {
                ui.selectAndFocus(target, true, true)
            }
            return
        }
        val contentManager = toolWindow?.contentManager ?: return
        val target = contentManager.contents.firstOrNull { containsComponent(it.component, console.component) }
        if (target != null) {
            contentManager.setSelectedContent(target)
        }
    }

    /** 在组件树里找来源控制台的落脚点（对标原版 searchComponentTree） */
    private fun containsComponent(root: Component, target: Component): Boolean {
        if (root === target) {
            return true
        }
        val container = root as? java.awt.Container ?: return false
        for (child in container.components) {
            if (containsComponent(child, target)) {
                return true
            }
        }
        return false
    }
}

