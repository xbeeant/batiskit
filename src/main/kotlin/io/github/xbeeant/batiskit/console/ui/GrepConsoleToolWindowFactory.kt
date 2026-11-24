package io.github.xbeeant.batiskit.console.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.github.xbeeant.batiskit.settings.ModuleToggles

/** 底部「Grep Console」工具窗口：日志级别筛选与错误日志快速定位 */
class GrepConsoleToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean =
        ModuleToggles.isEnabled(ModuleToggles.GREP)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = GrepConsolePanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}
