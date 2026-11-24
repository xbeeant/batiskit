package io.github.xbeeant.batiskit.git

/**
 * 仓库路径计算的纯函数集合（便于单测）。
 *
 * 入参一般来自 `VirtualFile.path`（以 `/` 分隔），但 Windows 上 `File.path` 会给出 `\`，
 * 因此这里先把两种分隔符统一成 `/` 再比较 —— 否则 Windows 下根部与文件永远比较不上，
 * 相对路径恒为 null（blame / 远程链接都会直接失效）。
 */
object GitPaths {

    /**
     * 相对 [rootPath] 的路径，统一用 `/` 分隔；
     * 文件不在仓库内或是仓库根目录本身时返回 null。
     */
    fun relativePath(rootPath: String, filePath: String): String? {
        val rootParts = split(rootPath)
        val fileParts = split(filePath)
        if (rootParts.isEmpty() || fileParts.size <= rootParts.size) {
            return null
        }
        for (i in rootParts.indices) {
            if (!rootParts[i].equals(fileParts[i], ignoreCase = CASE_INSENSITIVE)) {
                return null
            }
        }
        return fileParts.drop(rootParts.size).joinToString("/")
    }

    /** Windows 盘符大小写不敏感，其余平台按原样比较 */
    private val CASE_INSENSITIVE = java.io.File.separatorChar == '\\'

    /** 拆分路径：统一分隔符、去掉空段（首尾 `/`、`//` 都不影响结果） */
    private fun split(path: String): List<String> =
        path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
}
