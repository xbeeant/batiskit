package io.github.xbeeant.batiskit.git.link

/**
 * 远程仓库链接构造（GitToolBox 付费功能 Repository links 的复刻）。
 *
 * 自动识别 github.com / gitlab.com / gitee.com / bitbucket.org / Azure DevOps，
 * 其余托管平台可通过设置里的自定义模板支持
 * （占位符 {remoteUrl} {path} {line} {branch} {sha}）。
 * 全部是纯函数，可直接测试。
 */
object RepositoryLinks {

    /** 统一远程地址：git@github.com:owner/repo.git -> https://github.com/owner/repo */
    fun normalizeRemote(rawUrl: String): String {
        var url = rawUrl.trim().trimEnd('/')
        url = url.removeSuffix(".git")
        // ssh 别名写法 git@host:owner/repo
        val scpLike = Regex("^git@([^:]+):(.+)$")
        scpLike.matchEntire(url)?.let { m ->
            url = "https://${m.groupValues[1]}/${m.groupValues[2]}"
        }
        url = url.replace("git://", "https://")
        url = url.replace("http://", "https://")
        return url
    }

    fun hostOf(remoteUrl: String): String = runCatching {
        java.net.URI(normalizeRemote(remoteUrl)).host?.lowercase().orEmpty()
    }.getOrDefault("")

    /** 是否为 Azure DevOps（dev.azure.com 或 *.visualstudio.com） */
    private fun isAzure(host: String): Boolean =
        host == "dev.azure.com" || host.endsWith(".visualstudio.com")

    /**
     * 文件（可带行号）的远程链接；不认识的托管平台且未配置模板时返回 null。
     */
    fun buildLink(remoteUrl: String, branch: String, path: String, line: Int?, template: String?): String? {
        if (remoteUrl.isBlank() || path.isBlank()) {
            return null
        }
        val normalized = normalizeRemote(remoteUrl)
        val safeBranch = branch.ifBlank { "HEAD" }
        val linkLine = line?.takeIf { it > 0 }

        if (!template.isNullOrBlank()) {
            return template
                .replace("{remoteUrl}", normalized)
                .replace("{path}", path)
                .replace("{line}", (linkLine ?: 1).toString())
                .replace("{branch}", safeBranch)
        }

        val host = hostOf(remoteUrl)
        return when {
            host == "github.com" || host == "gitlab.com" || host == "gitee.com" -> {
                val anchor = linkLine?.let { "#L$it" } ?: ""
                "$normalized/blob/$safeBranch/$path$anchor"
            }

            host == "bitbucket.org" -> {
                val anchor = linkLine?.let { "#lines-$it" } ?: ""
                "$normalized/src/$safeBranch/$path$anchor"
            }

            isAzure(host) -> {
                val linePart = linkLine?.let { "&line=$it&lineEnd=$it&lineStartColumn=1&lineEndColumn=1&lineStyle=plain" } ?: ""
                "$normalized?path=/$path&version=GB$safeBranch$linePart&_a=contents"
            }

            else -> null
        }
    }

    /**
     * 提交详情链接；模板里出现 `{sha}` 时优先用模板，否则按平台拼。
     */
    fun commitLink(remoteUrl: String, sha: String, template: String? = null): String? {
        if (remoteUrl.isBlank() || sha.isBlank()) {
            return null
        }
        val normalized = normalizeRemote(remoteUrl)
        if (!template.isNullOrBlank() && template.contains("{sha}")) {
            return template
                .replace("{remoteUrl}", normalized)
                .replace("{sha}", sha)
        }
        val host = hostOf(remoteUrl)
        return when {
            host == "github.com" || host == "gitlab.com" || host == "gitee.com" -> "$normalized/commit/$sha"
            host == "bitbucket.org" -> "$normalized/commits/$sha"
            isAzure(host) -> "$normalized/commit/$sha"
            else -> null
        }
    }

    /** 仓库根目录链接 */
    fun repoLink(remoteUrl: String): String? {
        if (remoteUrl.isBlank()) {
            return null
        }
        return normalizeRemote(remoteUrl)
    }

    /** 用当前分支创建 Pull Request / Merge Request 的链接 */
    fun pullRequestLink(remoteUrl: String, branch: String): String? {
        if (remoteUrl.isBlank() || branch.isBlank() || branch == "HEAD") {
            return null
        }
        val normalized = normalizeRemote(remoteUrl)
        val host = hostOf(remoteUrl)
        return when {
            host == "github.com" -> "$normalized/compare/$branch?expand=1"
            host == "gitlab.com" -> "$normalized/-/merge_requests/new?merge_request%5Bsource_branch%5D=$branch"
            host == "gitee.com" -> "$normalized/compare/$branch"
            host == "bitbucket.org" -> "$normalized/pull-requests/new?source=$branch"
            isAzure(host) -> "$normalized/pullrequestcreate?sourceRef=$branch"
            else -> null
        }
    }
}
