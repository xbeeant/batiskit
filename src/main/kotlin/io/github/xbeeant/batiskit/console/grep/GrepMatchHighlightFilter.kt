package io.github.xbeeant.batiskit.console.grep

import com.intellij.execution.filters.Filter
import com.intellij.openapi.editor.markup.TextAttributes
import io.github.xbeeant.batiskit.console.FilterQuery
import io.github.xbeeant.batiskit.console.GrepConsoleSettings
import io.github.xbeeant.batiskit.console.GrepHighlightPalette
import java.awt.Font

/**
 * Grep 子控制台专属的片段高亮：只挂到子控制台上，表达式来自 [GrepSubConsoleListener.query]。
 *
 * 全局 [io.github.xbeeant.batiskit.console.ConsoleLogFilter] 负责级别色 / 关键词色（前景），
 * 这里只给「当前 Grep 表达式命中的片段」加背景色，两者区间由平台合并、不会互相顶掉。
 *
 * 底色从设置里实时读取，改完颜色点 Apply 已打开的子控制台立刻生效，不用重开 tab。
 * [colorSpecProvider] 可注入，便于离线测试；返回 null 表示不高亮（总开关关闭 / 配置不可用）。
 */
class GrepMatchHighlightFilter(
    private val queryProvider: () -> FilterQuery?,
    colorSpecProvider: () -> String? = {
        val config = GrepConsoleSettings.getInstance().config
        config.matchHighlightColor.takeIf { config.enabled }
    },
) : Filter {

    private val palette = GrepHighlightPalette(colorSpecProvider)

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val query = queryProvider() ?: return null
        if (line.isEmpty()) return null
        val spans = query.ranges(line)
        if (spans.isEmpty()) return null
        val background = palette.color() ?: return null
        val attributes = TextAttributes().apply {
            backgroundColor = background
            fontType = Font.BOLD
        }
        val items = spans.mapNotNull { span ->
            if (span.start < 0 || span.end > line.length || span.start >= span.end) {
                null
            } else {
                Filter.ResultItem(span.start, span.end, null, attributes)
            }
        }
        return if (items.isEmpty()) null else Filter.Result(items)
    }
}
