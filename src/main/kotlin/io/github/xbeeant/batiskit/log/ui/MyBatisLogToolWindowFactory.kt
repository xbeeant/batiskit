package io.github.xbeeant.batiskit.log.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import io.github.xbeeant.batiskit.settings.ModuleToggles

/**
 * 底部「MyBatis Log」工具窗口：捕获到的 SQL 与还原结果。
 */
class MyBatisLogToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun shouldBeAvailable(project: Project): Boolean =
        ModuleToggles.isEnabled(ModuleToggles.LOG)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = MyBatisLogPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}
