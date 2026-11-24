package io.github.xbeeant.batiskit.console

import com.intellij.execution.filters.ConsoleFilterProvider
import com.intellij.execution.filters.Filter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import io.github.xbeeant.batiskit.settings.ModuleToggles
import java.awt.Font

/**
 * 控制台着色：把 [LogHighlighter] 算出来的切段交给平台的 `Filter.Result`。
 *
 * 走的是平台标准的 `consoleFilterProvider`，不接管 ConsoleView、不改日志框架配置，
 * 因此 Run / Debug / 单元测试 / 终端控制台都自动生效；高亮只加前景色（和可选背景色），
 * 不抢平台自己的超链接与错误输出样式。
 */
class ConsoleLogFilter : Filter {

    /** 堆栈行要继承上一条 ERROR，所以解析器按 Filter 实例留一份状态 */
    private val parser = LogLineParser()

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val settings = GrepConsoleSettings.getInstance().config
        if (!settings.enabled || line.isEmpty()) return null

        val parsed = synchronized(parser) { parser.parse(line) }
        val segments = LogHighlighter.segments(
            parsed,
            settings.rules,
            settings.colors(),
            settings.colorizeLevel,
            settings.highlightKeywords,
        )
        if (segments.isEmpty()) return null

        val items = segments.mapNotNull { segment ->
            if (segment.start >= segment.end) return@mapNotNull null
            val foreground = GrepConsoleSettings.parseColor(segment.foreground)
            val background = GrepConsoleSettings.parseColor(segment.background)
            if (foreground == null && background == null) return@mapNotNull null
            val attributes = TextAttributes().apply {
                foregroundColor = foreground
                backgroundColor = background
                fontType = when {
                    segment.bold && segment.italic -> Font.BOLD or Font.ITALIC
                    segment.bold -> Font.BOLD
                    segment.italic -> Font.ITALIC
                    else -> Font.PLAIN
                }
            }
            Filter.ResultItem(segment.start, segment.end, null, attributes)
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }
}

/** 每个控制台拿一个独立的 Filter，避免多个进程的堆栈状态串味 */
class GrepConsoleFilterProvider : ConsoleFilterProvider {
    override fun getDefaultFilters(project: Project): Array<Filter> =
        if (ModuleToggles.isEnabled(ModuleToggles.GREP)) arrayOf(ConsoleLogFilter()) else emptyArray()
}
