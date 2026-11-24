package io.github.xbeeant.batiskit.git

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vcs.changes.ChangeListManager
import io.github.xbeeant.batiskit.git.blame.BlameInlayManager
import io.github.xbeeant.batiskit.settings.ModuleToggles

/**
 * 启动入口：初始化状态数据 / 自动 fetch / 行内 blame 监听。
 */
class GitToolboxStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (!ModuleToggles.isEnabled(ModuleToggles.GIT)) return
        // 常驻初始化：服务构造时就注册光标监听，设置页勾选 / 取消后无需重启 IDE
        // （开关判断在 BlameInlayManager.handle 里做，关闭时不发 git 命令）
        BlameInlayManager.getInstance(project)

        // 状态变化后刷新 Project View（分支 / ↑↓ / 未提交 / tags 装饰是读缓存的）
        GitProjectData.getInstance(project).addUpdateListener {
            runCatching { ProjectView.getInstance(project).refresh() }
        }

        val settings = GitSettings.getInstance().state
        if (!settings.autoFetchEnabled && !settings.behindTrackerEnabled) return

        val data = GitProjectData.getInstance(project)
        data.refreshAsync()
        if (settings.autoFetchEnabled) {
            AutoFetcher.getInstance(project).start()
        }
    }
}

/**
 * 关闭工程时的 pending changes 检查（GitToolBox 付费功能复刻）：
 * 有未提交改动或待推送提交时给出提醒。
 */
class PendingChangesChecker : com.intellij.openapi.project.ProjectManagerListener {

    override fun projectClosing(project: Project) {
        if (!ModuleToggles.isEnabled(ModuleToggles.GIT)) return
        val settings = GitSettings.getInstance().state
        if (!settings.pendingCheckEnabled) return

        val data = GitProjectData.getInstance(project)
        val uncommitted = runCatching {
            ChangeListManager.getInstance(project).defaultChangeList.changes.size
        }.getOrDefault(data.uncommittedCount)
        val ahead = data.totalAhead
        val behind = data.totalBehind
        if (uncommitted <= 0 && ahead <= 0) return

        val message = buildString {
            if (uncommitted > 0) append("有 $uncommitted 个文件未提交")
            if (ahead > 0) {
                if (isNotEmpty()) append("，")
                append("有 $ahead 个提交待推送")
            }
            if (behind > 0) {
                if (isNotEmpty()) append("，")
                append("分支落后远端 $behind 个提交")
            }
            append("。")
        }
        // 工程正在关闭，用应用级通知兜底
        runCatching {
            ApplicationManager.getApplication().invokeLater {
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup(GitProjectData.NOTIFICATION_GROUP)
                    .createNotification("Git 工作区提醒", message, com.intellij.notification.NotificationType.WARNING)
                    .notify(project)
            }
        }
    }
}
