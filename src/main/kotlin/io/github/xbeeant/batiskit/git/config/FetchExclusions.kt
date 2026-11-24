package io.github.xbeeant.batiskit.git.config

/**
 * 自动 fetch 的排除规则（GitToolBox 的 Auto Fetch Exclusions）。
 *
 * 排除项可以是仓库绝对路径（前缀匹配，用来整仓跳过），也可以是 remote 名（只跳过该 remote）。
 */
object FetchExclusions {

    private val CASE_INSENSITIVE = java.io.File.separatorChar == '\\'

    /** 仓库被整仓排除？ */
    fun repoExcluded(repoPath: String, exclusions: List<String>): Boolean =
        exclusions.any { it.isNotEmpty() && repoPath.startsWith(it, ignoreCase = CASE_INSENSITIVE) }

    /** 单个 remote 被排除？ */
    fun remoteExcluded(remoteName: String, exclusions: List<String>): Boolean =
        exclusions.any { it.equals(remoteName, ignoreCase = CASE_INSENSITIVE) }

    /** 需要实际执行 fetch 的 remote 名列表 */
    fun remotesToFetch(remotes: List<String>, exclusions: List<String>): List<String> =
        remotes.filter { !remoteExcluded(it, exclusions) }
}
