package io.github.xbeeant.batiskit.ui.components

import com.intellij.ui.components.JBLabel
import io.github.xbeeant.batiskit.api.ui.WrapLayout
import io.github.xbeeant.batiskit.ui.UiTokens
import io.github.xbeeant.batiskit.ui.UiTokens.Density
import java.awt.FlowLayout
import javax.swing.JPanel

/**
 * 统一状态位：语义色圆点 + 文字说明 + 计数 / 摘要。
 *
 * 用可换行布局而不是 BorderLayout WEST/EAST：窄工具窗口下两段文字会换行显示，
 * 不会互相挤压成「看不清的一截」。颜色绝不单独承载语义（旁边必须有文字）。
 */
class StatusBarPanel : JPanel(WrapLayout(FlowLayout.LEFT, UiTokens.scale(6), 0)) {

    enum class State { IDLE, BUSY, PAUSED, ERROR }

    private val dot = StatusDot()
    private val messageLabel = JBLabel()
    private val detailLabel = JBLabel()

    init {
        isOpaque = true
        background = UiTokens.surfaceRaised
        border = UiTokens.compound(
            UiTokens.line(UiTokens.borderSubtle, top = 1),
            UiTokens.emptyPx(Density.statusPadV, UiTokens.scale(8), Density.statusPadV, UiTokens.scale(8))
        )
        messageLabel.font = UiTokens.fontSmall()
        messageLabel.foreground = UiTokens.textSecondary
        detailLabel.font = UiTokens.fontSmall()
        detailLabel.foreground = UiTokens.textMuted
        add(dot)
        add(messageLabel)
        add(detailLabel)
    }

    fun update(state: State, message: String, detail: String = "") {
        dot.dotColor = when (state) {
            State.IDLE -> UiTokens.success
            State.BUSY -> UiTokens.info
            State.PAUSED -> UiTokens.warning
            State.ERROR -> UiTokens.error
        }
        messageLabel.text = message
        messageLabel.foreground = if (state == State.ERROR) UiTokens.error else UiTokens.textSecondary
        detailLabel.text = detail
        detailLabel.isVisible = detail.isNotEmpty()
        revalidate()
        repaint()
    }
}
