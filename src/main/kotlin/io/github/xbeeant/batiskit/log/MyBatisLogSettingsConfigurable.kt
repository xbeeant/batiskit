package io.github.xbeeant.batiskit.log

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.COLUMNS_TINY
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import io.github.xbeeant.batiskit.log.MyBatisLogBundle.message
import javax.swing.JComponent

/**
 * Settings → Tools → Batis Kit → MyBatis Log
 *
 * 按「功能 / 输出格式 / 日志解析」三组组织，解析前缀各带一个恢复默认按钮；
 * Apply 之后通过 [MyBatisLogService.settingsChanged] 热更新解析器与工具窗口，不用重开工程。
 */
class MyBatisLogSettingsConfigurable(private val project: Project) : SearchableConfigurable {

    override fun getId(): String = "io.github.xbeeant.batiskit.log.settings"

    private val state = MyBatisLogSettings.getInstance().state

    private var panel: DialogPanel? = null

    private lateinit var preparingCell: Cell<JBTextField>
    private lateinit var parametersCell: Cell<JBTextField>

    override fun getDisplayName(): String = "MyBatis Log"

    override fun createComponent(): JComponent = panel {
        group(message("settings.group.features")) {
            row {
                checkBox(message("settings.capture"))
                    .bindSelected(state::captureEnabled)
            }
            row { comment(message("settings.capture.tip")) }
            row(message("settings.maxEntries")) {
                textField()
                    .columns(COLUMNS_TINY)
                    .bindIntText(state::maxEntries)
                    .comment(message("settings.maxEntries.tip"))
            }
            row(message("settings.filterKeywords")) {
                textField()
                    .columns(COLUMNS_MEDIUM)
                    .bindText(state::filterKeywords)
                    .comment(message("settings.filterKeywords.tip"))
            }
        }
        group(message("settings.group.output")) {
            row {
                checkBox(message("settings.format"))
                    .bindSelected(state::formatSql)
            }
            row { comment(message("settings.format.tip")) }
            row {
                checkBox(message("settings.semicolon")).bindSelected(state::appendSemicolon)
                checkBox(message("settings.date")).bindSelected(state::normalizeDate)
            }
            row {
                checkBox(message("settings.simpleFormat")).bindSelected(state::simpleFormat)
                checkBox(message("settings.newLineWithComment")).bindSelected(state::newLineWithComment)
            }
            row {
                checkBox(message("settings.indexLog")).bindSelected(state::indexLog)
                checkBox(message("settings.impactLog")).bindSelected(state::impactLog)
            }
        }
        group(message("settings.group.parsing")) {
            row(message("settings.preparing")) {
                preparingCell = textField()
                    .columns(COLUMNS_MEDIUM)
                    .bindText(state::preparingPrefix)
                button(message("settings.reset")) {
                    preparingCell.component.text = MyBatisLogConfig.DEFAULT_PREPARING
                }
            }
            row(message("settings.parameters")) {
                parametersCell = textField()
                    .columns(COLUMNS_MEDIUM)
                    .bindText(state::parametersPrefix)
                button(message("settings.reset")) {
                    parametersCell.component.text = MyBatisLogConfig.DEFAULT_PARAMETERS
                }
            }
            row { comment(message("settings.prefix.tip")) }
            row(message("settings.endMask")) {
                textField()
                    .columns(COLUMNS_TINY)
                    .bindText(state::endMask)
                    .comment(message("settings.endMask.tip"))
            }
            row(message("settings.lineLimit")) {
                textField()
                    .columns(COLUMNS_TINY)
                    .bindIntText(state::maxLineLength)
                    .comment(message("settings.lineLimit.tip"))
            }
            row {
                checkBox(message("settings.iBatis")).bindSelected(state::iBatisEnabled)
            }
        }
    }.also { panel = it }

    override fun isModified(): Boolean = panel?.isModified() ?: false

    override fun apply() {
        panel?.apply()
        state.maxEntries = state.maxEntries.coerceAtLeast(20)
        state.maxLineLength = state.maxLineLength.coerceAtLeast(0)
        // 前缀留空时回写默认值，并同步输入框，避免 isModified 一直为真
        if (state.preparingPrefix.isBlank()) {
            state.preparingPrefix = MyBatisLogConfig.DEFAULT_PREPARING
            if (::preparingCell.isInitialized) {
                preparingCell.component.text = MyBatisLogConfig.DEFAULT_PREPARING
            }
        }
        if (state.parametersPrefix.isBlank()) {
            state.parametersPrefix = MyBatisLogConfig.DEFAULT_PARAMETERS
            if (::parametersCell.isInitialized) {
                parametersCell.component.text = MyBatisLogConfig.DEFAULT_PARAMETERS
            }
        }
        MyBatisLogService.getInstance(project).settingsChanged()
    }

    override fun reset() {
        panel?.reset()
    }
}
