package io.github.xbeeant.batiskit.console.grep

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.project.Project

/**
 * 对标原版 `MyConsoleViewImpl`：Grep 子控制台是一个真正的 [ConsoleViewImpl]
 * （复制 / 清空 / 滚动 / 级别着色全部可用），只多记两个东西——父控制台与所属的 [GrepPanel]。
 */
class MyGrepConsoleView(
    project: Project,
    viewer: Boolean,
    val parentConsoleView: ConsoleView,
) : ConsoleViewImpl(project, viewer) {

    var grepPanel: GrepPanel? = null

    override fun getData(dataId: String): Any? {
        if (GrepPanel.GREP_PANEL.`is`(dataId)) {
            return grepPanel
        }
        return super.getData(dataId)
    }
}
