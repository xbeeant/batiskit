package io.github.xbeeant.batiskit.git.config

/** 清理候选分支 */
data class BranchCandidate(
    val branch: String,
    /** 已合并到 base（可 `git branch -d` 安全删除） */
    val merged: Boolean,
    /** 未合并分支距最后一次提交的天数 */
    val idleDays: Int = 0
)

/**
 * 分支清理（GitToolBox 的 Branch cleanup）的纯逻辑：
 * 已合并分支 + 未合并但陈旧的分支，排除当前分支 / base / glob 排除项。
 */
object BranchCleanup {

    /** 解析 `git branch` 输出：去掉 `* `/`+ ` 前缀与空白 */
    fun parseBranchLines(lines: List<String>): List<String> =
        lines.map { it.trim().removePrefix("* ").removePrefix("+ ").trim() }
            .filter { it.isNotEmpty() }

    /**
     * 生成候选列表：[notMergedIdle] 是「未合并分支 -> 空闲天数」。
     */
    fun select(
        merged: List<String>,
        notMergedIdle: Map<String, Int>,
        current: String?,
        base: String,
        excludes: List<String>,
        staleDays: Int
    ): List<BranchCandidate> {
        fun usable(branch: String): Boolean =
            branch != current && branch != base && branch.isNotBlank() && !GitPatterns.anyMatches(excludes, branch)

        val mergedCandidates = merged.filter(::usable)
            .map { BranchCandidate(it, merged = true) }
        val staleCandidates = notMergedIdle.entries
            .filter { (branch, idleDays) -> usable(branch) && idleDays > staleDays }
            .map { (branch, idleDays) -> BranchCandidate(branch, merged = false, idleDays = idleDays) }
        return mergedCandidates + staleCandidates
    }
}
