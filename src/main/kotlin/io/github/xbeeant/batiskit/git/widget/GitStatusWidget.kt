package io.github.xbeeant.batiskit.git.widget

import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.util.Consumer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.github.xbeeant.batiskit.git.GitProjectData
import io.github.xbeeant.batiskit.git.GitSettings
import io.github.xbeeant.batiskit.git.GitSettingsConfigurable
import io.github.xbeeant.batiskit.git.blame.BlameInlayManager
import java.awt.Component
import java.awt.GridLayout
import java.awt.event.MouseEvent
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 状态栏 widget：`main ↑2 ↓5 ~3 [v1.0]`；全部干净时显示 `main ∅`。
 *
 * 点击（右下角）弹出快捷菜单，可开关行内 blame / 打开 Git Toolbox 设置，不用进 Settings 翻页面。
 */
class GitStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null

    override fun ID(): String = ID_VALUE

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
        GitProjectData.getInstance(project).addUpdateListener {
            statusBar.updateWidget(ID_VALUE)
        }
        GitProjectData.getInstance(project).refreshAsync()
    }

    override fun getText(): String {
        val data = GitProjectData.getInstance(project)
        if (data.statuses.isEmpty()) {
            return "no git"
        }
        val branch = data.statuses.first().branch
        return buildString {
            append(branch)
            if (data.totalAhead > 0) {
                append(" ↑").append(data.totalAhead)
            }
            if (data.totalBehind > 0) {
                append(" ↓").append(data.totalBehind)
            }
            if (data.uncommittedCount > 0) {
                append(" ~").append(data.uncommittedCount)
            }
            val tags = data.statuses.flatMap { it.tags }.distinct()
            if (tags.isNotEmpty()) {
                append(" ").append(tags.joinToString(" ") { "[$it]" })
            }
            if (data.totalAhead == 0 && data.totalBehind == 0 && data.uncommittedCount == 0) {
                append(" ∅")
            }
        }
    }

    override fun getTooltipText(): String {
        val data = GitProjectData.getInstance(project)
        return buildString {
            if (data.statuses.isEmpty()) {
                return "无 Git 仓库（点击打开 Git Toolbox 快捷菜单）"
            }
            data.statuses.forEach { status ->
                append(status.branch).append("（").append(status.rootPath).append("）")
                append(" ↑").append(status.ahead).append(" ↓").append(status.behind)
                append(" ~").append(status.uncommitted)
                if (status.tags.isNotEmpty()) {
                    append(" tags: ").append(status.tags.joinToString(", "))
                }
                append("\n")
            }
            append("未提交改动：").append(data.uncommittedCount).append(" 个文件")
            append("\n点击可开关行内 blame / 打开设置")
        }
    }

    override fun getAlignment(): Float = 0.5f

    /** 点击 widget 弹出快捷菜单：行内 blame 开关 + 设置入口 */
    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { event ->
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(popupPanel(), null)
            .setTitle("Git Toolbox")
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .createPopup()
            .show(RelativePoint(event))
    }

    private fun popupPanel(): JComponent {
        val panel = JPanel(GridLayout(0, 1, 0, JBUI.scale(4)))
        panel.isOpaque = true
        panel.background = UIUtil.getListBackground()
        panel.border = JBUI.Borders.empty(8)

        val settings = GitSettings.getInstance().state
        val blameToggle = JCheckBox("行内 blame（光标行提交信息）", settings.inlineBlameEnabled)
        blameToggle.isOpaque = false
        val wrapToggle = JCheckBox("blame 多行换行显示（提交信息展开在下方）", settings.blameWrapEnabled)
        wrapToggle.isOpaque = false
        wrapToggle.isEnabled = settings.inlineBlameEnabled
        blameToggle.addActionListener {
            BlameInlayManager.getInstance(project).setEnabled(blameToggle.isSelected)
            wrapToggle.isEnabled = blameToggle.isSelected
        }
        wrapToggle.addActionListener {
            settings.blameWrapEnabled = wrapToggle.isSelected
            // 让当前 inlay 按新设置重建
            BlameInlayManager.getInstance(project).reapply()
        }
        panel.add(blameToggle)
        panel.add(wrapToggle)

        val settingsLink = ActionLink("Git Toolbox 设置…") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, GitSettingsConfigurable::class.java)
        }
        settingsLink.alignmentX = Component.LEFT_ALIGNMENT
        panel.add(settingsLink)
        return panel
    }

    override fun dispose() {
        statusBar = null
    }

    companion object {
        const val ID_VALUE = "BatisKitGitStatusWidget"
    }
}
