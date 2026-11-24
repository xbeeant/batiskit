package io.github.xbeeant.batiskit.git

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager

/** 仓库定位的小工具：动作里到处都要「当前文件属于哪个仓库」 */
object GitRepos {

    /** 文件所属仓库（快速路径失败时按路径前缀兜底，兼容未索引目录） */
    fun forFile(project: Project, file: VirtualFile?): GitRepository? {
        val manager = GitRepositoryManager.getInstance(project)
        if (file == null) {
            return manager.repositories.firstOrNull()
        }
        return manager.getRepositoryForFileQuick(file)
            ?: manager.repositories
                .filter { GitPaths.relativePath(it.root.path, file.path) != null }
                .maxByOrNull { it.root.path.length }
            ?: manager.repositories.firstOrNull()
    }

    fun all(project: Project): List<GitRepository> = GitRepositoryManager.getInstance(project).repositories

    fun currentBranchOrHead(repo: GitRepository): String = repo.currentBranch?.name ?: "HEAD"

    fun localBranchNames(repo: GitRepository): List<String> =
        repo.branches.localBranches.map { it.name }
}
