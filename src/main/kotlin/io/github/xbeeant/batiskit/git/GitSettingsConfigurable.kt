package io.github.xbeeant.batiskit.git

import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.COLUMNS_MEDIUM
import com.intellij.ui.dsl.builder.COLUMNS_TINY
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * Settings → Tools → Batis Kit → Git Toolbox
 *
 * 用 Kotlin UI DSL v2 构建（与 IDEA 原生设置页同源）：group 分组、row 标签左对齐、
 * comment 弱化说明文字；控件通过 KMutableProperty0 直接绑定到设置 state，
 * isModified / apply / reset 委托给 DialogPanel，不手工维护 dirty 比较。
 */
class GitSettingsConfigurable : SearchableConfigurable {

    override fun getId(): String = "io.github.xbeeant.batiskit.git.settings"

    private val state = GitSettings.getInstance().state

    private var panel: DialogPanel? = null

    override fun getDisplayName(): String = "Git Toolbox"

    override fun createComponent(): JComponent = panel {
        group("编辑器") {
            row {
                checkBox("在行尾显示当前行 blame（作者 · 时间 · 摘要）")
                    .bindSelected(state::inlineBlameEnabled)
            }
            row {
                checkBox("多行提交信息在下方换行展开显示")
                    .bindSelected(state::blameWrapEnabled)
                    .comment("仅在上一项开启时生效")
            }
            row {
                comment(
                    "光标停在代码行上时，行尾以灰字显示该行最后的提交信息；issue 引用会高亮。" +
                        "默认不启用 blame；换行显示默认关闭——多行提交只显示首行摘要，超宽时省略号截断"
                )
            }
        }
        group("后台同步与提醒") {
            row {
                checkBox("定时 fetch 所有远程仓库")
                    .bindSelected(state::autoFetchEnabled)
            }
            row("Fetch 间隔（分钟）") {
                textField()
                    .columns(COLUMNS_TINY)
                    .bindIntText(state::autoFetchIntervalMinutes)
                    .comment("取值 1 ~ 1440")
            }
            row {
                checkBox("打开工程后立即 fetch 一次")
                    .bindSelected(state::autoFetchOnStartup)
            }
            row("Fetch 排除项") {
                textField()
                    .columns(COLUMNS_LARGE)
                    .bindText(state::autoFetchExclusions)
            }
            row {
                comment("逗号分隔：仓库绝对路径（整仓跳过）或 remote 名（只跳过该 remote）")
            }
            row {
                checkBox("本地分支落后远程（behind）时通知")
                    .bindSelected(state::behindTrackerEnabled)
            }
            row {
                checkBox("关闭工程时提醒未提交 / 待推送内容")
                    .bindSelected(state::pendingCheckEnabled)
            }
        }
        group("分支清理") {
            row("基础分支") {
                textField()
                    .columns(COLUMNS_MEDIUM)
                    .bindText(state::branchCleanupBase)
                    .comment("留空自动取 main / master / 当前分支")
            }
            row("排除 glob") {
                textField()
                    .columns(COLUMNS_LARGE)
                    .bindText(state::branchCleanupExcludes)
            }
            row("陈旧判定（天）") {
                textField()
                    .columns(COLUMNS_TINY)
                    .bindIntText(state::branchStaleDays)
                    .comment("未合并分支超过该天数没有提交，视为陈旧，可被清理")
            }
        }
        group("提交信息") {
            row {
                checkBox("提交前校验提交信息")
                    .bindSelected(state::commitMessageCheckEnabled)
            }
            row("必须匹配正则") {
                textField()
                    .columns(COLUMNS_LARGE)
                    .bindText(state::commitMessageRegex)
                    .comment("留空不校验，例如 ^(feat|fix|docs|refactor|test|chore)(\\(.+\\))?: .+")
            }
            row {
                checkBox("必须包含 issue 引用（Issue Navigation）")
                    .bindSelected(state::commitMessageRequireIssue)
            }
        }
        group("远程仓库链接") {
            row("自定义模板") {
                textField()
                    .columns(COLUMNS_LARGE)
                    .bindText(state::remoteLinkTemplate)
            }
            row {
                comment(
                    "留空时按 GitHub / GitLab / Gitee / Bitbucket / Azure DevOps 自动识别；" +
                        "配置后对所有仓库生效。占位符：{remoteUrl} {path} {line} {branch} {sha}，" +
                        "例如 https://git.example.com/{path}#L{line}"
                )
            }
        }
    }.also { panel = it }

    override fun isModified(): Boolean = panel?.isModified() ?: false

    override fun apply() {
        panel?.apply()
    }

    override fun reset() {
        panel?.reset()
    }
}
