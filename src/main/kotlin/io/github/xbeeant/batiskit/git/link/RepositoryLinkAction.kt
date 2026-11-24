package io.github.xbeeant.batiskit.git.link

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepositoryManager
import io.github.xbeeant.batiskit.git.GitPaths
import java.awt.datatransfer.StringSelection

/**
 * 复制 / 打开远程仓库文件链接（GitHub / GitLab / Gitee / Bitbucket / 自定义模板）。
 */
abstract class RepositoryLinkAction(private val openInBrowser: Boolean) : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && file != null && findRepo(project, file) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val repo = findRepo(project, file) ?: return
        val relative = GitPaths.relativePath(repo.root.path, file.path) ?: return

        val remote = repo.remotes.firstOrNull { it.name == "origin" } ?: repo.remotes.firstOrNull()
        val remoteUrl = remote?.urls?.firstOrNull()
        val branch = repo.currentBranch?.name ?: "HEAD"
        val line = editor?.caretModel?.logicalPosition?.line?.plus(1)
        val template = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(io.github.xbeeant.batiskit.git.GitSettings::class.java).state.remoteLinkTemplate

        val link = RepositoryLinks.buildLink(remoteUrl ?: "", branch, relative, line, template)
        if (link == null) {
            Messages.showInfoMessage(
                project,
                "无法识别远程仓库 ${remoteUrl.orEmpty()}，可在 Settings → Tools → Batis Kit → Git Toolbox 里配置链接模板",
                "远程仓库链接"
            )
            return
        }
        if (openInBrowser) {
            com.intellij.ide.BrowserUtil.browse(link)
        } else {
            CopyPasteManager.getInstance().setContents(StringSelection(link))
        }
    }

    private fun findRepo(project: Project, file: VirtualFile) =
        GitRepositoryManager.getInstance(project).getRepositoryForFileQuick(file)
            ?: GitRepositoryManager.getInstance(project).repositories
                .firstOrNull { file.path.startsWith(it.root.path) }
}

/** 复制远程文件链接到剪贴板 */
class CopyRemoteLinkAction : RepositoryLinkAction(openInBrowser = false)

/** 在浏览器打开远程文件链接 */
class OpenRemoteLinkAction : RepositoryLinkAction(openInBrowser = true)
