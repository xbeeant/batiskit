package io.github.xbeeant.batiskit.git

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepositoryManager
import io.github.xbeeant.batiskit.git.config.FetchExclusions
import io.github.xbeeant.batiskit.git.config.GitPatterns
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 自动 fetch：按配置间隔对工程里的仓库逐个 remote 执行 `git fetch --quiet`，
 * 完成后刷新 ahead / behind 快照（behind 变化由 [GitProjectData] 负责通知）。
 *
 * 支持排除项：命中仓库路径的整仓跳过，只命中 remote 名的跳过该 remote。
 */
@Service(Service.Level.PROJECT)
class AutoFetcher(private val project: Project) : Disposable {

    private var future: ScheduledFuture<*>? = null

    fun start() {
        if (future != null) {
            return
        }
        val settings = GitSettings.getInstance().state
        if (!settings.autoFetchEnabled) {
            return
        }
        val intervalMinutes = settings.autoFetchIntervalMinutes.coerceAtLeast(1)
        future = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
            { runCatching { fetchAll() } },
            intervalMinutes.toLong(),
            intervalMinutes.toLong(),
            TimeUnit.MINUTES
        )
        if (settings.autoFetchOnStartup) {
            ApplicationManager.getApplication().executeOnPooledThread { runCatching { fetchAll() } }
        }
    }

    fun stop() {
        future?.cancel(false)
        future = null
    }

    /** 立即 fetch 所有（未被排除的）仓库并刷新状态（后台线程调用） */
    fun fetchAll() {
        val settings = GitSettings.getInstance().state
        val exclusions = GitPatterns.parseCsv(settings.autoFetchExclusions)
        GitRepositoryManager.getInstance(project).repositories.forEach { repo ->
            if (FetchExclusions.repoExcluded(repo.root.path, exclusions)) {
                return@forEach
            }
            val remotes = FetchExclusions.remotesToFetch(repo.remotes.map { it.name }, exclusions)
            remotes.forEach { remote ->
                val handler = GitLineHandler(project, repo.root, GitCommand.FETCH)
                handler.addParameters(remote, "--quiet")
                runCatching { Git.getInstance().runCommand(handler) }
            }
        }
        GitProjectData.getInstance(project).refreshAsync()
    }

    override fun dispose() {
        stop()
    }

    companion object {
        fun getInstance(project: Project): AutoFetcher = project.service<AutoFetcher>()
    }
}
