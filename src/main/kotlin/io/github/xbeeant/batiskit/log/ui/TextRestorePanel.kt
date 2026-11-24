package io.github.xbeeant.batiskit.log.ui

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.xbeeant.batiskit.log.MyBatisLogBundle.message
import io.github.xbeeant.batiskit.log.MyBatisLogParser
import io.github.xbeeant.batiskit.log.MyBatisLogService
import io.github.xbeeant.batiskit.log.toConfig
import io.github.xbeeant.batiskit.support.SensitiveData
import io.github.xbeeant.batiskit.ui.UiTokens
import io.github.xbeeant.batiskit.ui.UiTokens.Density
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ScrollPaneConstants

/**
 * 「文本还原」工作台：左边粘一段 MyBatis / iBatis 原始日志，右边看还原后（带语法高亮）的 SQL。
 *
 * 与工具窗口的捕获页共用同一个 [MyBatisLogParser] 与设置里的解析前缀 / 格式化选项，
 * 但用一次性解析器，不写回捕获列表，避免和控制台里已经抓到的语句重复。
 */
class TextRestorePanel(private val project: Project) : JPanel(BorderLayout(0, Density.gap)) {

    private val service = MyBatisLogService.getInstance(project)

    private val input = JBTextArea()
    private val output = SqlPreviewPane()
    private val status = JBLabel()

    /** 最近一次还原结果，供「复制结果」使用 */
    private var restoredText: String = ""

    init {
        isOpaque = false
        input.apply {
            font = output.font
            lineWrap = false
            border = UiTokens.emptyPx(Density.fieldPadV, Density.fieldPadH)
            toolTipText = message("text.title")
        }
        output.setHint(message("text.empty"))
        status.apply {
            font = UiTokens.fontSmall()
            foreground = UiTokens.textSecondary
            border = UiTokens.empty(0, 2, 0, 2)
        }

        val actions = JPanel(FlowLayout(FlowLayout.LEFT, UiTokens.scale(4), 0)).apply {
            isOpaque = false
            add(JButton(message("text.demo")).apply { addActionListener { loadDemo() } })
            add(JButton(message("text.clear")).apply { addActionListener { clearInput() } })
            add(JButton(message("text.restore")).apply { addActionListener { restore() } })
            add(JButton(message("text.copy")).apply { addActionListener { copyResult() } })
        }

        val left = JPanel(BorderLayout(0, Density.gap)).apply {
            isOpaque = false
            add(label(message("text.title")), BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(input, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED), BorderLayout.CENTER)
        }
        val right = JPanel(BorderLayout(0, Density.gap)).apply {
            isOpaque = false
            add(label(message("text.result")), BorderLayout.NORTH)
            add(ScrollPaneFactory.createScrollPane(output, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED), BorderLayout.CENTER)
        }

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT).apply {
            leftComponent = left
            rightComponent = right
            resizeWeight = 0.5
            dividerSize = Density.divider
            border = JBUI.Borders.empty()
        }

        val south = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(status, BorderLayout.CENTER)
        }
        add(actions, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)
        add(south, BorderLayout.SOUTH)
    }

    private fun label(text: String) = JBLabel(text).apply {
        font = UiTokens.fontSmall()
        foreground = UiTokens.textSecondary
        border = UiTokens.empty(1, 2, 1, 2)
    }

    private fun loadDemo() {
        input.text = DEMO
        input.caretPosition = 0
        restore()
    }

    private fun clearInput() {
        input.text = ""
        restoredText = ""
        status.text = ""
        output.setHint(message("text.empty"))
    }

    private fun restore() {
        val text = input.text
        if (text.isBlank()) {
            restoredText = ""
            status.text = ""
            output.setHint(message("text.empty"))
            return
        }
        val parser = MyBatisLogParser(service.settings.toConfig())
        val result = parser.feed(if (text.endsWith('\n')) text else "$text\n")
        if (result.added.isEmpty()) {
            restoredText = ""
            status.text = ""
            output.setHint(message("text.noResult"))
            return
        }
        restoredText = result.added.joinToString("\n\n") { entry ->
            service.restoredSql(entry.sql, entry.parameters)
        }
        output.setSql(restoredText)
        status.text = message("text.count", result.added.size)
    }

    private fun copyResult() {
        if (restoredText.isEmpty()) {
            return
        }
        // 与捕获页一致：复制前遮蔽可能出现在参数里的凭据
        CopyPasteManager.copyTextToClipboard(SensitiveData.maskText(restoredText))
        status.text = message("text.copied")
    }

    private companion object {
        val DEMO = """
            MyBatis Log Test: DEBUG sql1 -  ==>  Preparing: select * from t_table where name = ?
            MyBatis Log Test: DEBUG sql1 -  ==> Parameters: hello(String)
            MyBatis Log Test: INFO sql2 -  ==>  Preparing: update t_table set name = ? where id = ?
            MyBatis Log Test: INFO sql2 -  ==> Parameters: world(String), 123(Integer)
            MyBatis Log Test: WARN sql3 -  ==>  Preparing: delete from t_table where id = ?
            MyBatis Log Test: WARN sql3 -  ==> Parameters: 123(Integer)
            MyBatis Log Test: ERROR sql4 - ==>  Preparing: select * from t_table order by id asc
            MyBatis Log Test: ERROR sql4 - ==>  Parameters:
        """.trimIndent()
    }
}
