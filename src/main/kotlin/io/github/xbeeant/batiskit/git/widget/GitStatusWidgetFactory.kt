package io.github.xbeeant.batiskit.git.widget

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

/**
 * 注册到 `statusBarWidgetFactory` EP，让用户可以在状态栏里开关这个 widget。
 */
class GitStatusWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = GitStatusWidget.ID_VALUE

    override fun getDisplayName(): String = "Git 状态（Batis Kit）"

    override fun createWidget(project: Project): StatusBarWidget = GitStatusWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) {
        widget.dispose()
    }

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    override fun isAvailable(project: Project): Boolean = true
}
