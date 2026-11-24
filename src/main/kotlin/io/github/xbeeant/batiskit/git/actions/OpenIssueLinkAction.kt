package io.github.xbeeant.batiskit.git.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleTextAttributes
import io.github.xbeeant.batiskit.git.GitRepos
import io.github.xbeeant.batiskit.git.blame.GitBlameService
import io.github.xbeeant.batiskit.git.issue.IssueLinks
import io.github.xbeeant.batiskit.git.issue.IssueRef
import io.github.xbeeant.batiskit.git.ui.GitNotifications

/**
 * Open Issue Link：从当前行 blame 的提交信息 + 当前分支名里找 issue 引用
 * （规则来自 IDE 的 Issue Navigation 配置），单个直接打开，多个弹窗选择。
 */
class OpenIssueLinkAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && e.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val line = editor.caretModel.logicalPosition.line + 1
        ApplicationManager.getApplication().executeOnPooledThread {
            val info = runCatching { GitBlameService.getInstance(project).blame(file, line) }.getOrNull()
            val branch = GitRepos.forFile(project, file)?.currentBranch?.name.orEmpty()
            val text = buildString {
                append(branch).append(' ')
                if (info != null) {
                    append(info.subject)
                    if (info.messageLines.isNotEmpty()) {
                        append(' ').append(info.messageLines.joinToString(" "))
                    }
                }
            }
            val refs = IssueLinks.findIn(project, text)
            ApplicationManager.getApplication().invokeLater {
                when {
                    refs.isEmpty() -> GitNotifications.info(
                        project,
                        "未找到 issue 引用：请先在 Settings / Version Control / Issue Navigation 配置识别规则"
                    )

                    refs.size == 1 -> IssueLinks.open(refs.first())
                    else -> chooseAndOpen(refs)
                }
            }
        }
    }

    private fun chooseAndOpen(refs: List<IssueRef>) {
        val builder = JBPopupFactory.getInstance().createPopupChooserBuilder(refs.map { Choice(it) })
        builder.setTitle("打开 Issue 链接")
            .setRenderer(object : com.intellij.ui.ColoredListCellRenderer<Choice>() {
                override fun customizeCellRenderer(
                    list: javax.swing.JList<out Choice>,
                    value: Choice?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean
                ) {
                    if (value == null) {
                        return
                    }
                    append(value.ref.key)
                    append("  " + value.ref.url, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            })
            .setItemChosenCallback { IssueLinks.open(it.ref) }
            .createPopup()
            .showInFocusCenter()
    }

    private data class Choice(val ref: IssueRef) {
        override fun toString(): String = ref.key
    }
}
