package io.github.xbeeant.batiskit.ui.components

import com.intellij.icons.AllIcons
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.xbeeant.batiskit.ui.UiTokens
import io.github.xbeeant.batiskit.ui.UiTokens.Density
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.border.Border
import javax.swing.event.DocumentEvent

/**
 * 搜索框：左搜索图标 + 输入框 + 右 `/` 快捷键提示。
 *
 * 控件边界统一 1px `--border-control`，聚焦时 2px 强调色焦点环（≥3:1）；
 * placeholder 只作提示，accessible name 独立提供；Escape 清空并交还焦点。
 */
class SearchField(placeholder: String, onChanged: () -> Unit) :
    JPanel(BorderLayout(Density.gap, 0)) {

    val textField = JBTextField()

    private val shortcut = JBLabel("/")

    var text: String
        get() = textField.text
        set(value) {
            textField.text = value
        }

    init {
        isOpaque = true
        background = UiTokens.surfaceRaised
        border = FieldBorder()

        textField.apply {
            isOpaque = false
            border = UiTokens.emptyPx(0, UiTokens.scale(1))
            font = UiTokens.fontBase()
            // 窄工具窗口里允许被压缩，不能让输入框的最小宽度把右侧控件挤出可视区
            minimumSize = java.awt.Dimension(0, preferredSize.height)
            emptyText.text = placeholder
            toolTipText = placeholder
            getAccessibleContext().accessibleName = "搜索"
            addFocusListener(object : FocusAdapter() {
                override fun focusGained(event: FocusEvent?) = repaint()
                override fun focusLost(event: FocusEvent?) = repaint()
            })
        }
        shortcut.apply {
            font = UiTokens.fontSmall()
            foreground = UiTokens.textMuted
            border = UiTokens.compound(
                UiTokens.line(UiTokens.borderSubtle, top = 1, left = 1, bottom = 1, right = 1),
                UiTokens.empty(0, 2, 0, 2)
            )
            toolTipText = "按 / 聚焦搜索"
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent?) = focus()
            })
        }
        val icon = JBLabel(AllIcons.Actions.Find).apply {
            border = JBUI.Borders.empty(0, UiTokens.scale(2), 0, 0)
        }
        add(icon, BorderLayout.WEST)
        add(textField, BorderLayout.CENTER)
        add(shortcut, BorderLayout.EAST)

        textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) = onChanged()
        })
        textField.registerKeyboardAction(
            { text = ""; textField.transferFocus() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_FOCUSED
        )
    }

    fun focus() {
        textField.requestFocusInWindow()
    }

    private inner class FieldBorder : Border {

        override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val focused = textField.hasFocus()
                g2.color = if (focused) UiTokens.accent else UiTokens.borderControl
                g2.stroke = BasicStroke(if (focused) UiTokens.scale(2).toFloat() else 1f)
                val radius = UiTokens.scale(4)
                g2.drawRoundRect(x + 1, y + 1, width - 3, height - 3, radius, radius)
            } finally {
                g2.dispose()
            }
        }

        override fun getBorderInsets(c: Component): Insets = Insets(
            Density.fieldPadV, Density.fieldPadH, Density.fieldPadV, Density.fieldPadH
        )

        override fun isBorderOpaque(): Boolean = false
    }
}
