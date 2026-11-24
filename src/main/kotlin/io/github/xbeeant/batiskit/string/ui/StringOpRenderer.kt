package io.github.xbeeant.batiskit.string.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import io.github.xbeeant.batiskit.string.StringOp
import io.github.xbeeant.batiskit.ui.UiText
import io.github.xbeeant.batiskit.ui.UiTokens
import io.github.xbeeant.batiskit.ui.UiTokens.Density
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * 操作列表的两行渲染：第一行标题 + 分组，第二行是**对当前文本的实时结果预览**。
 * 预览由调用方传入（搜索弹窗用选区、工具窗口用输入框），所以同一个渲染器两处复用。
 *
 * [compact] 为 true 时隐藏第二行：输入框为空时每项都只是「（空结果）」，
 * 17 行重复占位纯属浪费；调用方同步把列表切成单行行高。
 *
 * 不用 SimpleColoredComponent：它在压缩后的单行高度里离屏绘制不出文字（像素回归实测为 0），
 * 标准 label 组合在单行 / 两行、选中 / 未选中下都稳定。
 */
class StringOpRenderer(private val previewOf: (StringOp) -> String) :
    JPanel(BorderLayout()), ListCellRenderer<StringOp> {

    var compact: Boolean = false

    private val title = JBLabel()
    private val group = JBLabel()
    private val secondary = JBLabel()

    init {
        isOpaque = true
        title.isOpaque = false
        group.isOpaque = false
        group.font = UiTokens.fontSmall()
        secondary.isOpaque = false
        secondary.font = UiTokens.fontSmall()

        val firstLine = JPanel(FlowLayout(FlowLayout.LEFT, UiTokens.scale(4), 0)).apply {
            isOpaque = false
            add(title)
            add(group)
        }
        // BorderLayout 而非 GridLayout：隐藏预览行时不能再给它留一格空白
        val lines = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(firstLine, BorderLayout.CENTER)
            add(secondary, BorderLayout.SOUTH)
        }
        add(lines, BorderLayout.CENTER)
        border = UiTokens.emptyPx(Density.rowPadV, Density.rowPadH)
    }

    override fun getListCellRendererComponent(
        list: JList<out StringOp>,
        value: StringOp?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean
    ): Component {
        title.text = value?.title.orEmpty()
        group.text = if (value == null) "" else ellipsizeGroup(list.width, value)
        secondary.text = if (value != null) previewOf(value) else ""
        secondary.isVisible = !compact

        background = if (selected) UIUtil.getListSelectionBackground(hasFocus) else UIUtil.getListBackground()
        val foreground = UIUtil.getListForeground()
        title.foreground = if (selected) foreground else UiTokens.textPrimary
        group.foreground = if (selected) foreground else UiTokens.textMuted
        secondary.foreground = if (selected) foreground else UiTokens.secondaryText()
        return this
    }

    /** 窄工具窗口里先保证标题完整，分组文字按剩余宽度省略 */
    private fun ellipsizeGroup(listWidth: Int, value: StringOp): String {
        val available = listWidth - Density.rowPadH * 2 - UiTokens.scale(4)
        if (available <= 0) return ""
        val titleWidth = title.getFontMetrics(title.font).stringWidth(value.title)
        return UiText.ellipsize(
            value.group.title,
            group.getFontMetrics(group.font),
            (available - titleWidth).coerceAtLeast(0)
        )
    }
}
