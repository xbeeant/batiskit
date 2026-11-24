package io.github.xbeeant.batiskit.api.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * 右侧「Spring API」工具窗口：查看 & 搜索项目里的全部 HTTP 接口（含 @Scheduled 任务与 Dubbo 服务）。
 */
class ApiToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = ApiListPanel(project)
        val content = ContentFactory.getInstance().createContent(panel.component, "", false)
        content.isCloseable = false
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        panel.load()
    }
}
