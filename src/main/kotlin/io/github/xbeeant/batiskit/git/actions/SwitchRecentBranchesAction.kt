package io.github.xbeeant.batiskit.git.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleTextAttributes
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import io.github.xbeeant.batiskit.git.GitProjectData
import io.github.xbeeant.batiskit.git.GitRepos
import io.github.xbeeant.batiskit.git.RecentBranchOrder
import io.github.xbeeant.batiskit.git.RecentBranches
import io.github.xbeeant.batiskit.git.ui.GitNotifications

/** 最近使用的分支（弹窗条目） */
data class RecentBranchItem(val repo: GitRepository, val branch: String, val multiRepo: Boolean) {
    override fun toString(): String = branch
}

/**
 * Switch Recent Git Branches：弹窗列出各仓库最近使用的本地分支，
 * 顺序为「最久未使用 -> 最近使用」，选中后执行 `git checkout`。
 */
class SwitchRecentBranchesAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && GitRepos.all(project).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val items = recentItems(project)
        if (items.isEmpty()) {
            GitNotifications.info(project, "没有可切换的分支")
            return
        }
        val builder = JBPopupFactory.getInstance().createPopupChooserBuilder(items)
        builder.setTitle("切换最近使用的分支")
            .setRenderer(object : com.intellij.ui.ColoredListCellRenderer<RecentBranchItem>() {
                override fun customizeCellRenderer(
                    list: javax.swing.JList<out RecentBranchItem>,
                    value: RecentBranchItem?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean
                ) {
                    if (value == null) {
                        return
                    }
                    append(value.branch)
                    if (value.multiRepo) {
                        append("  " + value.repo.root.name, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                }
            })
            .setItemChosenCallback { checkout(project, it) }
            .createPopup()
            .showInFocusCenter()
    }

    private fun recentItems(project: com.intellij.openapi.project.Project): List<RecentBranchItem> {
        val repos = GitRepos.all(project)
        val multiRepo = repos.size > 1
        val recents = RecentBranches.getInstance()
        return repos.flatMap { repo ->
            val current = repo.currentBranch?.name
            val existing = GitRepos.localBranchNames(repo).toSet()
            val recorded = RecentBranchOrder.forPopup(recents.list(repo.root.path))
                .filter { it != current && it in existing }
            // 没有历史记录时退化为「当前仓库的其它本地分支」，避免首次使用时弹窗为空
            val branches = recorded.ifEmpty {
                existing.filter { it != current }.sorted().take(RecentBranchOrder.MAX)
            }
            branches.map { RecentBranchItem(repo, it, multiRepo) }
        }
    }

    private fun checkout(project: com.intellij.openapi.project.Project, item: RecentBranchItem) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val handler = GitLineHandler(project, item.repo.root, GitCommand.CHECKOUT)
            handler.addParameters(item.branch)
            val outcome = runCatching { Git.getInstance().runCommand(handler) }
            val result = outcome.getOrNull()
            val error = when {
                outcome.isFailure -> outcome.exceptionOrNull()?.message ?: "git checkout 执行失败"
                result == null -> "git checkout 执行失败"
                result != null && !result.success() -> result.errorOutputAsJoinedString.ifBlank { "git checkout 失败" }
                else -> null
            }
            ApplicationManager.getApplication().invokeLater {
                if (error != null) {
                    GitNotifications.error(project, error)
                } else {
                    GitProjectData.getInstance(project).refreshAsync()
                    GitNotifications.info(project, "已切换到分支 ${item.branch}")
                }
            }
        }
    }
}
