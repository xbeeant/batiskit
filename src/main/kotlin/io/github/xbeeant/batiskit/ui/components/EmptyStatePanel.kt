package io.github.xbeeant.batiskit.ui.components

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import io.github.xbeeant.batiskit.ui.UiTokens
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Insets
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 空状态：图标 + 标题 + 一句「下一步」引导 + 至多一个动作。
 * 必须区分「本来就没数据」和「被筛掉了」，后者给「清除筛选」而不是让用户干等。
 */
class EmptyStatePanel : JPanel(GridBagLayout()) {

    private val heading = JBLabel()
    private val hint = JBLabel()
    private val actionHolder = JPanel(FlowLayout(FlowLayout.CENTER, UiTokens.scale(4), 0))

    init {
        isOpaque = true
        background = UiTokens.listBackground()
        border = UiTokens.empty(12, 12, 12, 12)
        heading.font = UiTokens.fontBase().deriveFont(Font.BOLD)
        heading.foreground = UiTokens.textPrimary
        hint.font = UiTokens.fontSmall()
        hint.foreground = UiTokens.textSecondary
        actionHolder.isOpaque = false

        val column = JPanel(GridBagLayout()).apply {
            isOpaque = false
            val c = GridBagConstraints().apply { gridx = 0; fill = GridBagConstraints.HORIZONTAL }
            c.gridy = 0; add(heading, c)
            c.gridy = 1
            c.insets = Insets(UiTokens.scale(4), 0, 0, 0)
            add(hint, c)
            c.gridy = 2
            c.insets = Insets(UiTokens.scale(8), 0, 0, 0)
            add(actionHolder, c)
        }
        // 空状态贴内容区顶部：居中会在内容上下各留一大片空白，工具窗口里显得空荡
        add(column, GridBagConstraints().apply {
            anchor = GridBagConstraints.NORTH
            weighty = 1.0
            insets = Insets(UiTokens.scale(16), 0, 0, 0)
        })
    }

    fun display(
        title: String,
        description: String,
        actionText: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        heading.text = title
        hint.text = description
        actionHolder.removeAll()
        if (actionText != null && onAction != null) {
            actionHolder.add(ActionLink(actionText) { onAction() })
        }
        actionHolder.revalidate()
        actionHolder.repaint()
    }
}

/**
 * 内容区切换器：列表为空时显示 [EmptyStatePanel]，否则显示真正的滚动列表。
 * 用 CardLayout 而不是 JBList.emptyText，才能放进可点击的引导动作。
 */
class ContentSwitcher(content: JComponent) : JPanel(CardLayout()) {

    private val empty = EmptyStatePanel()
    private val cards = getLayout() as CardLayout

    init {
        add(content, CONTENT)
        add(empty, EMPTY)
        cards.show(this, CONTENT)
    }

    fun showContent() {
        cards.show(this, CONTENT)
        revalidate()
        repaint()
    }

    fun showEmpty(
        title: String,
        description: String,
        actionText: String? = null,
        onAction: (() -> Unit)? = null,
    ) {
        empty.display(title, description, actionText, onAction)
        cards.show(this, EMPTY)
        revalidate()
        repaint()
    }

    private companion object {
        const val CONTENT = "content"
        const val EMPTY = "empty"
    }
}
