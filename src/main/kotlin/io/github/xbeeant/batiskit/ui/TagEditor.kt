package io.github.xbeeant.batiskit.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.WrapLayout
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Cursor
import java.awt.event.ActionListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.border.AbstractBorder

/**
 * 标签（tag）式编辑器：值以圆角标签呈现，每个标签可单独移除；
 * 底部输入框回车或点「添加」追加新标签，重复 / 空白自动忽略。
 *
 * 仅负责交互呈现——持久化仍是逗号分隔字符串（与其他配置项一致），
 * 由调用方通过 [setTags] / [tags] 与配置互转。
 *
 * ## 布局：自动换行 + 固定高度
 *
 * 默认值有 90 多个词（SQL 保留字），一行放不下，而 `FlowLayout` 不换行——
 * 超出部分会被父容器直接裁掉，用户以为「只有前十几个生效」。所以这里：
 *
 *  - 标签区用 [WrapLayout] **按可视宽度自动换行**；
 *  - 外面套一个**高度固定**（约 5 行）的滚动区，宽度跟随容器：
 *    词多只是滚动条变长，不会把整个 Tab 撑到需要上下翻；
 *  - 右侧显示当前数量，删到一半时能知道还剩几个。
 *
 * 为什么不用表格：单列 90 多行、每行一个词，信息密度低且删除要「选中 + 按钮」两步；
 * 圆角标签扫一眼就知道有哪些，删一个是一次点击。
 */
class TagEditor(placeholder: String = "输入后回车添加") : JBPanel<TagEditor>(BorderLayout(0, 4)) {

    private val tags = LinkedHashSet<String>()

    /**
     * 标签容器：换行由 [WrapLayout] 负责，宽度跟随视口靠 [Scrollable.getScrollableTracksViewportWidth]，
     * 否则滚动区会按「一行排开」的宽度铺开、整排标签又被裁掉。
     */
    private val tagPanel = object : JPanel(WrapLayout(FlowLayout.LEFT, 4, 4)), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            JBUI.scale(16)

        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
            JBUI.scale(64)

        override fun getScrollableTracksViewportWidth(): Boolean = true
        override fun getScrollableTracksViewportHeight(): Boolean = false
    }

    private val scroll = JBScrollPane(
        tagPanel,
        JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
        JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    )

    private val input = JBTextField()
    private val addButton = JButton("添加")

    /** 当前数量（删到一半时能确认还剩几个） */
    private val counter = JBLabel()

    /** 标签增删后的回调（调用方可用于联动状态刷新，可空） */
    var onChanged: Runnable? = null

    init {
        input.emptyText.setText(placeholder)
        val add = ActionListener {
            addTag(input.text)
            input.text = ""
        }
        addButton.addActionListener(add)
        input.addActionListener(add)
        input.preferredSize = Dimension(JBUI.scale(200), input.preferredSize.height)

        tagPanel.isOpaque = false
        // 右侧留出滚动条的宽度：新 UI 的滚动条悬浮在内容上（不占布局宽度），
        // 不留白的话最后几个标签会被压在滚动条下面
        tagPanel.border = JBUI.Borders.empty(2, 2, 2, 18)

        // 固定高度 ≈ 5 行标签：词再多也只是滚动，不挤压同一 Tab 里的其它项
        scroll.apply {
            viewport.isOpaque = false
            isOpaque = false
            border = JBUI.Borders.customLine(JBColor.border(), 1, 1, 1, 1)
            preferredSize = Dimension(JBUI.scale(200), JBUI.scale(132))
            minimumSize = Dimension(JBUI.scale(120), JBUI.scale(96))
        }

        counter.foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND

        val west = JPanel(FlowLayout(FlowLayout.LEFT, 0, 2)).apply {
            isOpaque = false
            add(input)
            add(addButton)
        }
        val south = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(west, BorderLayout.WEST)
            add(counter, BorderLayout.EAST)
        }

        add(scroll, BorderLayout.CENTER)
        add(south, BorderLayout.SOUTH)
    }

    /** 覆盖当前标签集合（顺序保留，去重，忽略空白项） */
    fun setTags(values: Collection<String>) {
        tags.clear()
        values.map { it.trim() }.filter { it.isNotEmpty() }.forEach { tags.add(it) }
        rebuild()
    }

    /** 当前标签（按添加顺序） */
    fun tags(): List<String> = tags.toList()

    private fun addTag(raw: String) {
        val value = raw.trim()
        if (value.isEmpty() || !tags.add(value)) {
            return
        }
        rebuild()
        scrollToEnd()
        onChanged?.run()
    }

    private fun removeTag(value: String) {
        if (tags.remove(value)) {
            rebuild()
            onChanged?.run()
        }
    }

    private fun rebuild() {
        tagPanel.removeAll()
        if (tags.isEmpty()) {
            tagPanel.add(JBLabel("（无）").apply { border = JBUI.Borders.empty(2, 6) })
        } else {
            tags.forEach { tagPanel.add(chip(it)) }
        }
        counter.text = "${tags.size} 个"
        tagPanel.revalidate()
        tagPanel.repaint()
    }

    /** 新加的标签在末尾，滚过去让它可见（删标签时不抢滚动位置） */
    private fun scrollToEnd() {
        val bar = scroll.verticalScrollBar
        bar.value = bar.maximum
    }

    /**
     * 单个标签：圆角底色 + 文本 + 「×」移除。
     *
     * 移除按钮刻意用 [JBLabel] 而不是 [JButton]：固定尺寸的小按钮在部分 LaF 里
     * 放不下「×」会被渲染成「…」，看起来像每个标签都带个省略号；JBLabel 没有这层
     * 按钮 chrome，画出来就是干净的乘号，悬停变色 + 手型光标提供可点击感。
     */
    private fun chip(text: String): JComponent {
        val close = JBLabel("×").apply {
            font = font.deriveFont(font.size2D + 2f)
            foreground = UiTokens.textMuted
            toolTipText = "移除 $text"
            border = JBUI.Borders.empty(0, 4, 0, 0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        close.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                removeTag(text)
            }

            override fun mouseEntered(e: MouseEvent) {
                // 悬停变蓝（与平台链接色一致的明暗两套）
                close.foreground = UiTokens.accent
                close.repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                close.foreground = UiTokens.textMuted
                close.repaint()
            }
        })
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        chip.border = TagBorder()
        chip.isOpaque = false
        chip.add(JBLabel(text))
        chip.add(close)
        return chip
    }

    /** 标签的圆角底色与描边：贴近平台面板色的浅灰 / 深灰，10px 圆角，比按钮式标签轻 */
    private class TagBorder : AbstractBorder() {

        private val outline = UiTokens.borderSubtle
        private val fill = UiTokens.surfaceRaised

        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = fill
                g2.fillRoundRect(x, y, width - 1, height - 1, 10, 10)
                g2.color = outline
                g2.drawRoundRect(x, y, width - 1, height - 1, 10, 10)
            } finally {
                g2.dispose()
            }
        }

        override fun getBorderInsets(c: Component): Insets = JBUI.insets(3, 8)
    }
}
