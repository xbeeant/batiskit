package io.github.xbeeant.batiskit.rainbow

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction

/**
 * 编辑器右键的快速开关（勾选态 = 当前设置）。
 *
 * 切换后让所有打开工程重跑一次 daemon：高亮结果是 daemon 算出来的，不重跑就得等下一次编辑才生效。
 */
class ToggleRainbowBracketsAction : ToggleAction(
    "彩虹括号（Batis Kit）",
    "按嵌套层级给括号着色；颜色在 Color Scheme → Rainbow Brackets 里改",
    null
) {
    override fun isSelected(e: AnActionEvent): Boolean = RainbowSettings.getInstance().state.enabled

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        RainbowSettings.getInstance().state.enabled = state
        RainbowSettings.restartHighlighting()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
