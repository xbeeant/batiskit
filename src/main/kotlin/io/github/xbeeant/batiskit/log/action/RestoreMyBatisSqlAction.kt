package io.github.xbeeant.batiskit.log.action

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import io.github.xbeeant.batiskit.log.MyBatisLogBundle.message
import io.github.xbeeant.batiskit.log.MyBatisLogClipboard
import io.github.xbeeant.batiskit.log.MyBatisLogParser
import io.github.xbeeant.batiskit.log.MyBatisLogService
import io.github.xbeeant.batiskit.log.toConfig
import io.github.xbeeant.batiskit.log.ui.MyBatisLogPanel
import io.github.xbeeant.batiskit.support.SensitiveData

/**
 * 控制台 / 编辑器右键「还原 MyBatis SQL」：把选中的日志文本（没有选区时取剪贴板）还原成可执行 SQL 并复制。
 *
 * 用于「控制台日志已经刷过去了 / 从别处贴了一段日志」的场景——工具窗口只捕获运行中的输出，
 * 这里对离线文本做一次性还原，不写回列表，避免和控制台捕获的条目重复。
 * 挂在 ConsoleEditorPopupMenu 后，Run 控制台里选中几行日志即可直接还原。
 */
class RestoreMyBatisSqlAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selected = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText
        val text = selected?.takeIf { it.isNotBlank() } ?: MyBatisLogClipboard.readText()
        if (text.isNullOrBlank()) {
            notify(project, message("editor.noText"), NotificationType.WARNING)
            return
        }

        val service = MyBatisLogService.getInstance(project)
        // 与捕获页 / 文本还原工作台共用设置里的前缀、结束标识与 iBatis 开关
        val result = MyBatisLogParser(service.settings.toConfig())
            .feed(if (text.endsWith('\n')) text else "$text\n")
        if (result.added.isEmpty()) {
            notify(project, message("editor.noLog"), NotificationType.WARNING)
            return
        }

        val sql = result.added.joinToString("\n\n") { entry ->
            service.restoredSql(entry.sql, entry.parameters)
        }
        CopyPasteManager.copyTextToClipboard(SensitiveData.maskText(sql))
        notify(project, message("editor.done", result.added.size), NotificationType.INFORMATION)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.text = message("editor.restore")
        e.presentation.description = message("editor.restore.desc")
        e.presentation.isEnabled = e.project != null
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(MyBatisLogPanel.NOTIFICATION_GROUP)
            .createNotification("MyBatis Log", content, type)
            .notify(project)
    }
}
