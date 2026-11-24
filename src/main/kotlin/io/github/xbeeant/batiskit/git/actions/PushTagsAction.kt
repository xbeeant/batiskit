package io.github.xbeeant.batiskit.git.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import io.github.xbeeant.batiskit.git.GitProjectData
import io.github.xbeeant.batiskit.git.GitRepos
import io.github.xbeeant.batiskit.git.ui.GitNotifications
import io.github.xbeeant.batiskit.git.ui.SelectListDialog

/**
 * Push selected tags：列出当前分支上的 tag，多选后 `git push <remote> <tag...>`，
 * 支持 `--force`。
 */
class PushTagsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && GitRepos.all(project).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val repo = GitRepos.forFile(project, file)
        if (repo == null) {
            GitNotifications.warn(project, "当前工程没有 Git 仓库")
            return
        }
        val remote = repo.remotes.firstOrNull { it.name == "origin" }?.name
            ?: repo.remotes.firstOrNull()?.name
        if (remote == null) {
            GitNotifications.warn(project, "仓库 ${repo.root.name} 没有配置 remote")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val tags = mergedTags(project, repo)
            ApplicationManager.getApplication().invokeLater {
                if (tags.isEmpty()) {
                    GitNotifications.info(project, "当前分支没有可推送的 tag")
                    return@invokeLater
                }
                val dialog = SelectListDialog(
                    project,
                    "推送 tag 到 $remote",
                    tags.map { SelectListDialog.Item(it) },
                    extraOption = "强制推送（--force）",
                    okText = "推送选中 tag",
                )
                if (!dialog.showAndGet()) {
                    return@invokeLater
                }
                val chosen = dialog.chosen().map { it.label }
                if (chosen.isEmpty()) {
                    return@invokeLater
                }
                if (dialog.extraSelected()) {
                    val answer = Messages.showYesNoDialog(
                        project,
                        "强制推送会用本地 tag 覆盖远端同名 tag，远端引用可能被改写且难以恢复。确认继续？",
                        "确认强制推送（--force）",
                        Messages.getWarningIcon(),
                    )
                    if (answer != Messages.YES) {
                        return@invokeLater
                    }
                }
                pushTags(project, repo, remote, chosen, dialog.extraSelected())
            }
        }
    }

    private fun mergedTags(project: Project, repo: GitRepository): List<String> {
        val handler = GitLineHandler(project, repo.root, GitCommand.TAG)
        handler.addParameters("--merged", "HEAD", "--sort=-creatordate")
        val result = runCatching { Git.getInstance().runCommand(handler) }.getOrNull() ?: return emptyList()
        if (!result.success()) {
            return emptyList()
        }
        return result.output.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun pushTags(
        project: Project,
        repo: GitRepository,
        remote: String,
        tags: List<String>,
        force: Boolean
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val handler = GitLineHandler(project, repo.root, GitCommand.PUSH)
            if (force) {
                handler.addParameters("--force")
            }
            handler.addParameters(remote)
            tags.forEach { handler.addParameters(it) }
            val outcome = runCatching { Git.getInstance().runCommand(handler) }
            val result = outcome.getOrNull()
            val error = when {
                outcome.isFailure -> outcome.exceptionOrNull()?.message ?: "git push 执行失败"
                result == null -> "git push 执行失败"
                result != null && !result.success() -> result.errorOutputAsJoinedString.ifBlank { "git push 失败" }
                else -> null
            }
            ApplicationManager.getApplication().invokeLater {
                if (error != null) {
                    GitNotifications.error(project, error)
                } else {
                    GitProjectData.getInstance(project).refreshAsync()
                    GitNotifications.info(project, "已推送 ${tags.joinToString(", ")} 到 $remote")
                }
            }
        }
    }
}
