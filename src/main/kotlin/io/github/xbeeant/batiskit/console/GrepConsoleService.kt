package io.github.xbeeant.batiskit.console

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import io.github.xbeeant.batiskit.settings.ModuleToggles
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import java.util.Collections
import java.util.WeakHashMap

/** 工具窗口里的一行日志（[level] 已按堆栈上下文补全） */
data class LogEntry(
    val seq: Long,
    val level: LogLevel,
    val text: String,
    val source: String,
    val time: Long,
    val continuation: Boolean = false,
)

/**
 * 捕获 Run / Debug 控制台输出，按行解析级别，供「Grep Console」工具窗口快速筛选。
 *
 * 与 [io.github.xbeeant.batiskit.log.MyBatisLogService] 一样被动监听 ProcessHandler，
 * 不改 logging 配置、不需要代理；两份监听互不干扰。解析是有状态的（异常堆栈继承上一条级别），
 * 因此所有输入都在同一把锁上串行处理，多个进程同时输出也不会把行拼错。
 */
@Service(Service.Level.PROJECT)
class GrepConsoleService(private val project: Project) : Disposable {

    interface Listener {
        /** 新行到达（EDT 回调） */
        fun linesAdded(lines: List<LogEntry>)

        /** 列表被清空 / 设置变化，重绘即可 */
        fun refreshed()
    }

    private val lock = Any()
    private val parser = LogLineParser()
    private val pending = StringBuilder()
    private val entries = ArrayList<LogEntry>()
    private val listeners = mutableListOf<Listener>()

    /** 已经挂过监听的进程，避免 processStarting / processStarted 重复触发 */
    private val attached: MutableSet<ProcessHandler> =
        Collections.newSetFromMap(WeakHashMap<ProcessHandler, Boolean>())

    /** 进程 → 运行配置名，Grep Tab 用它把某次运行的日志与其它运行区分开 */
    private val sourceByHandler: MutableMap<ProcessHandler, String> = WeakHashMap()

    private var sequence = 0L

    /** 工具窗口里临时暂停，不落盘 */
    @Volatile
    var paused: Boolean = false

    val settings: GrepConsoleSettingsState get() = GrepConsoleSettings.getInstance().config

    init {
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                attach(handler, env.runProfile.name)
            }

            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                attach(handler, env.runProfile.name)
            }
        })
    }

    // ------------------------------------------------------------------ 捕获

    private fun attach(handler: ProcessHandler, source: String) {
        if (!synchronized(lock) { attached.add(handler) }) return
        synchronized(lock) { sourceByHandler[handler] = source }
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (!settings.enabled || !settings.captureEnabled || paused) return
                consume(event.text, source)
            }

            override fun processTerminated(event: ProcessEvent) {
                // 进程结束时把没带换行的最后一行补进列表
                consume("", source, flush = true)
                handler.removeProcessListener(this)
                synchronized(lock) { attached.remove(handler) }
            }
        })
    }

    /**
     * 喂入一段控制台文本，返回本次新增的行。文本块可能被截断，所以只按 `\n` 取完整行，
     * 尾巴留在 [pending] 里等下一块；[flush] 用于进程结束 / 手工导入时强制收尾。
     */
    fun consume(text: String, source: String = MANUAL_SOURCE, flush: Boolean = false): List<LogEntry> {
        val added = ArrayList<LogEntry>()
        synchronized(lock) {
            if (text.isNotEmpty()) pending.append(text)
            while (true) {
                val nl = pending.indexOf('\n')
                if (nl < 0) break
                val line = pending.substring(0, nl).trimEnd('\r')
                pending.delete(0, nl + 1)
                addLine(line, source, added)
            }
            // 有些程序打印超长单行且迟迟不给换行，别让它一直憋在缓冲区里
            if (flush || pending.length > MAX_PENDING) {
                val line = pending.toString().trimEnd('\r')
                pending.setLength(0)
                addLine(line, source, added)
            }
            if (added.isNotEmpty()) {
                entries.addAll(added)
                val limit = settings.maxEntries
                if (entries.size > limit) entries.subList(0, entries.size - limit).clear()
            }
        }
        if (added.isNotEmpty()) notifyAdded(added)
        return added
    }

    private fun addLine(raw: String, source: String, added: MutableList<LogEntry>) {
        if (raw.isBlank()) return
        val line = parser.parse(raw)
        added += LogEntry(++sequence, line.level, line.text, source, System.currentTimeMillis(), line.continuation)
    }

    fun snapshot(): List<LogEntry> = synchronized(lock) { entries.toList() }

    /** 查某次运行（进程）对应的日志来源名；进程已结束且被回收后返回 null */
    fun sourceOf(handler: ProcessHandler): String? = synchronized(lock) { sourceByHandler[handler] }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            pending.setLength(0)
            parser.reset()
            sequence = 0
        }
        notifyRefreshed()
    }

    /** 撤销「清空」：把清空前的快照原样放回，序号从最大 seq 续上 */
    fun restore(snapshot: List<LogEntry>) {
        synchronized(lock) {
            entries.clear()
            entries.addAll(snapshot)
            pending.setLength(0)
            parser.reset()
            sequence = snapshot.maxOfOrNull { it.seq } ?: 0L
        }
        notifyRefreshed()
    }

    /** 设置页 apply 后刷新列表（关键词 / 颜色可能变了） */
    fun settingsChanged() = notifyRefreshed()

    // ------------------------------------------------------------------ 监听

    fun addListener(listener: Listener, parentDisposable: Disposable) {
        synchronized(listeners) { listeners += listener }
        Disposer.register(parentDisposable) { synchronized(listeners) { listeners -= listener } }
    }

    private fun notifyAdded(added: List<LogEntry>) {
        if (listeners.isEmpty()) return
        ApplicationManager.getApplication().invokeLater(
            { listeners.toList().forEach { it.linesAdded(added) } }, project.disposed
        )
    }

    private fun notifyRefreshed() {
        if (listeners.isEmpty()) return
        ApplicationManager.getApplication().invokeLater(
            { listeners.toList().forEach { it.refreshed() } }, project.disposed
        )
    }

    override fun dispose() {
        synchronized(listeners) { listeners.clear() }
    }

    companion object {
        const val MANUAL_SOURCE = "手动导入"
        private const val MAX_PENDING = 16 * 1024

        fun getInstance(project: Project): GrepConsoleService = project.service<GrepConsoleService>()
    }
}

/** 工程打开就把服务建起来，第一次点「运行」之前的启动日志也不会漏 */
class GrepConsoleStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!ModuleToggles.isEnabled(ModuleToggles.GREP)) return
        GrepConsoleService.getInstance(project)
    }
}
