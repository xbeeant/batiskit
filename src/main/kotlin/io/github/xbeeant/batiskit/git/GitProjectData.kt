package io.github.xbeeant.batiskit.git

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.util.concurrency.AppExecutorUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/** 单个仓库的状态快照 */
data class RepoStatus(
    val rootPath: String,
    val branch: String,
    val ahead: Int,
    val behind: Int,
    /** 该仓库下未提交的文件数 */
    val uncommitted: Int = 0,
    /** HEAD 上的 tags（可能在多个，按提交时间倒序） */
    val tags: List<String> = emptyList()
)

/**
 * 工程级 Git 状态数据源：ahead / behind / 未提交数量 / HEAD tags。
 *
 * - 后台线程跑 git 命令，EDT 只读缓存；
 * - 仓库变化（提交、切分支、fetch 完成）时自动刷新；
 * - behind 数增加且 >0 时弹通知（GitToolBox 的 behind tracker）；
 * - 顺带记录最近使用的本地分支（Recent branches switcher）。
 */
@Service(Service.Level.PROJECT)
class GitProjectData(private val project: Project) : Disposable {

    @Volatile
    var statuses: List<RepoStatus> = emptyList()
        private set

    /** 未提交改动数量（项目级，来自 ChangeListManager） */
    @Volatile
    var uncommittedCount: Int = 0
        private set

    @Volatile
    private var lastBehind: Int = 0

    private val refreshing = AtomicBoolean(false)
    private var refreshFuture: Future<*>? = null

    private val updateListeners = mutableListOf<Runnable>()

    private val repoListener = GitRepositoryChangeListener { refreshAsync() }

    val totalAhead: Int get() = statuses.sumOf { it.ahead }
    val totalBehind: Int get() = statuses.sumOf { it.behind }

    init {
        // 仓库状态变化时刷新（提交 / 切分支 / rebase 等）
        project.messageBus.connect(this).subscribe(GitRepository.GIT_REPO_CHANGE, repoListener)
    }

    fun addUpdateListener(listener: Runnable) {
        updateListeners += listener
    }

    /** 文件所属仓库的状态；嵌套仓库按最长根目录命中 */
    fun statusFor(path: String): RepoStatus? = statuses
        .filter { path == it.rootPath || GitPaths.relativePath(it.rootPath, path) != null }
        .maxByOrNull { it.rootPath.length }

    /** 后台刷新状态快照 */
    fun refreshAsync() {
        if (!refreshing.compareAndSet(false, true)) {
            return
        }
        refreshFuture = executor().submit {
            try {
                val changedPaths = readChangedPaths()
                val snapshot = mutableListOf<RepoStatus>()
                GitRepositoryManager.getInstance(project).repositories.forEach { repo ->
                    snapshot += readRepo(repo, changedPaths)
                }
                statuses = snapshot
                uncommittedCount = changedPaths.size
                checkBehind()
                ApplicationManager.getApplication().invokeLater {
                    // 最近分支记录是 @State 数据，只在 EDT 修改，避免与持久化线程并发
                    snapshot.forEach { RecentBranches.getInstance().record(it.rootPath, it.branch) }
                    updateListeners.forEach { runCatching { it.run() } }
                }
            } catch (e: Exception) {
                // 以前整段吞掉：git 失败 / NPE 后状态栏会长期停在旧快照且零线索
                com.intellij.openapi.diagnostic.Logger.getInstance(GitProjectData::class.java)
                    .warn("刷新 Git 状态失败，保留上一次快照", e)
            } finally {
                refreshing.set(false)
            }
        }
    }

    private fun readRepo(repo: GitRepository, changedPaths: List<String>): RepoStatus {
        val uncommitted = changedPaths.count { GitPaths.relativePath(repo.root.path, it) != null }
        val tags = readTags(repo)
        val branch = repo.currentBranch?.name
        if (branch == null) {
            return RepoStatus(repo.root.path, "HEAD", 0, 0, uncommitted, tags)
        }
        val upstream = readUpstream(repo, branch)
        if (upstream.isNullOrBlank()) {
            return RepoStatus(repo.root.path, branch, 0, 0, uncommitted, tags)
        }

        val handler = GitLineHandler(project, repo.root, GitCommand.REV_LIST)
        handler.addParameters("--left-right", "--count")
        handler.addParameters("$upstream...HEAD")
        val result = runCatching { Git.getInstance().runCommand(handler) }.getOrNull()
        if (result == null || !result.success()) {
            return RepoStatus(repo.root.path, branch, 0, 0, uncommitted, tags)
        }
        val parts = result.outputAsJoinedString.trim().split(Regex("\\s+"))
        // 第一个数字：upstream 独有 = behind；第二个：HEAD 独有 = ahead
        val behind = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val ahead = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return RepoStatus(repo.root.path, branch, ahead, behind, uncommitted, tags)
    }

    /** HEAD 上的 tags；读不到（老版本 git / 非仓库）时返回空列表 */
    private fun readTags(repo: GitRepository): List<String> {
        val handler = GitLineHandler(project, repo.root, GitCommand.TAG)
        handler.addParameters("--points-at", "HEAD")
        handler.addParameters("--sort=-creatordate")
        val result = runCatching { Git.getInstance().runCommand(handler) }.getOrNull() ?: return emptyList()
        if (!result.success()) {
            return emptyList()
        }
        return result.output.map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 查询当前分支的 upstream（不依赖 git4idea 的分支模型 API）；没有 upstream 时返回 null */
    private fun readUpstream(repo: GitRepository, branch: String): String? =
        revParseUpstream(repo, branch)?.trim()?.ifBlank { null }

    /**
     * `git rev-parse --symbolic-full-name --abbrev-ref @{upstream}`，输出 `origin/main` 形式。
     *
     * 之所以不用 `GitCommand.FOR_EACH_REF`（`--format=%(upstream:short)`）：
     * FOR_EACH_REF 常量是较新版本 git4idea 才加入的，直接引用在 2024.1 等低版本平台上无法编译；
     * 而 `@{upstream}` 从 git 1.8 起就支持，REV_PARSE 常量在各版本 git4idea 里都有，
     * 一个实现同时兼容低 / 高版本平台，输出语义与 for-each-ref 的 `%(upstream:short)` 一致。
     */
    private fun revParseUpstream(repo: GitRepository, branch: String): String? {
        val handler = GitLineHandler(project, repo.root, GitCommand.REV_PARSE)
        handler.addParameters("--symbolic-full-name", "--abbrev-ref", "@{upstream}")
        val result = runCatching { Git.getInstance().runCommand(handler) }.getOrNull() ?: return null
        if (!result.success()) {
            return null
        }
        return result.outputAsJoinedString
    }

    /** 未提交改动涉及的文件路径（后台线程调用） */
    private fun readChangedPaths(): List<String> = runCatching {
        ChangeListManager.getInstance(project).defaultChangeList.changes
            .mapNotNull { it.virtualFile?.path }
            .distinct()
    }.getOrDefault(emptyList())

    private fun checkBehind() {
        val settings = GitSettings.getInstance().state
        val behind = totalBehind
        if (settings.behindTrackerEnabled && behind > lastBehind) {
            val branch = statuses.maxByOrNull { it.behind }?.branch ?: return
            ApplicationManager.getApplication().invokeLater {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(
                        "分支落后远端",
                        "$branch 落后远端 $behind 个提交，建议 fetch 后 pull 或 rebase",
                        NotificationType.INFORMATION
                    )
                    .notify(project)
            }
        }
        lastBehind = behind
    }

    private fun executor(): ExecutorService = AppExecutorUtil.getAppExecutorService()

    override fun dispose() {
        refreshFuture?.cancel(false)
    }

    companion object {
        const val NOTIFICATION_GROUP = "Git Toolbox"

        fun getInstance(project: Project): GitProjectData = project.service<GitProjectData>()
    }
}
