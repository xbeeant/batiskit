package io.github.xbeeant.batiskit.ui.components

import io.github.xbeeant.batiskit.ui.UiTokens
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 设置页表单行：高度恒等于内容首选高度，不被父级 BoxLayout 拉伸。
 *
 * BoxLayout(Y_AXIS) 在容器高于内容时会按 (maximumSize - preferredSize) 的权重
 * 把多余高度分给每个子组件；普通 JPanel 的 maximumSize 默认是 Int.MAX_VALUE，
 * 于是每一行都被撑高几十像素，末尾的 vertical glue 只分到一份 —— 表现就是
 * 「设置页行间距随窗口高度变大」。这里把 maximumSize.height 锁到
 * preferredSize.height，剩余空间自然全部沉到 glue。
 */
class FixedHeightRow(component: JComponent, gapAfter: Int = DEFAULT_GAP) : JPanel(BorderLayout()) {

    init {
        isOpaque = false
        alignmentX = JComponent.LEFT_ALIGNMENT
        if (gapAfter > 0) border = UiTokens.empty(0, 0, gapAfter, 0)
        add(component, BorderLayout.CENTER)
    }

    /** 动态取高度：内容（如 Banner）在构造之后才填充时也算得对 */
    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    companion object {
        /** 固定行距：紧凑但保留呼吸感，且不随窗口高度变化 */
        val DEFAULT_GAP: Int get() = UiTokens.scale(6)
    }
}

/** 把任意组件包成固定高度行，用于直接 add 到 BoxLayout 的滚动区 / 表格 */
fun JComponent.fixedHeight(gapAfter: Int = FixedHeightRow.DEFAULT_GAP): JComponent =
    FixedHeightRow(this, gapAfter)
