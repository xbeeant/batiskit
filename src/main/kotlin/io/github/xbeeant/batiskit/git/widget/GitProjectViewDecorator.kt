package io.github.xbeeant.batiskit.git.widget

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import io.github.xbeeant.batiskit.git.GitProjectData
import io.github.xbeeant.batiskit.git.RepoStatus

/**
 * Project View 里的 Git 状态显示（GitToolBox 的 Git status display）：
 * 仓库根节点后面追加 `main ↑1 ↓2 ~3 [v1.0]`，无改动时 `∅`。
 *
 * 数据来自 [GitProjectData] 的后台缓存，这里只读内存，不触发 git 命令。
 */
class GitProjectViewDecorator : ProjectViewNodeDecorator {

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val file = node.virtualFile ?: return
        val status = GitProjectData.getInstance(node.project).statuses
            .firstOrNull { it.rootPath == file.path } ?: return
        val suffix = suffixOf(status)
        if (suffix.isEmpty()) {
            return
        }
        val text = data.presentableText ?: file.name
        if (!text.endsWith(suffix)) {
            data.presentableText = text + suffix
        }
    }

    /** 纯函数：节点后缀文本 */
    fun suffixOf(status: RepoStatus): String = buildString {
        append("  ").append(status.branch)
        if (status.ahead > 0) {
            append(" ↑").append(status.ahead)
        }
        if (status.behind > 0) {
            append(" ↓").append(status.behind)
        }
        if (status.uncommitted > 0) {
            append(" ~").append(status.uncommitted)
        }
        status.tags.forEach { append(" [").append(it).append("]") }
        if (status.ahead == 0 && status.behind == 0 && status.uncommitted == 0 && status.tags.isEmpty()) {
            append(" ∅")
        }
    }
}
