package io.github.xbeeant.batiskit.string.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.github.xbeeant.batiskit.settings.ModuleToggles

/**
 * 右侧「String Manipulation」工具窗口：输入一段文本，选操作、调选项，结果实时出来。
 * 与编辑器右键（选中即替换）互补，这里适合批量处理与连续串多个操作。
 */
class StringToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean =
        ModuleToggles.isEnabled(ModuleToggles.STRING)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = StringPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}
