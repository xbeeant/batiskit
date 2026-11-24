package io.github.xbeeant.batiskit.ui.components

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import io.github.xbeeant.batiskit.ui.UiTokens
import io.github.xbeeant.batiskit.ui.UiTokens.Density
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Context / 警告条：只在存在「需要用户确认的上下文」时出现。
 *
 * 窄工具窗口里标题与说明分两行，说明用 HTML 让 Swing 按可用宽度自动换行，
 * 避免长 context path 把整条 Banner 撑破或把动作挤出可视区域。
 */
class BannerPanel : JPanel(BorderLayout(UiTokens.scale(4), 0)) {

    enum class Type { WARN, INFO, ERROR }

    private val mark = JBLabel("!", JBLabel.CENTER)
    private val title = JBLabel()
    private val description = JBLabel()
    private val actionHolder = JPanel(FlowLayout(FlowLayout.RIGHT, UiTokens.scale(4), 0))
    private val titleRow = JPanel(BorderLayout(UiTokens.scale(4), 0))

    init {
        isOpaque = true
        border = UiTokens.compound(
            UiTokens.line(UiTokens.borderSubtle, top = 1, bottom = 1),
            UiTokens.emptyPx(Density.bannerPadV, UiTokens.scale(8), Density.bannerPadV, UiTokens.scale(8))
        )
        val iconSize = UiTokens.scale(16)
        mark.preferredSize = Dimension(iconSize, iconSize)
        mark.minimumSize = Dimension(iconSize, iconSize)
        mark.font = UiTokens.fontSmall().deriveFont(Font.BOLD)
        mark.horizontalAlignment = JBLabel.CENTER
        title.font = UiTokens.fontSmall().deriveFont(Font.BOLD)
        description.font = UiTokens.fontSmall()
        description.foreground = UiTokens.textSecondary
        // BoxLayout 默认按 preferredSize 限制宽度，放开后才能随 Banner 宽度自动换行
        description.maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        description.alignmentX = JComponent.LEFT_ALIGNMENT
        actionHolder.isOpaque = false

        titleRow.apply {
            isOpaque = false
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(title, BorderLayout.WEST)
            add(actionHolder, BorderLayout.EAST)
        }
        val column = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(titleRow)
            add(description)
        }
        add(mark, BorderLayout.WEST)
        add(column, BorderLayout.CENTER)
        isVisible = false
    }

    fun display(
        type: Type,
        heading: String,
        detail: String,
        actionText: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        val (foreground, background, sign) = when (type) {
            Type.WARN -> Triple(UiTokens.warning, UiTokens.warningBg, "!")
            Type.ERROR -> Triple(UiTokens.error, UiTokens.errorBg, "!")
            Type.INFO -> Triple(UiTokens.info, UiTokens.infoBg, "i")
        }
        this.background = background
        mark.foreground = foreground
        mark.text = sign
        title.foreground = foreground
        title.text = heading
        // 预折行而不是依赖 JLabel 按容器宽度动态换行：
        // 后者在组件树首次布局（宽度还是 0）时只算一行高度，后续行会被父布局裁掉
        description.text = "<html>${htmlLines(detail)}</html>"
        description.toolTipText = detail
        actionHolder.removeAll()
        if (actionText != null && onAction != null) {
            val link = ActionLink(actionText) { onAction() }
            link.font = UiTokens.fontSmall()
            actionHolder.add(link)
        }
        actionHolder.revalidate()
        actionHolder.repaint()
        isVisible = true
        revalidate()
        repaint()
    }

    fun dismiss() {
        isVisible = false
        revalidate()
    }

    /** 按显示宽度单位预折行（中文算 2），优先在空格 / 分隔符处断行，不劈开单词 */
    private fun htmlLines(text: String, unitsPerLine: Int = 44, maxLines: Int = 3): String {
        val lines = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty() && lines.size < maxLines) {
            if (widthOf(remaining) <= unitsPerLine) {
                lines.add(remaining)
                remaining = ""
                break
            }
            var units = 0
            var cut = 0
            var lastBreak = -1
            for (index in remaining.indices) {
                val char = remaining[index]
                val weight = if (char.code > 0xFF) 2 else 1
                if (units + weight > unitsPerLine) break
                units += weight
                cut = index + 1
                if (char == ' ' || char == '·' || char == '/') lastBreak = index + 1
            }
            // 断点太靠前会留下大片空白，只在断点位于后半段时采用
            if (lastBreak > unitsPerLine / 2 && lastBreak < cut) cut = lastBreak
            lines.add(remaining.substring(0, cut).trimEnd())
            remaining = remaining.substring(cut).trimStart()
        }
        if (remaining.isNotEmpty() && lines.isNotEmpty()) {
            lines[lines.lastIndex] = lines.last().trimEnd() + "…"
        }
        return lines.joinToString("<br>") { escapeHtml(it) }
    }

    private fun widthOf(text: String): Int =
        text.fold(0) { width, char -> width + if (char.code > 0xFF) 2 else 1 }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
