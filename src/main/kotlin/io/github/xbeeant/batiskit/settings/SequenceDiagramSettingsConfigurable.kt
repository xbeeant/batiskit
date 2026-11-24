package io.github.xbeeant.batiskit.settings

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import io.github.xbeeant.batiskit.sequence.BasicClassRules
import io.github.xbeeant.batiskit.sequence.SequenceDiagramSettings
import io.github.xbeeant.batiskit.ui.components.FixedHeightRow
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings → Tools → Batis Kit → 时序图：编辑「跳过基础类 / 工具类」用的三份名单。
 *
 * 名单原先写死在 `SequenceRules` 里；这里只负责编辑与持久化，判定与解析都在
 * [BasicClassRules]（纯逻辑）。设置对所有工程生效，生成对话框里的「跳过基础类」
 * 开关仍是本次是否启用过滤的总开关。
 */
class SequenceDiagramSettingsConfigurable : SearchableConfigurable {

    override fun getId(): String = "io.github.xbeeant.batiskit.sequence.settings"

    private val settings = SequenceDiagramSettings.getInstance()

    private val prefixesArea = JBTextArea(7, 44).apply { lineWrap = false }
    private val simpleNamesArea = JBTextArea(7, 44).apply { lineWrap = false }
    private val keepNamesArea = JBTextArea(3, 44).apply { lineWrap = false }

    private var panel: JComponent? = null

    override fun getDisplayName(): String = "时序图"

    override fun createComponent(): JComponent {
        val root = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }
        root.add(
            fullRow(
                JBLabel("生成时序图时，哪些类算「基础类 / 工具类」由下面三份名单决定；留空即该类名单不生效。")
            )
        )
        root.add(
            fullRow(
                JBLabel("每行一条，也可用逗号 / 分号分隔；# 之后是注释。名单对所有工程生效，改动后重新生成即生效。")
            )
        )
        root.add(Box.createVerticalStrut(JBUI.scale(6)))

        root.add(listBlock("跳过的包前缀（命中即以该前缀开头的全限定名整体跳过）", prefixesArea))
        root.add(
            listBlock(
                "跳过的简单类名（PSI 只解析得出简单名时的兜底；带包名的业务类不按简单名判断）",
                simpleNamesArea
            )
        )
        root.add(
            listBlock(
                "始终保留的类（写全限定名或简单名，优先级最高；可从整包过滤里捞回单个类）",
                keepNamesArea
            )
        )

        val actions = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            add(JButton("恢复默认名单").apply {
                toolTipText = "把三份名单恢复为插件出厂值（点 Apply / OK 后写入设置）"
                addActionListener { restoreDefaults() }
            }, BorderLayout.WEST)
        }
        root.add(fullRow(actions))
        root.add(
            fullRow(
                JBLabel("生成对话框里的「跳过基础类」开关控制本次是否启用过滤（按工程记忆）；关掉它名单就不参与判定。")
            )
        )
        root.add(
            fullRow(
                JBLabel("名单随父页的「导出 / 导入 / 恢复默认设置」一起流转；已打开的时序图点「重新生成」即可用上新名单。")
            )
        )
        root.add(Box.createVerticalGlue())
        reset()
        return root.also { panel = it }
    }

    private fun listBlock(title: String, area: JBTextArea): JComponent {
        val block = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
            isOpaque = false
        }
        block.add(JBLabel(title).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        block.add(Box.createVerticalStrut(JBUI.scale(3)))
        block.add(JBScrollPane(area).apply { alignmentX = JComponent.LEFT_ALIGNMENT })
        return fullRow(block)
    }

    override fun isModified(): Boolean {
        val state = settings.state
        return prefixesArea.text != state.basicPackagePrefixes ||
            simpleNamesArea.text != state.basicSimpleNames ||
            keepNamesArea.text != state.keepClassNames
    }

    override fun apply() {
        settings.state.basicPackagePrefixes = prefixesArea.text
        settings.state.basicSimpleNames = simpleNamesArea.text
        settings.state.keepClassNames = keepNamesArea.text
    }

    override fun reset() {
        prefixesArea.text = settings.state.basicPackagePrefixes
        simpleNamesArea.text = settings.state.basicSimpleNames
        keepNamesArea.text = settings.state.keepClassNames
        prefixesArea.caretPosition = 0
        simpleNamesArea.caretPosition = 0
        keepNamesArea.caretPosition = 0
    }

    /** 只在 UI 里还原文本，仍需用户点 Apply / OK；与「Mapper 辅助」的恢复默认前缀一致 */
    private fun restoreDefaults() {
        prefixesArea.text = BasicClassRules.DEFAULT_PREFIXES_TEXT
        simpleNamesArea.text = BasicClassRules.DEFAULT_SIMPLE_NAMES_TEXT
        keepNamesArea.text = BasicClassRules.DEFAULT_KEEP_NAMES_TEXT
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun fullRow(component: JComponent): JComponent = FixedHeightRow(component)
}
