package io.github.xbeeant.batiskit.git.link

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepository
import io.github.xbeeant.batiskit.git.GitRepos
import io.github.xbeeant.batiskit.git.GitSettings
import io.github.xbeeant.batiskit.git.blame.GitBlameService
import io.github.xbeeant.batiskit.git.ui.GitNotifications
import java.awt.datatransfer.StringSelection

/** 远程链接的目标 */
enum class RemoteLinkTarget { REPO_ROOT, COMMIT, PULL_REQUEST }

/**
 * 仓库根 / 提交 / PR 三类远程链接动作（文件级链接见 [RepositoryLinkAction]）。
 */
abstract class RemoteLinkAction(
    private val target: RemoteLinkTarget,
    private val open: Boolean
) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && e.getData(CommonDataKeys.VIRTUAL_FILE) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val line = (editor?.caretModel?.logicalPosition?.line ?: 0) + 1
        val repo = GitRepos.forFile(project, file) ?: return
        val remoteUrl = remoteUrlOf(repo)
        if (remoteUrl == null) {
            GitNotifications.warn(project, "仓库 ${repo.root.name} 没有配置 remote")
            return
        }
        val template = GitSettings.getInstance().state.remoteLinkTemplate
        when (target) {
            RemoteLinkTarget.REPO_ROOT -> apply(project, RepositoryLinks.repoLink(remoteUrl))
            RemoteLinkTarget.PULL_REQUEST -> apply(
                project,
                RepositoryLinks.pullRequestLink(remoteUrl, GitRepos.currentBranchOrHead(repo))
            )

            RemoteLinkTarget.COMMIT -> resolveCommitLink(project, file, line, remoteUrl, template)
        }
    }

    private fun resolveCommitLink(project: Project, file: VirtualFile, line: Int, remoteUrl: String, template: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val info = runCatching { GitBlameService.getInstance(project).blame(file, line) }.getOrNull()
            val link = info?.takeUnless { it.uncommitted }
                ?.let { RepositoryLinks.commitLink(remoteUrl, it.sha, template) }
            ApplicationManager.getApplication().invokeLater {
                if (link == null) {
                    GitNotifications.warn(project, "当前行没有可链接的提交（可能是未提交改动）")
                } else {
                    apply(project, link)
                }
            }
        }
    }

    private fun apply(project: Project, link: String?) {
        if (link == null) {
            GitNotifications.warn(project, "无法识别远程仓库地址，可在设置里配置链接模板")
            return
        }
        if (open) {
            BrowserUtil.browse(link)
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(link))
        GitNotifications.info(project, "已复制：$link")
    }

    private fun remoteUrlOf(repo: GitRepository): String? =
        (repo.remotes.firstOrNull { it.name == "origin" } ?: repo.remotes.firstOrNull())
            ?.urls?.firstOrNull()
}

/** 复制仓库根目录链接 */
class CopyRepoRootLinkAction : RemoteLinkAction(RemoteLinkTarget.REPO_ROOT, open = false)

/** 在浏览器打开仓库根目录 */
class OpenRepoRootLinkAction : RemoteLinkAction(RemoteLinkTarget.REPO_ROOT, open = true)

/** 复制当前行提交的远程链接 */
class CopyCommitLinkAction : RemoteLinkAction(RemoteLinkTarget.COMMIT, open = false)

/** 在浏览器打开当前行提交 */
class OpenCommitLinkAction : RemoteLinkAction(RemoteLinkTarget.COMMIT, open = true)

/** 用当前分支创建 Pull Request */
class OpenPullRequestAction : RemoteLinkAction(RemoteLinkTarget.PULL_REQUEST, open = true)
