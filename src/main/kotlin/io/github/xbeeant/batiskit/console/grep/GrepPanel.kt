package io.github.xbeeant.batiskit.console.grep

import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.DataKey
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Pair
import com.intellij.ui.components.ActionLink
import com.intellij.util.ui.JBUI
import io.github.xbeeant.batiskit.api.ui.WrapLayout
import io.github.xbeeant.batiskit.console.FilterQuery
import io.github.xbeeant.batiskit.console.GrepConsoleSettings
import io.github.xbeeant.batiskit.console.GrepExpressionHistory
import io.github.xbeeant.batiskit.console.GrepExpressionPreset
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Grep 子控制台顶部的表达式区（对标原版 `GrepPanel`）：
 *
 * - `Expression` 关键词框 + `Unless` 例外框 + `Match Case` / `Regex` 勾选；
 * - **Apply**（Ctrl+Enter）：更新过滤表达式与 tab 标题，不动已打印的行；
 * - **Reload**（Enter）：清空子控制台并按当前表达式回放父控制台的全部已有文本；
 * - **Source**：跳回来源控制台；**关闭**：关掉本 tab。
 *
 * 原版支持多行表达式（AND / OR / 排除组合），这里收敛为「一个主表达式 + 一个 Unless 例外」，
 * 覆盖最常见的用法：多关键字 AND 用空格分隔，排除用 Unless。
 */
class GrepPanel(
    val parentConsole: ConsoleView,
    val console: MyGrepConsoleView,
    initialExpression: String,
    /** Reload 时的回放动作（由创建方提供：清缓冲 + 回放父控制台已有文本） */
    private val replayFn: () -> Unit,
) : JPanel(BorderLayout()), Disposable {

    companion object {
        val GREP_PANEL: DataKey<GrepPanel> = DataKey.create("GrepPanel")
        private const val MAX_TITLE_LENGTH = 40

        fun title(expression: String, unless: String): String {
            var text = if (expression.isEmpty()) "---" else expression
            if (unless.isNotEmpty()) {
                text = "$text -$unless"
            }
            return if (text.length > MAX_TITLE_LENGTH) text.substring(0, MAX_TITLE_LENGTH) + "..." else text
        }
    }

    /** Apply 后由创建方接管：更新监听器的表达式与 tab 标题 */
    var applyCallback: ((FilterQuery, String) -> Unit)? = null

    /** 跳回来源控制台（content 创建后由动作注入） */
    var selectSourceFn: (() -> Unit)? = null

    /** 关闭本 tab（content 创建后由动作注入） */
    var closeFn: (() -> Unit)? = null

    private val expressionField = JTextField(initialExpression, 30)
    private val unlessField = JTextField(20)
    private val matchCaseBox = JCheckBox("Match Case")
    private val regexBox = JCheckBox("Regex")
    private var disposed = false

    /** 下拉里的一个可选项；preset == null 表示占位提示 */
    private data class Choice(val label: String, val preset: GrepExpressionPreset?)

    private val presetBox = ComboBox<String>()
    private val matchLabel = JLabel("命中 0 行")
    private var choices: List<Choice> = emptyList()
    private var updatingChoices = false
    private val countUpdatePending = AtomicBoolean(false)

    @Volatile
    private var lastCount = 0

    val rootComponent: JComponent get() = this

    /** 最近一次 Apply 生成的完整标题（父 tab 的 Source 描述要用） */
    var cachedTitle: String = title(initialExpression, "")
        private set

    init {
        val rows = JPanel(WrapLayout(FlowLayout.LEFT, 3, 3))
        rows.border = JBUI.Borders.empty(2)
        rows.add(JLabel("Expression:"))
        rows.add(expressionField)
        rows.add(matchCaseBox)
        rows.add(regexBox)
        val applyLink = ActionLink("Apply") { apply() }
        applyLink.toolTipText = "Ctrl+Enter：更新表达式，不动已打印的行"
        rows.add(applyLink)
        val reloadLink = ActionLink("Reload") { reload() }
        reloadLink.toolTipText = "Enter：清空本控制台并回放父控制台的全部已有日志"
        rows.add(reloadLink)
        rows.add(JLabel("Unless:"))
        rows.add(unlessField)
        val clearLink = ActionLink("清空显示") { console.clear() }
        clearLink.toolTipText = "只清空本控制台，不影响父控制台"
        rows.add(clearLink)
        rows.add(ActionLink("Source") { selectSourceFn?.invoke() })
        rows.add(ActionLink("关闭") { closeFn?.invoke() })
        presetBox.toolTipText = "选择已保存的表达式预设或最近使用过的表达式，选中后立即 Apply"
        rows.add(presetBox)
        rows.add(ActionLink("存为预设") { savePreset() })
        rows.add(ActionLink("删预设") { deletePreset() })
        matchLabel.toolTipText = "当前表达式命中并转发到本子控制台的行数；Apply / Reload 后重新计数"
        rows.add(matchLabel)
        add(rows, BorderLayout.CENTER)

        presetBox.addActionListener {
            if (updatingChoices) return@addActionListener
            val selected = choices.getOrNull(presetBox.selectedIndex)?.preset ?: return@addActionListener
            expressionField.text = selected.expression
            unlessField.text = selected.unless
            matchCaseBox.isSelected = selected.caseSensitive
            regexBox.isSelected = selected.regex
            presetBox.selectedIndex = 0
            apply()
        }
        refreshChoices()

        // 对标原版键盘语义：Enter=Reload，Ctrl/Alt+Enter=Apply
        expressionField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && (e.isControlDown || e.isAltDown)) {
                    apply()
                } else if (e.keyCode == KeyEvent.VK_ENTER) {
                    reload()
                }
            }
        })
    }

    /** 从 UI 组装过滤表达式；表达式为空返回 null（与原版一致：空表达式不匹配任何行） */
    fun createQuery(): Pair<FilterQuery, String>? {
        val expression = expressionField.text.trim()
        if (expression.isEmpty()) {
            return null
        }
        val unless = unlessField.text.trim()
        val query = FilterQuery(
            expression,
            caseSensitive = matchCaseBox.isSelected,
            forceRegex = regexBox.isSelected,
            unless = unless,
        )
        val title = title(expression, unless)
        return Pair.create(query, title)
    }

    /** 对标原版 Apply：只更新表达式与 tab 标题，不清空、不回放 */
    fun apply() {
        if (disposed) {
            return
        }
        val created = createQuery() ?: return
        cachedTitle = created.second
        val state = GrepConsoleSettings.getInstance().config
        state.recentExpressions =
            GrepExpressionHistory.record(state.recentExpressions, expressionField.text).toMutableList()
        refreshChoices()
        applyCallback?.invoke(created.first, created.second)
    }

    /** 对标原版 Reload：Apply + 清空子控制台 + 按当前表达式重放父控制台的全部已有文本 */
    fun reload() {
        if (disposed) {
            return
        }
        apply()
        console.clear()
        replayFn()
    }

    /** 命中计数由输出线程推过来；这里合并成一次 EDT 刷新，避免大日志把事件队列冲垮 */
    fun setMatchCount(count: Int) {
        lastCount = count
        if (!countUpdatePending.compareAndSet(false, true)) return
        val update = Runnable {
            countUpdatePending.set(false)
            matchLabel.text = "命中 $lastCount 行"
        }
        if (SwingUtilities.isEventDispatchThread()) update.run() else SwingUtilities.invokeLater(update)
    }

    private fun refreshChoices() {
        val state = GrepConsoleSettings.getInstance().config
        val list = mutableListOf(Choice("预设 / 最近使用…", null))
        state.expressionPresets.forEach { list += Choice("预设: ${it.label()}", it) }
        state.recentExpressions.forEach { expression ->
            list += Choice(
                "最近: ${expression.take(48)}",
                GrepExpressionPreset(name = "", expression = expression)
            )
        }
        choices = list
        updatingChoices = true
        try {
            presetBox.removeAllItems()
            list.forEach { presetBox.addItem(it.label) }
            presetBox.selectedIndex = 0
        } finally {
            updatingChoices = false
        }
    }

    private fun savePreset() {
        val expression = expressionField.text.trim()
        if (expression.isEmpty()) {
            JOptionPane.showMessageDialog(
                this, "表达式为空，先填写再保存。", "保存表达式预设", JOptionPane.WARNING_MESSAGE
            )
            return
        }
        val name = JOptionPane.showInputDialog(
            this, "预设名称：", "保存表达式预设", JOptionPane.PLAIN_MESSAGE
        )?.trim()
        if (name.isNullOrEmpty()) return
        val preset = GrepExpressionPreset(
            name = name,
            expression = expression,
            unless = unlessField.text.trim(),
            caseSensitive = matchCaseBox.isSelected,
            regex = regexBox.isSelected,
        )
        val state = GrepConsoleSettings.getInstance().config
        state.expressionPresets =
            GrepExpressionHistory.upsert(state.expressionPresets, preset).toMutableList()
        refreshChoices()
    }

    private fun deletePreset() {
        val state = GrepConsoleSettings.getInstance().config
        val names = state.expressionPresets.map { it.name }.toTypedArray()
        if (names.isEmpty()) {
            JOptionPane.showMessageDialog(
                this, "还没有保存过预设。", "删除表达式预设", JOptionPane.INFORMATION_MESSAGE
            )
            return
        }
        val chosen = JOptionPane.showInputDialog(
            this, "选择要删除的预设：", "删除表达式预设",
            JOptionPane.PLAIN_MESSAGE, null, names, names[0]
        ) as? String ?: return
        state.expressionPresets = state.expressionPresets.filterNot { it.name == chosen }.toMutableList()
        refreshChoices()
    }

    override fun dispose() {
        disposed = true
    }
}
