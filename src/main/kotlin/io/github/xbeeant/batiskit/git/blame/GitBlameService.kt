package io.github.xbeeant.batiskit.git.blame

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import io.github.xbeeant.batiskit.git.GitPaths
import io.github.xbeeant.batiskit.git.GitText
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

/** 一行代码的 blame 信息 */
data class BlameInfo(
    /** 提交 sha；未提交行为全 0 */
    val sha: String,
    val author: String,
    val subject: String,
    /** 相对时间文本，例如 3 天前 */
    val whenText: String,
    /** 未提交的行（工作区改动）为 true */
    val uncommitted: Boolean,
    /** 完整提交信息按行（未提交/拿不到完整消息时只有单行标题）；多于 1 行时编辑器下方以块状 inlay 多行显示 */
    val messageLines: List<String> = emptyList()
)

/** 整个文件的 blame 缓存：一次 git blame 覆盖所有行，光标在文件内移动零 git 进程 */
class FileBlame internal constructor(
    val byLine: Map<Int, BlameInfo>,
    internal val loadedAt: Long
)

/**
 * 当前行 blame：优先命中文件级缓存；未命中时对光标所在行执行 `git blame --porcelain -L n,n`，
 * 并后台预热整个文件的 blame（一次调用覆盖所有行，老仓库历史再长也只跑一次）。
 *
 * 注意：porcelain 的 `summary` 只是 commit message 的第一个物理行，
 * 标题折行或有正文时信息不全 —— 因此拿到 sha 后再用 `git show -s --format=%B`
 * 取完整消息：subject 按 git 规则（空行前各行去尾空白后用空格连接）重组，
 * 全部消息行放进 [BlameInfo.messageLines] 供多行显示；结果按 sha 做 LRU 缓存。
 *
 * 文件级缓存只在「提交 / 切分支等仓库变化」或超过 [FILE_BLAME_TTL_MS] 时失效 ——
 * 编辑器里的修改不影响 blame 归属（未提交行始终显示 Not Committed Yet），
 * 因此不会因为打字频繁失效重算。
 */
@Service(Service.Level.PROJECT)
class GitBlameService(private val project: Project) : Disposable {

    /** sha -> 完整提交信息（%B 原文）；同一提交反复 blame 时避免重复起 git 进程 */
    private val messageCache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > MESSAGE_CACHE_SIZE
    }

    /** 文件级 blame 缓存：路径 -> 整个文件各行的 blame */
    private val fileBlameCache = ConcurrentHashMap<String, FileBlame>()

    /** 正在预热整文件 blame 的路径，避免同文件重复起后台任务 */
    private val prewarming = ConcurrentHashMap.newKeySet<String>()

    init {
        // 提交 / 切分支 / fetch 等仓库变化时整表失效（编辑器修改不影响 blame 归属，不按文档变化失效）
        project.messageBus.connect(this).subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener {
            fileBlameCache.clear()
        })
    }

    fun blame(file: VirtualFile, oneBasedLine: Int): BlameInfo? {
        blameCached(file, oneBasedLine)?.let { return it }
        val info = blameLine(file, oneBasedLine) ?: return null
        // 按行查到结果后，后台把整个文件的 blame 算出来，后续行移动零 git 进程
        prewarmFileBlame(file)
        return info
    }

    /** 文件级缓存命中时直接返回（零 git 进程）；过期条目惰性清除 */
    fun blameCached(file: VirtualFile, oneBasedLine: Int): BlameInfo? {
        val cached = fileBlameCache[file.path] ?: return null
        if (System.currentTimeMillis() - cached.loadedAt > FILE_BLAME_TTL_MS) {
            fileBlameCache.remove(file.path)
            return null
        }
        return cached.byLine[oneBasedLine]
    }

    /** 后台预热整个文件的 blame；同一路径同时只跑一次，失败静默（下次再试） */
    fun prewarmFileBlame(file: VirtualFile) {
        if (fileBlameCache.containsKey(file.path)) {
            return
        }
        if (!prewarming.add(file.path)) {
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                loadFileBlame(file)
            } catch (e: Exception) {
                com.intellij.openapi.diagnostic.Logger.getInstance(GitBlameService::class.java)
                    .warn("预取 blame 失败：${file.path}", e)
            } finally {
                prewarming.remove(file.path)
            }
        }
    }

    /** 一次性 blame 整个文件并写缓存；同时批量预取涉及提交的完整提交信息 */
    fun loadFileBlame(file: VirtualFile): FileBlame? {
        val manager = GitRepositoryManager.getInstance(project)
        val repo = manager.getRepositoryForFileQuick(file)
            ?: manager.repositories.firstOrNull { file.path.startsWith(it.root.path) }
            ?: return null
        val relative = GitPaths.relativePath(repo.root.path, file.path) ?: return null

        var byLine = runFileBlame(repo.root, relative, Charsets.UTF_8) ?: return null
        if (byLine.values.any { isGarbled(it) }) {
            // 老仓库的提交信息可能是 GBK / GB18030：UTF-8 解出替换符时换个字符集重跑一次
            runFileBlame(repo.root, relative, GitText.LEGACY_CHARSET)?.let { byLine = it }
        }
        prefetchMessages(repo.root, byLine.values.mapNotNull { if (it.uncommitted) null else it.sha }.distinct())
        val enriched = byLine.mapValues { (_, info) -> enrich(info) }
        val fileBlame = FileBlame(enriched, System.currentTimeMillis())
        fileBlameCache[file.path] = fileBlame
        return fileBlame
    }

    private fun blameLine(file: VirtualFile, oneBasedLine: Int): BlameInfo? {
        val manager = GitRepositoryManager.getInstance(project)
        val repo = manager.getRepositoryForFileQuick(file)
            ?: manager.repositories.firstOrNull { file.path.startsWith(it.root.path) }
            ?: return null
        val relative = GitPaths.relativePath(repo.root.path, file.path) ?: return null
        val now = System.currentTimeMillis()

        var info = readBlame(repo.root, relative, oneBasedLine, Charsets.UTF_8, now) ?: return null
        if (isGarbled(info)) {
            readBlame(repo.root, relative, oneBasedLine, GitText.LEGACY_CHARSET, now)
                ?.takeUnless { isGarbled(it) }
                ?.let { info = it }
        }
        return enrich(info)
    }

    /** 补全提交信息：subject 重组 + 多行消息（块状 inlay 用），依赖 messageCache 里的 %B 原文 */
    private fun enrich(info: BlameInfo): BlameInfo {
        if (info.uncommitted) {
            return info
        }
        val message = synchronized(messageCache) { messageCache[info.sha] } ?: return info
        val lines = message.lines()
            .dropWhile { it.isBlank() }
            .dropLastWhile { it.isBlank() }
        if (lines.isEmpty()) {
            return info
        }
        val subject = subjectOf(lines).ifEmpty { info.subject }
        return info.copy(subject = subject, messageLines = lines)
    }

    /** 作者 / 标题里有解码失败产生的替换符，说明命令输出不是 UTF-8 */
    private fun isGarbled(info: BlameInfo): Boolean =
        GitText.looksGarbled(info.author) || GitText.looksGarbled(info.subject)

    /** 执行 blame 并按指定字符集解码输出 */
    private fun readBlame(
        root: VirtualFile,
        relative: String,
        oneBasedLine: Int,
        charset: Charset,
        nowMillis: Long
    ): BlameInfo? {
        val handler = GitLineHandler(project, root, GitCommand.BLAME)
        handler.setCharset(charset)
        handler.addParameters("--porcelain", "-L", "$oneBasedLine,$oneBasedLine")
        handler.addParameters("--")
        handler.addParameters(relative)
        val result = runCatching { Git.getInstance().runCommand(handler) }.getOrNull() ?: return null
        if (!result.success()) {
            return null
        }
        return parsePorcelain(result.output, nowMillis)
    }

    /** 一次性 blame 整个文件（`--porcelain -l`，完整 sha）；UTF-8 出现乱码时换老字符集重跑 */
    private fun runFileBlame(root: VirtualFile, relative: String, charset: Charset): Map<Int, BlameInfo>? {
        val handler = GitLineHandler(project, root, GitCommand.BLAME)
        handler.setCharset(charset)
        handler.addParameters("--porcelain", "-l")
        handler.addParameters("--")
        handler.addParameters(relative)
        val result = runCatching { Git.getInstance().runCommand(handler) }.getOrNull() ?: return null
        if (!result.success()) {
            return null
        }
        return parseFullPorcelain(result.output, System.currentTimeMillis())
    }

    /**
     * 批量预取提交信息：一次 `git show -s --format=%H%n%B <sha...>` 覆盖文件涉及的全部提交
     * （替代每行一次的 git show），写入 messageCache 供 enrich 使用。
     * 超过 [MAX_PREFETCH_SHAS] 个提交时只预取前 N 个，其余按需走单条 fullMessage 的 LRU。
     */
    private fun prefetchMessages(root: VirtualFile, shas: List<String>) {
        val missing = shas.filter { sha -> synchronized(messageCache) { messageCache[sha] } == null }
            .take(MAX_PREFETCH_SHAS)
        if (missing.isEmpty()) {
            return
        }
        if (fetchMessageBatch(root, missing, Charsets.UTF_8, forceUtf8 = true)) {
            return
        }
        // UTF-8 解出乱码时用老字符集重跑一次（与单条 readMessage 的重试策略一致）
        fetchMessageBatch(root, missing, GitText.LEGACY_CHARSET, forceUtf8 = false)
    }

    private fun fetchMessageBatch(root: VirtualFile, shas: List<String>, charset: Charset, forceUtf8: Boolean): Boolean {
        val config = if (forceUtf8) {
            listOf("i18n.logOutputEncoding=UTF-8")
        } else {
            emptyList()
        }
        val handler = GitLineHandler(project, root, GitCommand.SHOW, config)
        handler.setCharset(charset)
        handler.addParameters("-s", "--format=%H%n%B")
        handler.addParameters(shas)
        val result = runCatching { Git.getInstance().runCommand(handler) }.getOrNull() ?: return false
        if (!result.success()) {
            return false
        }
        val parsed = parseShowBatch(result.output)
        if (parsed.isEmpty()) {
            return false
        }
        val garbled = parsed.values.any { GitText.looksGarbled(it) }
        synchronized(messageCache) {
            parsed.forEach { (sha, message) ->
                if (!garbled || !GitText.looksGarbled(message)) {
                    messageCache.putIfAbsent(sha, message)
                }
            }
        }
        return !garbled
    }

    override fun dispose() {
        fileBlameCache.clear()
    }

    /** 取提交的完整提交信息（%B，带缓存）；失败时返回 null，调用方回退到 porcelain summary */
    private fun fullMessage(root: VirtualFile, sha: String): String? {
        synchronized(messageCache) { messageCache[sha] }?.let { return it }

        var message = readMessage(root, sha, Charsets.UTF_8, forceUtf8 = true) ?: return null
        if (GitText.looksGarbled(message)) {
            readMessage(root, sha, GitText.LEGACY_CHARSET, forceUtf8 = false)
                ?.takeUnless { GitText.looksGarbled(it) }
                ?.let { message = it }
        }
        synchronized(messageCache) { messageCache[sha] = message }
        return message
    }

    /**
     * 执行 `git show -s --format=%B`。
     *
     * [forceUtf8] 时带上 `-c i18n.logOutputEncoding=UTF-8`：提交里声明了 encoding 的
     * （例如 GBK 提交）由 git 负责转成 UTF-8；没声明 encoding 的老提交只能靠调用方换字符集重试。
     */
    private fun readMessage(root: VirtualFile, sha: String, charset: Charset, forceUtf8: Boolean): String? {
        val config = if (forceUtf8) {
            listOf("i18n.logOutputEncoding=UTF-8")
        } else {
            emptyList()
        }
        val handler = GitLineHandler(project, root, GitCommand.SHOW, config)
        handler.setCharset(charset)
        handler.addParameters("-s", "--format=%B")
        handler.addParameters(sha)
        val result = runCatching { Git.getInstance().runCommand(handler) }.getOrNull() ?: return null
        if (!result.success()) {
            return null
        }
        return result.output.joinToString("\n").trim('\n')
    }

    companion object {
        fun getInstance(project: Project): GitBlameService = project.service<GitBlameService>()

        private const val MESSAGE_CACHE_SIZE = 512

        /** 文件级 blame 缓存的有效期；兜底防御（正常靠仓库变化事件失效） */
        private const val FILE_BLAME_TTL_MS = 5 * 60 * 1000L

        /** 批量预取提交信息的上限，避免超长历史时输出过大 */
        private const val MAX_PREFETCH_SHAS = 1000

        private val FILE_HEADER = Regex("^([0-9a-f]{40}) (\\d+) (\\d+)(?: (\\d+))?$")

        /**
         * 解析 `git blame --porcelain -l <file>` 的整文件输出（纯函数，方便测试）。
         *
         * porcelain 每个块 = 40 位 sha 头（`sha origLine finalLine [numLines]`）+ 元数据行 + 若干 `\t` 内容行；
         * 用头的 finalLine / numLines 直接算出行号区间，不需要数内容行。
         */
        fun parseFullPorcelain(lines: List<String>, nowMillis: Long): Map<Int, BlameInfo> {
            val out = HashMap<Int, BlameInfo>()
            var i = 0
            while (i < lines.size) {
                val match = FILE_HEADER.matchEntire(lines[i])
                if (match == null) {
                    i++
                    continue
                }
                val sha = match.groupValues[1]
                val finalLine = match.groupValues[3].toInt()
                val groupSize = match.groupValues[4].ifEmpty { "1" }.toInt().coerceAtLeast(1)
                var author: String? = null
                var subject: String? = null
                var authorTime: Long? = null
                i++
                while (i < lines.size && !lines[i].startsWith('\t')) {
                    val line = lines[i]
                    when {
                        line.startsWith("author ") -> author = line.removePrefix("author ")
                        line.startsWith("author-time ") -> authorTime = line.removePrefix("author-time ").toLongOrNull()
                        line.startsWith("summary ") -> subject = line.removePrefix("summary ")
                    }
                    i++
                }
                // 跳过内容行（\t 前缀），下一行是新的块头
                while (i < lines.size && lines[i].startsWith('\t')) {
                    i++
                }
                val uncommitted = sha.startsWith("0000000") || subject == NOT_COMMITTED
                if (!uncommitted && author == null) {
                    continue
                }
                val info = if (uncommitted) {
                    BlameInfo(
                        sha = sha,
                        author = author ?: "you",
                        subject = subject ?: "未提交",
                        whenText = "未提交",
                        uncommitted = true
                    )
                } else {
                    val whenText = authorTime?.let { relativeTime(it * 1000, nowMillis) } ?: ""
                    BlameInfo(
                        sha = sha,
                        author = author ?: return out,
                        subject = subject ?: "",
                        whenText = whenText,
                        uncommitted = false,
                        messageLines = listOfNotNull(subject?.takeUnless { it.isEmpty() })
                    )
                }
                for (lineNo in finalLine until finalLine + groupSize) {
                    out[lineNo] = info
                }
            }
            return out
        }

        /**
         * 纯函数：解析 `git show -s --format=%H%n%B <sha...>` 的批量输出。
         * 40 位 sha 行是每个提交的起点，其后到下一个 sha 行之间都是该提交的 %B。
         */
        fun parseShowBatch(lines: List<String>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            var current: String? = null
            val message = StringBuilder()
            for (line in lines) {
                val isSha = line.length == 40 && line.all { it in '0'..'9' || it in 'a'..'f' }
                if (isSha) {
                    current?.let { sha -> out[sha] = message.toString().trim('\n') }
                    current = line
                    message.setLength(0)
                } else if (current != null) {
                    message.append(line).append('\n')
                }
            }
            current?.let { sha -> out[sha] = message.toString().trim('\n') }
            return out
        }

        /**
         * 解析 `git blame --porcelain -L n,n` 的输出（纯函数，方便测试）。
         */
        fun parsePorcelain(lines: List<String>, nowMillis: Long): BlameInfo? {
            if (lines.isEmpty()) return null
            val sha = lines[0].substringBefore(' ')
            var author: String? = null
            var subject: String? = null
            var authorTime: Long? = null
            for (i in 1 until lines.size) {
                val line = lines[i]
                when {
                    line.startsWith("author ") -> author = line.removePrefix("author ")
                    line.startsWith("author-time ") -> authorTime = line.removePrefix("author-time ").toLongOrNull()
                    line.startsWith("summary ") -> subject = line.removePrefix("summary ")
                    line.isBlank() -> break
                }
            }
            val uncommitted = sha.startsWith("0000000") || subject == NOT_COMMITTED
            if (uncommitted) {
                return BlameInfo(
                    sha = sha,
                    author = author ?: "you",
                    subject = subject ?: "未提交",
                    whenText = "未提交",
                    uncommitted = true
                )
            }
            val whenText = authorTime?.let { relativeTime(it * 1000, nowMillis) } ?: ""
            return BlameInfo(
                sha = sha,
                author = author ?: return null,
                subject = subject ?: "",
                whenText = whenText,
                uncommitted = false,
                messageLines = listOfNotNull(subject?.takeUnless { it.isEmpty() })
            )
        }

        /**
         * 纯函数：按 git 的 subject 规则从完整消息（%B）中重组标题 ——
         * 跳过开头空行，取到第一个空行前的所有行，各自去掉首尾空白后用空格连接。
         */
        fun subjectOf(messageLines: List<String>): String {
            val start = messageLines.indexOfFirst { it.isNotBlank() }
            if (start < 0) return ""
            val rest = messageLines.subList(start, messageLines.size)
            val end = rest.indexOfFirst { it.isBlank() }
            val block = if (end < 0) rest else rest.subList(0, end)
            return block.joinToString(" ") { it.trim() }
        }

        /** 人性化相对时间（GitToolBox 风格） */
        fun relativeTime(thenMillis: Long, nowMillis: Long): String {
            val seconds = (nowMillis - thenMillis) / 1000
            return when {
                seconds < 60 -> "刚刚"
                seconds < 3600 -> "${seconds / 60} 分钟前"
                seconds < 86400 -> "${seconds / 3600} 小时前"
                seconds < 86400 * 7 -> "${seconds / 86400} 天前"
                seconds < 86400 * 30 -> "${seconds / (86400 * 7)} 周前"
                seconds < 86400 * 365 -> "${seconds / (86400 * 30)} 个月前"
                else -> "${seconds / (86400 * 365)} 年前"
            }
        }

        const val NOT_COMMITTED = "Not Committed Yet"
    }
}
