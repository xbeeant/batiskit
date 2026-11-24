package io.github.xbeeant.batiskit.git.blame

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import io.github.xbeeant.batiskit.ui.UiTokens
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import io.github.xbeeant.batiskit.git.GitSettings
import io.github.xbeeant.batiskit.git.issue.IssueLinks

/**
 * 行内 blame：光标所在行末尾以 inlay 显示 `作者 · 时间 · 主题`。
 *
 * commit message 有换行时，额外在当前行下方以块状 inlay 逐行渲染完整提交信息，
 * 保留换行结构；此时行内只显示 `作者 · 时间`，避免标题重复。
 *
 * 性能（大仓库 / 多年提交历史也不阻塞编辑）：
 *  - 所有 git 查询都在后台线程，EDT 只做 inlay 增删；
 *  - 光标移动只做 [DEBOUNCE_MILLIS] 防抖，连续移动只发起最后一次查询；
 *  - 命中 GitBlameService 的文件级缓存时零 git 进程、立即显示；
 *  - 当前行已显示时不再销毁/重建 inlay（写操作也会与打字竞争 EDT）。
 */
@Service(Service.Level.PROJECT)
class BlameInlayManager(private val project: Project) : Disposable {

    private var inlay: Inlay<*>? = null
    private var blockInlay: Inlay<*>? = null

    /** 当前已显示 inlay 的编辑器与行号，用于跳过重复的隐藏/重建 */
    private var shownEditor: Editor? = null
    private var shownLine: Int = -1

    /** 防抖：连续移动光标只发起最后一次查询 */
    private val alarm = Alarm(Alarm.ThreadToUse.POOLED_THREAD, this)

    private val caretListener = object : CaretListener {
        override fun caretPositionChanged(event: CaretEvent) {
            handle(event.editor, event.newPosition.line)
        }
    }

    init {
        EditorFactory.getInstance().eventMulticaster.addCaretListener(caretListener, this)
    }

    private fun handle(editor: Editor, line: Int) {
        if (editor.project != project || line < 0) return
        if (!GitSettings.getInstance().state.inlineBlameEnabled) {
            hide()
            return
        }
        if (shownEditor === editor && shownLine == line) {
            // 当前行已在显示，什么都不用做
            return
        }
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: run {
            hide()
            return
        }

        hide()
        // 防抖：暂停 [DEBOUNCE_MILLIS] 才真正查询，快速划过几十行也只起一次 git 进程
        alarm.addRequest({ query(editor, file, line) }, DEBOUNCE_MILLIS)
    }

    private fun query(editor: Editor, file: VirtualFile, line: Int) {
        val oneBased = line + 1
        // 文件级缓存命中零 git 进程；未命中按行查（并在后台预热整个文件的 blame）
        val info = runCatching {
            GitBlameService.getInstance(project).blameCached(file, oneBased)
                ?: GitBlameService.getInstance(project).blame(file, oneBased)
        }.getOrNull()
        ApplicationManager.getApplication().invokeLater {
            if (editor.isDisposed || editor.caretModel.logicalPosition.line != line) return@invokeLater
            show(editor, line, info)
        }
    }

    private fun show(editor: Editor, line: Int, info: BlameInfo?) {
        if (info == null) return
        hide()
        val offset = editor.document.getLineEndOffset(line).coerceAtMost(editor.document.textLength)

        // 块状换行显示（提交信息展开在下方）由设置控制，默认关闭；
        // 关闭时只保留行内一行：放不下由行内省略号截断，不再转块状
        val wrapEnabled = GitSettings.getInstance().state.blameWrapEnabled
        val multiline = info.messageLines.size > 1
        val fullText = inlineTextOf(info, useBlock = false)
        val baseFont = editor.colorsScheme.getFont(EditorFontType.ITALIC)
        val fits = info.uncommitted ||
            BlameFonts.width(editor, baseFont, fullText) + BlameRenderer.HORIZONTAL_PADDING <=
            BlameRenderer.maxWidth(editor)
        val useBlock = wrapEnabled && (multiline || !fits)
        val inlineText = inlineTextOf(info, useBlock)
        // issue 引用（Issue Navigation 配置）在行内 blame 里高亮显示
        val highlights = runCatching {
            IssueLinks.findIn(project, inlineText).map { it.range }
        }.getOrDefault(emptyList())
        ApplicationManager.getApplication().runWriteAction {
            inlay = editor.inlayModel.addInlineElement(offset, true, BlameRenderer(inlineText, highlights))
            if (useBlock && info.messageLines.isNotEmpty()) {
                blockInlay = editor.inlayModel
                    .addBlockElement(offset, true, false, 0, BlameBlockRenderer(info.messageLines))
            }
        }
        shownEditor = editor
        shownLine = line
    }

    private fun hide() {
        alarm.cancelAllRequests()
        shownEditor = null
        shownLine = -1
        val current = inlay
        val block = blockInlay
        inlay = null
        blockInlay = null
        if (current == null && block == null) return
        ApplicationManager.getApplication().runWriteAction {
            if (current != null && current.isValid) Disposer.dispose(current)
            if (block != null && block.isValid) Disposer.dispose(block)
        }
    }

    /**
     * 状态栏 widget 的快捷开关：写 [GitSettingsState.inlineBlameEnabled]，
     * 关闭时立即隐藏当前 inlay（不用等下一次光标移动）。
     */
    fun setEnabled(enabled: Boolean) {
        GitSettings.getInstance().state.inlineBlameEnabled = enabled
        if (!enabled) {
            hide()
        }
    }

    /** 换行显示等显示设置变化后由外部触发：立即移除当前 inlay，下一次光标移动按新设置重建 */
    fun reapply() {
        hide()
    }

    override fun dispose() {
        hide()
    }

    companion object {
        fun getInstance(project: Project): BlameInlayManager = project.service<BlameInlayManager>()

        /** 光标停止移动后的查询延迟（毫秒） */
        private const val DEBOUNCE_MILLIS = 150L

        /**
         * 纯函数：行内 inlay 文案。
         * - `useBlock = false`（换行显示关闭）：始终显示「作者 · 时间 · 标题」——
         *   多行提交信息也把首行摘要放进行内，否则什么都不显示（此前缺陷）；
         * - `useBlock = true`（标题/正文已展开在下方块状 inlay）：行内只留「作者 · 时间」，避免重复。
         */
        fun inlineTextOf(info: BlameInfo, useBlock: Boolean): String = buildString {
            append(' ').append(info.author).append(" · ").append(info.whenText)
            if (!info.uncommitted && !useBlock) {
                append(" · ").append(info.subject)
            }
        }
    }
}

/**
 * 行内 blame 的绘制：灰色斜体小字。
 *
 * 显示宽度上限取 `max(280px, 编辑器可见区域宽度 3/5)`，超出部分按像素截断加省略号。
 */
class BlameRenderer(
    private val text: String,
    /** 需要高亮的字符区间（issue 引用） */
    private val highlights: List<IntRange> = emptyList()
) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val width = BlameFonts.width(editor, font(editor), text)
        return (width + HORIZONTAL_PADDING).coerceAtMost(maxWidth(editor))
    }

    override fun paint(
        inlay: Inlay<*>,
        g: java.awt.Graphics2D,
        targetRegion: java.awt.geom.Rectangle2D,
        textAttributes: com.intellij.openapi.editor.markup.TextAttributes
    ) {
        val editor = inlay.editor
        val base = font(editor)
        val metrics = editor.contentComponent.getFontMetrics(base)
        val available = maxWidth(editor) - HORIZONTAL_PADDING
        val display = ellipsize(text, { BlameFonts.width(editor, base, it) }, available)
        val baseline = targetRegion.y + (editor.lineHeight - metrics.height) / 2 + metrics.ascent
        var x = targetRegion.x + JBUI.scale(6)
        for (segment in segments(display, highlights)) {
            g.color = if (segment.highlighted) HIGHLIGHT_COLOR else JBColor.GRAY
            // 主字体缺中文字形时按 run 换用回退字体，否则会画成方框
            for (run in BlameFonts.runs(base, segment.text)) {
                g.font = run.font
                g.drawString(run.text, x.toFloat(), baseline.toFloat())
                x += editor.contentComponent.getFontMetrics(run.font).stringWidth(run.text)
            }
        }
    }

    private fun font(editor: Editor) = editor.colorsScheme.getFont(EditorFontType.ITALIC)

    /** 按高亮区间切段，绘制时分段上色 */
    data class Segment(val text: String, val highlighted: Boolean)

    companion object {

        /** 左右留白（6 + 2），随 HiDPI 缩放；show() 判断行内是否放得下时也用它 */
        val HORIZONTAL_PADDING = JBUI.scale(8)

        /** issue 引用的高亮色（浅色 / 深色主题各一） */
        val HIGHLIGHT_COLOR = UiTokens.accent

        private const val MIN_WIDTH = 280

        private const val ELLIPSIS = "…"

        /**
         * 纯函数：把文本按高亮区间切成若干段。
         * 越界 / 空 / 倒序的区间会被忽略（文本可能已被省略号截断）。
         */
        fun segments(text: String, highlights: List<IntRange>): List<Segment> {
            val valid = highlights
                .filter { it.first >= 0 && it.last >= it.first && it.last < text.length }
                .sortedBy { it.first }
            if (valid.isEmpty()) {
                return listOf(Segment(text, false))
            }
            val out = mutableListOf<Segment>()
            var cursor = 0
            valid.forEach { range ->
                if (range.first > cursor) {
                    out += Segment(text.substring(cursor, range.first), false)
                }
                val start = maxOf(range.first, cursor)
                if (range.last > cursor) {
                    out += Segment(text.substring(start, range.last + 1), true)
                    cursor = range.last + 1
                }
            }
            if (cursor < text.length) {
                out += Segment(text.substring(cursor), false)
            }
            return out
        }

        /** inlay 最大宽度：至少 [MIN_WIDTH]，最多占编辑器可见区域宽度的 3/5 */
        fun maxWidth(editor: Editor): Int {
            val visible = editor.scrollingModel.visibleArea.width
            return maxOf(JBUI.scale(MIN_WIDTH), visible * 3 / 5)
        }

        /**
         * 纯函数：在 [maxWidth] 内截断 [text] 并追加省略号；
         * [widthOf] 为文本宽度度量（便于用字符数做单测）。
         * 即使一个字符都放不下，也至少返回省略号。
         */
        fun ellipsize(text: String, widthOf: (String) -> Int, maxWidth: Int): String {
            if (widthOf(text) <= maxWidth) return text
            var low = 0
            var high = text.length
            while (low < high) {
                val mid = (low + high + 1) / 2
                if (widthOf(text.take(mid)) + widthOf(ELLIPSIS) <= maxWidth) low = mid else high = mid - 1
            }
            return text.take(low) + ELLIPSIS
        }
    }
}

/**
 * commit message 的块状 inlay 绘制：在当前行下方逐行渲染完整提交信息，保留换行结构。
 *
 * 单行超过宽度上限时**自动换行**（优先在空格处断行，超长单词/中文按字符断行），
 * 不再用省略号截断内容；仅当换行后的总行数超过 [MAX_LINES] 时省略剩余行。
 */
class BlameBlockRenderer(private val messageLines: List<String>) : EditorCustomElementRenderer {

    /** 换行结果依赖编辑器字体度量，按 inlay 惰性计算并缓存 */
    private var wrappedCache: List<String>? = null

    private fun displayLines(editor: Editor): List<String> {
        wrappedCache?.let { return it }
        val base = font(editor)
        val maxTextWidth = BlameRenderer.maxWidth(editor) - LEFT_PADDING - RIGHT_PADDING
        val wrapped = messageLines.flatMap {
            BlameBlockRenderer.wrap(it, { s -> BlameFonts.width(editor, base, s) }, maxTextWidth)
        }
        return BlameBlockRenderer.displayLinesOf(wrapped, MAX_LINES).also { wrappedCache = it }
    }

    override fun calcWidthInPixels(inlay: Inlay<*>): Int {
        val editor = inlay.editor
        val base = font(editor)
        val widest = displayLines(editor).maxOfOrNull { BlameFonts.width(editor, base, it) } ?: 0
        return (widest + LEFT_PADDING + RIGHT_PADDING).coerceAtMost(BlameRenderer.maxWidth(editor))
    }

    override fun calcHeightInPixels(inlay: Inlay<*>): Int =
        inlay.editor.lineHeight * displayLines(inlay.editor).size

    override fun paint(
        inlay: Inlay<*>,
        g: java.awt.Graphics2D,
        targetRegion: java.awt.geom.Rectangle2D,
        textAttributes: com.intellij.openapi.editor.markup.TextAttributes
    ) {
        val editor = inlay.editor
        val base = font(editor)
        val metrics = editor.contentComponent.getFontMetrics(base)
        g.color = JBColor.GRAY
        val lineHeight = editor.lineHeight
        var y = targetRegion.y
        for (line in displayLines(editor)) {
            val baseline = y + (lineHeight - metrics.height) / 2 + metrics.ascent
            var x = targetRegion.x + LEFT_PADDING
            for (run in BlameFonts.runs(base, line)) {
                g.font = run.font
                g.drawString(run.text, x.toFloat(), baseline.toFloat())
                x += editor.contentComponent.getFontMetrics(run.font).stringWidth(run.text)
            }
            y += lineHeight
        }
    }

    private fun font(editor: Editor) = editor.colorsScheme.getFont(EditorFontType.ITALIC)

    private fun metrics(editor: Editor) = editor.contentComponent.getFontMetrics(font(editor))

    companion object {

        private val LEFT_PADDING = JBUI.scale(12)
        private val RIGHT_PADDING = JBUI.scale(6)

        /** 最多渲染的视觉行数，避免超大块 inlay 遮挡代码 */
        const val MAX_LINES = 10

        /**
         * 纯函数：取前 [maxLines] 行，超出时追加省略行（可测）。
         */
        fun displayLinesOf(messageLines: List<String>, maxLines: Int): List<String> {
            if (messageLines.size <= maxLines) return messageLines
            return messageLines.take(maxLines) + "…"
        }

        /**
         * 纯函数：把一行文本按 [widthOf] 度量折成若干段，每段宽度不超过 [maxWidth]。
         * 断行点优先选段内最后一个空格（不丢语义）；没有空格（中文/超长单词）时按字符硬断。
         * 空行原样返回单段。
         */
        fun wrap(text: String, widthOf: (String) -> Int, maxWidth: Int): List<String> {
            if (text.isEmpty() || widthOf(text) <= maxWidth || maxWidth <= 0) return listOf(text)
            val out = ArrayList<String>()
            var rest = text
            while (widthOf(rest) > maxWidth) {
                // 二分找能放下的最长前缀
                var low = 0
                var high = rest.length
                while (low < high) {
                    val mid = (low + high + 1) / 2
                    if (widthOf(rest.take(mid)) <= maxWidth) low = mid else high = mid - 1
                }
                var cut = low
                if (cut <= 0) cut = 1 // 至少前进一个字符，避免死循环
                // 段内优先在最后一个空格处断行（空格丢弃）
                val space = rest.take(cut).lastIndexOf(' ')
                if (space > 0) cut = space + 1
                out += rest.take(cut).trimEnd()
                rest = rest.drop(cut).trimStart()
            }
            if (rest.isNotEmpty()) out += rest
            return out
        }
    }
}
