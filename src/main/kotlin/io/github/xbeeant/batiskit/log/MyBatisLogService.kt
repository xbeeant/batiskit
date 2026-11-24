package io.github.xbeeant.batiskit.log

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
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import java.util.*

/**
 * 控制台捕获：挂在 Run / Debug 的 [ProcessHandler] 上收文本，交给 [MyBatisLogParser] 解析。
 *
 * 与 MyBatis Log Plugin 的做法一致——不接管日志框架，只是被动读控制台输出，
 * 所以对 spring-boot、普通 main、测试运行都生效，不需要改项目的 logging 配置。
 */
@Service(Service.Level.PROJECT)
class MyBatisLogService(private val project: Project) : Disposable {

    interface Listener {
        /** 新语句到达（EDT 回调） */
        fun entriesAdded(entries: List<MyBatisLogEntry>)

        /** 已有语句的 Total / Updates 回填，列表重绘即可 */
        fun entriesUpdated()

        /** 列表被清空 */
        fun cleared()
    }

    /** 解析配置从设置拍快照；设置页 Apply 后 [settingsChanged] 会热更新 */
    private val parser = MyBatisLogParser(settings.toConfig())

    private val entries = ArrayList<MyBatisLogEntry>()

    private val listeners = mutableListOf<Listener>()

    /** 已经挂过监听的进程，避免 processStarted 重复触发时重复解析 */
    private val attached: MutableSet<ProcessHandler> =
        Collections.newSetFromMap(WeakHashMap<ProcessHandler, Boolean>())

    private var sequence = 0

    /** 临时暂停捕获（工具窗口里的「暂停」按钮），不落盘 */
    @Volatile
    var paused: Boolean = false

    val settings: MyBatisLogSettingsState get() = MyBatisLogSettings.getInstance().state

    init {
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            // processStarting 在 startNotify() 之前触发，比 processStarted 挂得更早，
            // 程序启动阶段打印的 SQL 也不会漏；两处都挂，attach 内部去重
            override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                attach(handler, env.runProfile.name)
            }

            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                // 来源标签用运行配置名（如 Application / MyApp），在列表第二行能看到是从哪个进程抓的
                attach(handler, env.runProfile.name)
            }
        })
    }

    // ------------------------------------------------------------------ 捕获

    private fun attach(handler: ProcessHandler, source: String) {
        if (!attached.add(handler)) {
            return
        }
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (!settings.captureEnabled || paused) {
                    return
                }
                consume(event.text, source)
            }

            override fun processTerminated(event: ProcessEvent) {
                handler.removeProcessListener(this)
                attached.remove(handler)
            }
        })
    }

    /** 解析一段控制台文本，返回本次新增 / 回填的结果 */
    fun consume(text: String, source: String = MANUAL_SOURCE): MyBatisLogParseResult {
        val result = parser.feed(text, source)
        if (result.isEmpty) {
            return result
        }
        val added = result.added
        if (added.isNotEmpty()) {
            synchronized(entries) {
                added.forEach { it.seq = ++sequence }
                entries.addAll(added)
                val limit = settings.maxEntries.coerceAtLeast(MIN_ENTRIES)
                while (entries.size > limit) {
                    entries.removeAt(0)
                }
            }
            notifyAdded(added)
        } else if (result.updated) {
            notifyUpdated()
        }
        return result
    }

    /** 清空列表并重置解析状态 */
    fun clear() {
        parser.reset()
        synchronized(entries) {
            entries.clear()
            sequence = 0
        }
        if (listeners.isNotEmpty()) {
            ApplicationManager.getApplication().invokeLater({ listeners.toList().forEach { it.cleared() } }, project.disposed)
        }
    }

    fun snapshot(): List<MyBatisLogEntry> = synchronized(entries) { entries.toList() }

    /** 撤销「清空」：把快照原样放回，续上序号并通知工具窗口重新读快照 */
    fun restore(snapshot: List<MyBatisLogEntry>) {
        parser.reset()
        synchronized(entries) {
            entries.clear()
            entries.addAll(snapshot)
            sequence = snapshot.maxOfOrNull { it.seq } ?: 0
        }
        if (listeners.isNotEmpty()) {
            ApplicationManager.getApplication().invokeLater(
                { listeners.toList().forEach { it.cleared() } }, project.disposed
            )
        }
    }

    // ------------------------------------------------------------------ 还原

    /** 按当前设置还原一条语句：替换参数 → 可选格式化 → 可选补分号 */
    fun restoredSql(entry: MyBatisLogEntry, format: Boolean = settings.formatSql): String {
        val restored = SqlRestorer.restore(entry.sql, entry.parameters, settings.normalizeDate)
        val text = if (format) {
            SqlFormatter.format(restored.text, settings.simpleFormat, settings.newLineWithComment)
        } else {
            restored.text
        }
        return withSemicolon(text)
    }

    /** 文本还原工作台复用：给定独立解析结果，按同一套设置产出 SQL */
    fun restoredSql(sql: String, parameters: List<MyBatisParameter>, format: Boolean = settings.formatSql): String {
        val restored = SqlRestorer.restore(sql, parameters, settings.normalizeDate)
        val text = if (format) {
            SqlFormatter.format(restored.text, settings.simpleFormat, settings.newLineWithComment)
        } else {
            restored.text
        }
        return withSemicolon(text)
    }

    fun withSemicolon(sql: String): String {
        val trimmed = sql.trimEnd()
        return if (settings.appendSemicolon && trimmed.isNotEmpty() && !trimmed.endsWith(";")) {
            "$trimmed;"
        } else {
            trimmed
        }
    }

    // ------------------------------------------------------------------ 监听

    fun addListener(listener: Listener, parentDisposable: Disposable) {
        synchronized(listeners) { listeners += listener }
        Disposer.register(parentDisposable) { synchronized(listeners) { listeners -= listener } }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) { listeners -= listener }
    }

    /** 设置页 apply 之后热更新解析器并让工具窗口按新设置重绘 */
    fun settingsChanged() {
        parser.updateConfig(settings.toConfig())
        notifyUpdated()
    }

    private fun notifyAdded(added: List<MyBatisLogEntry>) {
        if (listeners.isEmpty()) {
            return
        }
        ApplicationManager.getApplication().invokeLater({ listeners.toList().forEach { it.entriesAdded(added) } }, project.disposed)
    }

    private fun notifyUpdated() {
        if (listeners.isEmpty()) {
            return
        }
        ApplicationManager.getApplication().invokeLater({ listeners.toList().forEach { it.entriesUpdated() } }, project.disposed)
    }

    override fun dispose() {
        synchronized(listeners) { listeners.clear() }
    }

    companion object {
        const val MANUAL_SOURCE = "手动导入"

        private const val MIN_ENTRIES = 20

        fun getInstance(project: Project): MyBatisLogService = project.service<MyBatisLogService>()
    }
}
