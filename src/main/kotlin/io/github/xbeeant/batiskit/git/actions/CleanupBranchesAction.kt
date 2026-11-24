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
import io.github.xbeeant.batiskit.git.GitSettings
import io.github.xbeeant.batiskit.git.config.BranchCandidate
import io.github.xbeeant.batiskit.git.config.BranchCleanup
import io.github.xbeeant.batiskit.git.config.GitPatterns
import io.github.xbeeant.batiskit.git.ui.GitNotifications
import io.github.xbeeant.batiskit.git.ui.SelectListDialog

/**
 * Git Branches Cleanup：列出「已合并到 base」与「未合并但陈旧」的本地分支，
 * 多选后删除（已合并用 `-d`，陈旧的用 `-D`）。
 */
class CleanupBranchesAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && GitRepos.all(project).isNotEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val repo = GitRepos.forFile(project, e.getData(CommonDataKeys.VIRTUAL_FILE))
        if (repo == null) {
            GitNotifications.warn(project, "当前工程没有 Git 仓库")
            return
        }
        val state = GitSettings.getInstance().state
        val staleDays = state.branchStaleDays.coerceAtLeast(1)
        ApplicationManager.getApplication().executeOnPooledThread {
            val base = baseBranch(repo, state.branchCleanupBase)
            val merged = BranchCleanup.parseBranchLines(gitBranchLines(project, repo, "--merged", base))
            val notMerged = BranchCleanup.parseBranchLines(gitBranchLines(project, repo, "--no-merged", base))
            val nowSeconds = System.currentTimeMillis() / 1000
            val idle = notMerged.associateWith { branch ->
                val time = lastCommitSeconds(project, repo, branch) ?: nowSeconds
                ((nowSeconds - time) / 86400).toInt()
            }
            val candidates = BranchCleanup.select(
                merged = merged,
                notMergedIdle = idle,
                current = repo.currentBranch?.name,
                base = base,
                excludes = GitPatterns.parseCsv(state.branchCleanupExcludes),
                staleDays = staleDays
            )
            ApplicationManager.getApplication().invokeLater {
                if (candidates.isEmpty()) {
                    GitNotifications.info(project, "没有可清理的分支（base: $base）")
                    return@invokeLater
                }
                val dialog = SelectListDialog(
                    project,
                    "清理分支（base: $base，未见更新超过 $staleDays 天视为陈旧）",
                    candidates.map {
                        SelectListDialog.Item(
                            it.branch,
                            if (it.merged) "已合并" else "未合并，${it.idleDays} 天未更新"
                        )
                    },
                    okText = "删除选中分支",
                    warning = "删除后不可恢复：本地分支一旦删除，未合并的提交可能无法找回。",
                )
                if (!dialog.showAndGet()) {
                    return@invokeLater
                }
                val chosen = dialog.chosen().map { it.label }.toSet()
                val targets = candidates.filter { it.branch in chosen }
                if (targets.isEmpty()) {
                    return@invokeLater
                }
                val unmerged = targets.count { !it.merged }
                val message = buildString {
                    append("将永久删除 ${targets.size} 个本地分支：${targets.joinToString(", ") { it.branch }}。")
                    if (unmerged > 0) append("其中 $unmerged 个尚未合并到 base。")
                    append("此操作不可撤销，确认继续？")
                }
                val answer = Messages.showYesNoDialog(
                    project, message, "确认删除分支（不可恢复）", Messages.getWarningIcon(),
                )
                if (answer != Messages.YES) {
                    return@invokeLater
                }
                deleteBranches(project, repo, targets)
            }
        }
    }

    /** 基础分支：设置优先，其次 main / master，最后当前分支 */
    private fun baseBranch(repo: GitRepository, configured: String): String {
        val trimmed = configured.trim()
        if (trimmed.isNotEmpty()) {
            return trimmed
        }
        val locals = GitRepos.localBranchNames(repo)
        return locals.firstOrNull { it == "main" }
            ?: locals.firstOrNull { it == "master" }
            ?: GitRepos.currentBranchOrHead(repo)
    }

    private fun gitBranchLines(project: Project, repo: GitRepository, vararg args: String): List<String> {
        val handler = GitLineHandler(project, repo.root, GitCommand.BRANCH)
        args.forEach { handler.addParameters(it) }
        val result = runCatching { Git.getInstance().runCommand(handler) }.getOrNull() ?: return emptyList()
        if (!result.success()) {
            return emptyList()
        }
        return result.output
    }

    /** 分支最后一次提交的秒级时间戳 */
    private fun lastCommitSeconds(project: Project, repo: GitRepository, branch: String): Long? {
        val handler = GitLineHandler(project, repo.root, GitCommand.LOG)
        handler.addParameters("-1", "--format=%ct", branch)
        val result = runCatching { Git.getInstance().runCommand(handler) }.getOrNull() ?: return null
        if (!result.success()) {
            return null
        }
        return result.outputAsJoinedString.trim().toLongOrNull()
    }

    private fun deleteBranches(project: Project, repo: GitRepository, targets: List<BranchCandidate>) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val failures = mutableListOf<String>()
            targets.forEach { target ->
                val handler = GitLineHandler(project, repo.root, GitCommand.BRANCH)
                // 已合并用 -d（git 会再确认一次），陈旧未合并分支用 -D
                handler.addParameters(if (target.merged) "-d" else "-D", target.branch)
                val result = runCatching { Git.getInstance().runCommand(handler) }.getOrNull()
                if (result == null || !result.success()) {
                    failures += target.branch
                }
            }
            ApplicationManager.getApplication().invokeLater {
                GitProjectData.getInstance(project).refreshAsync()
                val deleted = targets.size - failures.size
                if (failures.isEmpty()) {
                    GitNotifications.info(project, "已删除 $deleted 个分支")
                } else {
                    GitNotifications.warn(project, "已删除 $deleted 个分支，失败：${failures.joinToString(", ")}")
                }
            }
        }
    }
}
