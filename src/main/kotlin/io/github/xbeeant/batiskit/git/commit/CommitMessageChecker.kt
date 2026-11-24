package io.github.xbeeant.batiskit.git.commit

import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import io.github.xbeeant.batiskit.git.GitRepos
import io.github.xbeeant.batiskit.git.GitSettings
import io.github.xbeeant.batiskit.git.issue.IssueLinks

/**
 * 提交信息校验（GitToolBox 的 Commit message validation）：
 * - 必须匹配配置的正则；
 * - 必须包含 issue 引用（按 IDE Issue Navigation 配置识别）。
 *
 * 不满足时弹确认框，用户可以选择「仍然提交」。
 */
class CommitMessageCheckinHandlerFactory : CheckinHandlerFactory() {

    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        // 只对 Git 工程生效（SVN 等其它 VCS 的提交信息规则不归本插件管）
        if (GitRepos.all(panel.project).isEmpty()) {
            return CheckinHandler.DUMMY
        }
        return object : CheckinHandler() {

            override fun beforeCheckin(): ReturnResult {
                val state = GitSettings.getInstance().state
                if (!state.commitMessageCheckEnabled) {
                    return ReturnResult.COMMIT
                }
                val message = panel.commitMessage
                val issueKeys = if (state.commitMessageRequireIssue) {
                    IssueLinks.findIn(panel.project, message).map { it.key }
                } else {
                    emptyList()
                }
                val problem = CommitMessageRules.check(
                    message = message,
                    regex = state.commitMessageRegex,
                    requireIssue = state.commitMessageRequireIssue,
                    issueKeys = issueKeys
                ) ?: return ReturnResult.COMMIT

                val choice = Messages.showYesNoDialog(
                    panel.project,
                    "$problem\n\n仍然提交？",
                    "提交信息校验",
                    "仍然提交",
                    "返回修改",
                    Messages.getWarningIcon()
                )
                return if (choice == Messages.YES) {
                    ReturnResult.COMMIT
                } else {
                    ReturnResult.CANCEL
                }
            }
        }
    }
}

/** 校验规则（纯函数，便于单测） */
object CommitMessageRules {

    /** 返回第一条不满足的提示；全部通过返回 null */
    fun check(message: String, regex: String, requireIssue: Boolean, issueKeys: List<String>): String? {
        val trimmed = regex.trim()
        if (trimmed.isNotEmpty()) {
            val pattern = runCatching { Regex(trimmed) }.getOrNull()
                ?: return "提交信息校验正则无效：$trimmed"
            if (!pattern.containsMatchIn(message)) {
                return "提交信息必须匹配正则：$trimmed"
            }
        }
        if (requireIssue && issueKeys.isEmpty()) {
            return "提交信息必须包含 issue 引用（规则来自 Settings / Version Control / Issue Navigation）"
        }
        return null
    }
}
