package io.github.xbeeant.batiskit.ui.components

import com.intellij.ui.components.JBLabel
import io.github.xbeeant.batiskit.console.ColorSpecs
import io.github.xbeeant.batiskit.console.HighlightRule
import io.github.xbeeant.batiskit.console.LogHighlighter
import io.github.xbeeant.batiskit.console.LogLevel
import io.github.xbeeant.batiskit.console.LogLineParser
import io.github.xbeeant.batiskit.ui.UiTokens
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import java.awt.Insets
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * Grep Console 设置页的整页颜色效果预览。
 *
 * 关键点：预览不自己发明一套配色规则，而是把当前表单里的级别色 / 规则 / 命中底色
 * 喂给 [LogHighlighter.segments] —— 与真正控制台过滤器同一段逻辑，所见即所得。
 * 上下两栏分别固定渲染浅色 / 深色主题，不跟随当前 IDE 主题，这样调深色配色时
 * 不必切主题去猜效果。
 */
class ConsoleColorPreviewPanel : JPanel(BorderLayout()) {

    /** 表单当前值；任何一项改动后调用 [update] 即可 */
    data class PreviewState(
        val levelColors: Map<LogLevel, String> = emptyMap(),
        val rules: List<HighlightRule> = emptyList(),
        val matchColor: String = "",
        val colorizeLevel: Boolean = true,
        val highlightKeywords: Boolean = true,
    )

    val lightPane = createPane(LIGHT_CONSOLE, DARK_TEXT)
    val darkPane = createPane(DARK_CONSOLE, LIGHT_TEXT)

    private var state = PreviewState()

    init {
        isOpaque = false
        val rows = JPanel(GridLayout(2, 1, 0, UiTokens.scale(4))).apply { isOpaque = false }
        rows.add(previewRow("浅色", lightPane))
        rows.add(previewRow("深色", darkPane))
        add(rows, BorderLayout.CENTER)
        update(state)
    }

    fun update(state: PreviewState) {
        this.state = state
        render(lightPane, darkTheme = false)
        render(darkPane, darkTheme = true)
        revalidate()
        repaint()
    }

    /** 供外部（主题 / LAF 刷新）重绘，不改变配置值 */
    fun refresh() = update(state)

    private fun previewRow(title: String, pane: JTextPane): JPanel =
        JPanel(BorderLayout(UiTokens.scale(6), 0)).apply {
            isOpaque = false
            add(JBLabel(title).apply {
                foreground = UiTokens.textSecondary
                toolTipText = "${title}主题下的实际着色效果"
                preferredSize = Dimension(UiTokens.scale(34), UiTokens.scale(20))
            }, BorderLayout.WEST)
            add(pane, BorderLayout.CENTER)
        }

    private fun render(pane: JTextPane, darkTheme: Boolean) {
        val document = pane.styledDocument
        runCatching { document.remove(0, document.length) }
        val parser = LogLineParser()
        SAMPLE_LINES.forEachIndexed { index, line ->
            val parsed = parser.parse(line)
            val segments = LogHighlighter.segments(
                parsed,
                state.rules,
                state.levelColors,
                state.colorizeLevel,
                state.highlightKeywords,
            )
            runs(line, segments, darkTheme).forEach { (text, style) ->
                runCatching { document.insertString(document.length, text, attributes(style)) }
            }
            if (index != SAMPLE_LINES.lastIndex) {
                // 换行符不继承上一段的背景色，否则行尾会拖出一条色带
                val plain = RunStyle(if (darkTheme) LIGHT_TEXT else DARK_TEXT, null, false, false)
                runCatching { document.insertString(document.length, "\n", attributes(plain)) }
            }
        }
        pane.caretPosition = 0
    }

    private fun attributes(style: RunStyle): SimpleAttributeSet = SimpleAttributeSet().apply {
        StyleConstants.setFontFamily(this, Font.MONOSPACED)
        StyleConstants.setFontSize(this, UiTokens.scale(11))
        StyleConstants.setForeground(this, style.foreground)
        StyleConstants.setBold(this, style.bold)
        StyleConstants.setItalic(this, style.italic)
        if (style.background != null) StyleConstants.setBackground(this, style.background)
    }

    /** 逐字符合成样式后合并相邻同款，避免往 StyledDocument 里塞几百个碎片 */
    private fun runs(
        line: String,
        segments: List<io.github.xbeeant.batiskit.console.HighlightSegment>,
        darkTheme: Boolean,
    ): List<Pair<String, RunStyle>> {
        val baseForeground = if (darkTheme) LIGHT_TEXT else DARK_TEXT
        val matchStart = line.indexOf(MATCH_TOKEN)
        val matchEnd = if (matchStart < 0) -1 else matchStart + MATCH_TOKEN.length
        val matchColor = ColorSpecs.color(state.matchColor, darkTheme)
        val styles = Array(line.length) { offset ->
            val segment = segments.firstOrNull { it.start <= offset && offset < it.end }
            val foreground = segment?.foreground?.let { ColorSpecs.color(it, darkTheme) } ?: baseForeground
            val background = when {
                matchStart >= 0 && offset in matchStart until matchEnd -> matchColor
                else -> segment?.background?.let { ColorSpecs.color(it, darkTheme) }
            }
            RunStyle(foreground, background, segment?.bold ?: false, segment?.italic ?: false)
        }
        if (line.isEmpty()) return emptyList()
        val result = ArrayList<Pair<String, RunStyle>>()
        var from = 0
        for (index in 1..line.length) {
            if (index == line.length || styles[index] != styles[from]) {
                result += line.substring(from, index) to styles[from]
                from = index
            }
        }
        return result
    }

    private fun createPane(background: Color, foreground: Color): JTextPane = JTextPane().apply {
        isEditable = false
        isOpaque = true
        this.background = background
        this.foreground = foreground
        font = Font(Font.MONOSPACED, Font.PLAIN, UiTokens.scale(11))
        margin = Insets(UiTokens.scale(4), UiTokens.scale(6), UiTokens.scale(4), UiTokens.scale(6))
        border = UiTokens.line(UiTokens.borderSubtle, 1, 1, 1, 1)
        preferredSize = Dimension(UiTokens.scale(520), UiTokens.scale(96))
        toolTipText = "颜色预览：使用当前设置页的颜色与规则渲染，左上是浅色主题、下方是深色主题"
    }

    /** data class 让相邻同款可以直接判等合并 */
    private data class RunStyle(
        val foreground: Color,
        val background: Color?,
        val bold: Boolean,
        val italic: Boolean,
    )

    companion object {
        private const val MATCH_TOKEN = "pay/order"

        private val SAMPLE_LINES = listOf(
            "12:00:00.001 [main] ERROR c.e.Demo - Exception on pay/order",
            "12:00:00.002 [main] WARN  c.e.Demo - timeout, retrying 1/3",
            "12:00:00.003 [main] INFO  c.e.Demo - service started",
            "12:00:00.004 [main] DEBUG c.e.Demo - cache hit for user:42",
            "12:00:00.005 [main] TRACE c.e.Demo - enter doWork()",
        )

        private val LIGHT_CONSOLE = Color(0xFBFCFE)
        private val DARK_CONSOLE = Color(0x1C2027)
        private val DARK_TEXT = Color(0x1B2532)
        private val LIGHT_TEXT = Color(0xE2E8F1)
    }
}
