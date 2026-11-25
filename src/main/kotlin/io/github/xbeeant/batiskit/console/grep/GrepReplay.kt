package io.github.xbeeant.batiskit.console.grep

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import io.github.xbeeant.batiskit.console.LogLineParser
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 对标原版 `GrepUtils.grepThroughExistingText`：把父控制台**已有**的文本按行喂给监听器，
 * 让新开的 Grep 子控制台立刻能看到之前打印过的日志（Reload 也走这里）。
 *
 * 原版通过 markup 区段还原每行的 contentType；我们用自家级别解析器按行推断，
 * 错误行仍红、警告行仍黄。文本快照在 EDT 上取（与控制台打印互斥）；
 * 逐字符切行 / 匹配移到后台任务，命中行按小批切回 EDT 打印，避免大日志卡住界面。
 */
object GrepReplay {

    /** 同一个子控制台重复 Reload 时，取消还在跑的旧回放，省掉无用的后台扫描 */
    private val active = WeakHashMap<Disposable, MutableSet<ProgressIndicator>>()

    fun grepThroughExistingText(
        project: Project,
        parent: ConsoleView?,
        listener: GrepSubConsoleListener,
        owner: Disposable,
    ) {
        val query = listener.query ?: return
        val impl = parent as? ConsoleViewImpl ?: return
        impl.flushDeferredText()
        val editor = impl.editor ?: return
        val text = editor.document.text

        // 回放期间实时输出先缓存，回放结束再补印，保证旧日志不会盖在新日志后面
        val generation = listener.beginReplay()
        val finishScheduled = AtomicBoolean(false)
        val truncated = AtomicBoolean(false)

        fun scheduleFinish() {
            if (!finishScheduled.compareAndSet(false, true)) return
            ApplicationManager.getApplication().invokeLater(
                {
                    if (project.isDisposed) return@invokeLater
                    listener.finishReplay(generation)
                    if (truncated.get()) {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Grep Console")
                            .createNotification(
                                "Grep Console",
                                "已有日志超过 $MAX_REPLAY_LINES 行或 ${MAX_REPLAY_CHARS / 1024 / 1024} MB，" +
                                    "为避免卡顿只回放了前面部分；调窄表达式后点 Reload 可重放。",
                                NotificationType.WARNING,
                            )
                            .notify(project)
                    }
                },
                project.disposed,
            )
        }

        if (text.isEmpty()) {
            scheduleFinish()
            return
        }

        val parser = LogLineParser()
        val matched = ArrayList<Pair<String, ConsoleViewContentType>>()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "回放 Grep 日志…", true) {
            override fun run(indicator: ProgressIndicator) {
                register(owner, indicator)
                try {
                    val line = StringBuilder()
                    var stop = false
                    var checked = 0

                    fun emit(raw: String, newline: Boolean) {
                        val content = if (newline) raw else "$raw\n"
                        if (!query.matches(
                                if (content.length > MAX_MATCH_LENGTH) content.substring(0, MAX_MATCH_LENGTH) else content
                            )
                        ) {
                            return
                        }
                        matched += content to GrepSubConsoleListener.contentTypeOf(parser, content)
                        if (matched.size >= MAX_REPLAY_LINES) {
                            truncated.set(true)
                            stop = true
                        }
                    }

                    for (ch in text) {
                        if (stop) break
                        if (++checked % CHECK_INTERVAL == 0) {
                            indicator.checkCanceled()
                        }
                        if (checked > MAX_REPLAY_CHARS) {
                            truncated.set(true)
                            break
                        }
                        line.append(ch)
                        if (ch == '\n') {
                            emit(line.toString(), newline = true)
                            line.setLength(0)
                        }
                    }
                    if (!stop && checked <= MAX_REPLAY_CHARS && line.isNotEmpty()) {
                        emit(line.toString(), newline = false)
                    }

                    // 每批很短，EDT 只做 print；批次顺序即日志顺序。
                    // scheduleFinish() 的 invokeLater 排在最后，保证所有批次打印完才补印期间的新行。
                    for (batch in matched.chunked(BATCH_SIZE)) {
                        indicator.checkCanceled()
                        ApplicationManager.getApplication().invokeLater(
                            {
                                if (project.isDisposed) return@invokeLater
                                for ((content, type) in batch) {
                                    listener.processReplayLine(content, type, query, generation)
                                }
                            },
                            project.disposed,
                        )
                    }
                } finally {
                    unregister(owner, indicator)
                }
                scheduleFinish()
            }

            override fun onCancel() {
                scheduleFinish()
            }

            override fun onThrowable(error: Throwable) {
                scheduleFinish()
            }
        })
    }

    private fun register(owner: Disposable, indicator: ProgressIndicator) {
        synchronized(active) {
            val set = active.getOrPut(owner) { mutableSetOf() }
            // 同一子控制台的新回放先取消旧任务；旧批次即使已排队也会被 generation 丢弃
            for (old in set.toList()) {
                runCatching { old.cancel() }
            }
            set.clear()
            set += indicator
        }
    }

    private fun unregister(owner: Disposable, indicator: ProgressIndicator) {
        synchronized(active) {
            val set = active[owner] ?: return
            set -= indicator
            if (set.isEmpty()) active.remove(owner)
        }
    }

    private const val BATCH_SIZE = 200
    private const val MAX_REPLAY_LINES = 20_000
    private const val MAX_REPLAY_CHARS = 8 * 1024 * 1024
    private const val MAX_MATCH_LENGTH = 8 * 1024
    private const val CHECK_INTERVAL = 4096
}
