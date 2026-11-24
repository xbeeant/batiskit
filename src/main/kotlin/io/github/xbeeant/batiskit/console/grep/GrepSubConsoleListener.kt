package io.github.xbeeant.batiskit.console.grep

import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Pair
import io.github.xbeeant.batiskit.console.FilterQuery
import io.github.xbeeant.batiskit.console.LogLevel
import io.github.xbeeant.batiskit.console.LogLineParser
import java.util.concurrent.atomic.AtomicInteger

/**
 * 对标原版 `GrepFilterSyncListener`：父控制台的输出流经 [MyGrepFilter] 时到达这里，
 * 攒成完整行后按当前表达式匹配，命中的行**带着原始 contentType**（颜色 / 错误流样式不丢）打印进子控制台。
 *
 * 另外统计命中行数，供子控制台顶部显示；回调在输出线程触发，UI 侧负责切到 EDT 并合并刷新。
 */
class GrepSubConsoleListener(
    /** 生产环境传 `console::print`；测试传收集 lambda */
    private val print: (String, ConsoleViewContentType) -> Unit,
) : GrepFilterListener, Disposable {

    /** 当前过滤表达式；为 null 时（还没 Apply 过）不转发任何行 */
    @Volatile
    var query: FilterQuery? = null

    /** 半行缓冲：InputFilter 给的文本块可能被截断，只有以 \n 结尾的 token 才构成完整行 */
    private val pending = ArrayList<Pair<String, ConsoleViewContentType>>()

    private val matchCount = AtomicInteger(0)

    /** 命中行数变化回调；参数是从上次 [resetCount] 起的累计行数 */
    var onMatchCountChanged: ((Int) -> Unit)? = null

    fun count(): Int = matchCount.get()

    /** Apply 新表达式 / Reload 回放时清零 */
    fun resetCount() {
        matchCount.set(0)
        onMatchCountChanged?.invoke(0)
    }

    override fun process(text: String, contentType: ConsoleViewContentType) {
        val q = query ?: return
        if (text.isEmpty()) {
            return
        }
        var pos = 0
        while (pos < text.length) {
            val nl = text.indexOf('\n', pos)
            val end = if (nl >= 0) nl + 1 else text.length
            val token = text.substring(pos, end)
            pos = end
            pending.add(Pair.create(token, contentType))
            if (!token.endsWith("\n")) {
                continue
            }
            val line = join(pending)
            val pairs = pending.toList()
            pending.clear()
            val matchable = if (line.length > MAX_MATCH_LENGTH) line.substring(0, MAX_MATCH_LENGTH) else line
            if (q.matches(matchable)) {
                val count = matchCount.incrementAndGet()
                onMatchCountChanged?.invoke(count)
                for (pair in pairs) {
                    print(pair.first, pair.second)
                }
            }
        }
    }

    override fun clear() {
        pending.clear()
    }

    override fun dispose() {
        pending.clear()
    }

    private fun join(pairs: List<Pair<String, ConsoleViewContentType>>): String {
        val sb = StringBuilder()
        for (pair in pairs) {
            sb.append(pair.first)
        }
        return sb.toString()
    }

    companion object {
        /** 超长行只拿前 8K 匹配（对标原版 limitInputGrepLength，防止正则卡死控制台） */
        private const val MAX_MATCH_LENGTH = 8 * 1024

        /**
         * 回放父控制台已有文本时的 contentType 推断：按行解析级别，
         * 映射到平台对应的日志输出类型（错误行仍是红色、警告行黄色）。
         */
        fun contentTypeOf(parser: LogLineParser, line: String): ConsoleViewContentType {
            return when (parser.parse(line).level) {
                LogLevel.ERROR -> ConsoleViewContentType.ERROR_OUTPUT
                LogLevel.WARN -> ConsoleViewContentType.LOG_WARNING_OUTPUT
                LogLevel.INFO -> ConsoleViewContentType.LOG_INFO_OUTPUT
                LogLevel.DEBUG -> ConsoleViewContentType.LOG_DEBUG_OUTPUT
                LogLevel.TRACE -> ConsoleViewContentType.LOG_VERBOSE_OUTPUT
                LogLevel.OTHER -> ConsoleViewContentType.NORMAL_OUTPUT
            }
        }
    }
}
