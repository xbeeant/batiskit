package io.github.xbeeant.batiskit.git.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import io.github.xbeeant.batiskit.git.GitProjectData

/** Git Toolbox 通知的统一出口（notificationGroup 在 plugin.xml 里注册） */
object GitNotifications {

    fun info(project: Project, content: String) = notify(project, content, NotificationType.INFORMATION)

    fun warn(project: Project, content: String) = notify(project, content, NotificationType.WARNING)

    fun error(project: Project, content: String) = notify(project, content, NotificationType.ERROR)

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GitProjectData.NOTIFICATION_GROUP)
            .createNotification("Git Toolbox", content, type)
            .notify(project)
    }
}
