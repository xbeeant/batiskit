package io.github.xbeeant.batiskit.ui

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import io.github.xbeeant.batiskit.api.ui.WrapLayout
import io.github.xbeeant.batiskit.ui.UiTokens.Density
import io.github.xbeeant.batiskit.ui.components.BannerPanel
import io.github.xbeeant.batiskit.ui.components.SearchField
import io.github.xbeeant.batiskit.ui.components.StatusBarPanel
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.KeyboardFocusManager
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyEvent
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.text.JTextComponent

/**
 * 四个工具窗口共用的紧凑骨架：Actions / Filter / Advanced / Context / Content / Status。
 *
 * 布局原则（工具窗口宽度常常只有 300~400px）：
 *  - 工具窗口标题由 IDE 标题栏提供，骨架内不再重复一行大标题；总数徽标并入动作行；
 *  - 动作行用 [WrapLayout] 自动换行，绝不互相挤压或裁字；
 *  - 搜索框独占一行，右侧不放任何控件；
 *  - Advanced 默认折叠，展开器常驻动作行首位并显示已启用项数量；
 *  - 垂直间距统一读 [Density]，所有顶部子容器水平左对齐并拉伸到全宽。
 */
class ToolWindowShell(private val title: String, content: JComponent) {

    data class Action(val text: String, val tooltip: String, val invoke: () -> Unit)

    val component: JComponent = JPanel(BorderLayout(0, Density.gap))

    private val root get() = component as JPanel

    private val countLabel = JBLabel()

    /** 总数徽标 + 筛选展开器 + 所有瞬时动作 + 可选下拉，统一换行 */
    private val actionRow = JPanel(WrapLayout(FlowLayout.LEFT, UiTokens.scale(6), UiTokens.scale(2)))
    private val advancedToggle = JButton()

    private val filterBar = JPanel(BorderLayout())
    private val advancedHost = JPanel(BorderLayout())
    private val banner = BannerPanel()
    private val status = StatusBarPanel()
    private val north = JPanel()

    private var searchField: SearchField? = null
    private var advancedComponent: JComponent? = null
    private var actions: List<Action> = emptyList()
    private var trailing: JComponent? = null
    private var userCollapsed = true
    private var previousUserCollapsed = true
    private var isNarrow = false
    private var advancedLabel = "筛选"
    private var activeCountProvider: () -> Int = { 0 }
    private var narrowCallback: ((Boolean) -> Unit)? = null
    private var clearFilters: (() -> Unit)? = null

    init {
        root.apply {
            border = UiTokens.emptyPx(Density.outerV, Density.outerH)
            name = title
            toolTipText = title
            isOpaque = true
            background = UiTokens.listBackground()
        }

        countLabel.apply {
            font = UiTokens.fontSmall()
            foreground = UiTokens.textMuted
            border = UiTokens.compound(
                UiTokens.line(UiTokens.borderSubtle, top = 1, left = 1, bottom = 1, right = 1),
                UiTokens.empty(0, 4, 0, 4)
            )
            isVisible = false
        }

        // ① Actions：展开器 + 总数 + 全部动作（换行，不隐藏、不截断文字）
        advancedToggle.apply {
            font = UiTokens.fontSmall()
            foreground = UiTokens.textSecondary
            isOpaque = false
            isContentAreaFilled = false
            isFocusPainted = true
            border = UiTokens.empty(1, 2, 1, 2)
            toolTipText = "展开或收起高级筛选"
            addActionListener {
                userCollapsed = !userCollapsed
                applyAdvancedVisibility()
            }
        }
        actionRow.apply {
            isOpaque = false
            border = Density.sectionBorder()
        }

        // ② Filter：搜索框独占整行
        filterBar.apply {
            isOpaque = false
            border = Density.sectionBorder()
        }

        // ③ Advanced（默认折叠）
        advancedHost.apply {
            isOpaque = false
            border = Density.sectionBorder()
        }

        // ④ Context Banner
        banner.isVisible = false

        listOf(actionRow, filterBar, advancedHost, banner).forEach {
            it.alignmentX = JComponent.LEFT_ALIGNMENT
        }
        north.apply {
            isOpaque = false
            layout = BoxLayout(north, BoxLayout.Y_AXIS)
            add(actionRow)
            add(filterBar)
            add(advancedHost)
            add(banner)
        }

        root.add(north, BorderLayout.NORTH)
        root.add(content, BorderLayout.CENTER)
        root.add(status, BorderLayout.SOUTH)

        rebuildActionRow()
        registerKeyboardShortcuts()
        root.addComponentListener(object : ComponentAdapter() {
            override fun componentResized(event: ComponentEvent) {
                val narrow = root.width in 1..NARROW_WIDTH
                if (narrow == isNarrow) return
                isNarrow = narrow
                if (narrow) {
                    // 记住用户原本的展开状态，加宽后原样恢复
                    previousUserCollapsed = userCollapsed
                    userCollapsed = true
                } else {
                    userCollapsed = previousUserCollapsed
                }
                applyAdvancedVisibility()
                narrowCallback?.invoke(narrow)
            }
        })
        applyAdvancedVisibility()
    }

    // ------------------------------------------------------------------ 配置

    fun setActions(actions: List<Action>) {
        this.actions = actions
        rebuildActionRow()
    }

    /** 搜索框占满整行；trailing 是可选的少量控件，放进动作行末尾 */
    fun setSearchField(field: SearchField, trailing: JComponent? = null) {
        searchField = field
        this.trailing = trailing
        filterBar.add(field, BorderLayout.CENTER)
        filterBar.revalidate()
        rebuildActionRow()
    }

    /** 注册高级筛选区。默认折叠，展开器上显示已启用项数量 */
    fun setAdvanced(label: String, component: JComponent, activeCount: () -> Int = { 0 }) {
        advancedLabel = label
        advancedComponent = component
        activeCountProvider = activeCount
        advancedHost.removeAll()
        advancedHost.add(component, BorderLayout.CENTER)
        advancedHost.revalidate()
        applyAdvancedVisibility()
    }

    fun refreshAdvancedToggle() = applyAdvancedVisibility()

    fun showBanner(
        type: BannerPanel.Type,
        heading: String,
        detail: String,
        actionText: String? = null,
        onAction: (() -> Unit)? = null,
    ) = banner.display(type, heading, detail, actionText, onAction)

    fun hideBanner() = banner.dismiss()

    fun setStatus(state: StatusBarPanel.State, message: String, detail: String = "") =
        status.update(state, message, detail)

    fun setCount(text: String) {
        countLabel.text = text
        countLabel.isVisible = text.isNotEmpty()
    }

    fun onNarrowChanged(callback: (Boolean) -> Unit) {
        narrowCallback = callback
    }

    /** Esc 的第二级行为：搜索已空时清除全部筛选 */
    fun onClearFilters(callback: () -> Unit) {
        clearFilters = callback
    }

    fun isNarrow(): Boolean = isNarrow

    // ------------------------------------------------------------------ 内部

    private fun rebuildActionRow() {
        actionRow.removeAll()
        actionRow.add(advancedToggle)
        actionRow.add(countLabel)
        actions.forEach { action ->
            actionRow.add(
                ActionLink(action.text) { action.invoke() }.apply {
                    font = UiTokens.fontSmall()
                    toolTipText = action.tooltip
                    getAccessibleContext().accessibleName = action.text
                }
            )
        }
        trailing?.let { actionRow.add(it) }
        actionRow.revalidate()
        actionRow.repaint()
    }

    private fun applyAdvancedVisibility() {
        val visible = !userCollapsed && advancedComponent != null
        advancedHost.isVisible = visible
        val count = activeCountProvider()
        advancedToggle.text = "$advancedLabel ${if (count > 0) "$count " else ""}${if (visible) "▴" else "▾"}"
        advancedToggle.toolTipText = if (isNarrow) {
            "工具窗口较窄，高级筛选已自动收起；点击可临时展开"
        } else {
            "展开或收起高级筛选（$count 项已启用）"
        }
        north.revalidate()
        north.repaint()
    }

    private fun registerKeyboardShortcuts() {
        root.registerKeyboardAction(
            {
                val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                // 在任何可编辑输入框里 "/" 都必须仍然是字符，不抢焦点；只读预览区则允许聚焦搜索
                if (focused is JTextComponent && focused.isEditable) return@registerKeyboardAction
                searchField?.focus()
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, 0),
            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT
        )
        root.registerKeyboardAction(
            {
                // 搜索框自己的 WHEN_FOCUSED 绑定负责清空并交还焦点，这里只处理其它区域
                val focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                if (focused is JTextComponent && focused.isEditable) return@registerKeyboardAction
                val field = searchField
                if (field != null && field.text.isNotEmpty()) {
                    field.text = ""
                } else {
                    clearFilters?.invoke()
                }
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT
        )
    }

    private companion object {
        const val NARROW_WIDTH = 360
    }
}
