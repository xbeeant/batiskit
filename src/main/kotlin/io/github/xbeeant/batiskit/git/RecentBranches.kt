package io.github.xbeeant.batiskit.git

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.PersistentStateComponent

/** 单个仓库的最近分支列表（最近使用的在前） */
class RecentBranchEntry {
    var repoPath: String = ""
    var branches: MutableList<String> = mutableListOf()
}

/** 持久化数据：仓库路径 -> 最近使用的本地分支 */
class RecentBranchesState {
    var repos: MutableList<RecentBranchEntry> = mutableListOf()
}

/**
 * 最近使用的本地分支记录（GitToolBox 的 Recent branches switcher）。
 *
 * 只存每个仓库最近 [RecentBranchOrder.MAX] 个分支名，切换分支后由 [GitProjectData]
 * 的刷新过程写入；弹窗里按「最久未使用 -> 最近使用」排序。
 */
@State(name = "BatisKitRecentBranches", storages = [Storage("batiskit-recent-branches.xml")])
@Service(Service.Level.APP)
class RecentBranches : PersistentStateComponent<RecentBranchesState> {

    private var state = RecentBranchesState()

    override fun getState(): RecentBranchesState = state

    override fun loadState(state: RecentBranchesState) {
        this.state = state
    }

    /** 最近使用的分支，最近的在前 */
    fun list(repoPath: String): List<String> =
        state.repos.firstOrNull { it.repoPath == repoPath }?.branches?.toList() ?: emptyList()

    fun record(repoPath: String, branch: String) {
        if (repoPath.isBlank() || branch.isBlank() || branch == "HEAD") {
            return
        }
        val entry = state.repos.firstOrNull { it.repoPath == repoPath }
        if (entry == null) {
            val created = RecentBranchEntry()
            created.repoPath = repoPath
            created.branches = RecentBranchOrder.record(emptyList(), branch).toMutableList()
            state.repos += created
            return
        }
        val updated = RecentBranchOrder.record(entry.branches, branch)
        if (updated != entry.branches) {
            entry.branches = updated.toMutableList()
        }
    }

    companion object {
        fun getInstance(): RecentBranches = ApplicationManager.getApplication().getService(RecentBranches::class.java)
    }
}

/** 最近分支的排序逻辑（纯函数） */
object RecentBranchOrder {

    const val MAX = 5

    /** 记录一次使用：把 [branch] 提到最前，最多保留 [MAX] 个 */
    fun record(existing: List<String>, branch: String): List<String> {
        if (branch.isBlank()) {
            return existing
        }
        return (listOf(branch) + existing.filter { it != branch }).take(MAX)
    }

    /** 弹窗顺序：最久未使用在前（与 GitToolBox 一致） */
    fun forPopup(existing: List<String>): List<String> = existing.reversed()
}
