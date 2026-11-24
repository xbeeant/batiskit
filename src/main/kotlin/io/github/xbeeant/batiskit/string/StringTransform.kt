package io.github.xbeeant.batiskit.string

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

/** plugin.xml 里注册的通知组 id，UI 各处共用 */
const val STRING_NOTIFICATION_GROUP = "String Manipulation"

/**
 * 一次转换的输入与写回位置。
 *
 * - 有选区：转换选区，原地替换；
 * - 无选区但有编辑器：取光标所在整行（省掉「先选中」这一步）；
 * - 没有编辑器（例如从别处触发）：取剪贴板，转换结果再放回剪贴板。
 */
data class StringTarget(
    val text: String,
    val start: Int = 0,
    val end: Int = 0,
    val editor: Editor? = null,
    val project: Project? = null
)

/**
 * 弹窗里的一个可选作用范围：label 给用户看，target 是真正被转换并写回的位置。
 *
 * Alt+Enter 时尤其重要——光标停在 `user_name` 上，默认应该只转这个词，
 * 而不是把 `private String user_name;` 整行也卷进去。
 */
data class StringRangeOption(val label: String, val target: StringTarget)

/** 编辑器右键菜单、Alt+Enter 与搜索弹窗共用的「取文本 → 转换 → 写回」流程。 */
object StringTransform {

    /**
     * 列出当前编辑器里可以转换的范围，按优先级排序：选区 → 字符串内容 → 当前单词 → 当前行。
     * 偏移全部是文档绝对偏移，选中某一项后可直接交给 [apply] 原地替换。
     */
    fun rangeOptions(project: Project?, editor: Editor): List<StringRangeOption> {
        val out = mutableListOf<StringRangeOption>()
        val selected = editor.selectionModel.selectedText
        if (!selected.isNullOrEmpty()) {
            val start = editor.selectionModel.selectionStart
            out += StringRangeOption(
                "选区 · ${selected.length} 字符",
                StringTarget(selected, start, start + selected.length, editor, project)
            )
        }

        val document = editor.document
        if (document.textLength == 0) return out
        val offset = editor.caretModel.offset.coerceAtMost(document.textLength)
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineEnd = document.getLineEndOffset(lineNumber)
        val line = document.getText(TextRange(lineStart, lineEnd))
        val caretInLine = (offset - lineStart).coerceIn(0, line.length)

        for (span in StringRanges.spans(line, caretInLine)) {
            out += StringRangeOption(
                span.label,
                StringTarget(
                    line.substring(span.start, span.end),
                    lineStart + span.start,
                    lineStart + span.end,
                    editor,
                    project
                )
            )
        }
        return out
    }

    fun targetOf(e: AnActionEvent): StringTarget? {
        val editor = e.getData(CommonDataKeys.EDITOR)
        if (editor != null) {
            val selected = editor.selectionModel.selectedText
            if (!selected.isNullOrEmpty()) {
                val start = editor.selectionModel.selectionStart
                return StringTarget(selected, start, start + selected.length, editor, e.project)
            }
            val document = editor.document
            if (document.textLength > 0) {
                val offset = editor.caretModel.offset.coerceAtMost(document.textLength)
                val line = document.getLineNumber(offset)
                val start = document.getLineStartOffset(line)
                val end = document.getLineEndOffset(line)
                val text = document.getText(TextRange(start, end))
                if (text.isNotEmpty()) return StringTarget(text, start, end, editor, e.project)
            }
        }
        val clipboard = readClipboard()
        return if (clipboard.isNullOrEmpty()) null else StringTarget(clipboard, project = e.project)
    }

    /** 应用转换：能写回编辑器就原地替换，否则复制结果到剪贴板。 */
    fun apply(target: StringTarget, op: StringOp, options: StringOptions = StringOptions()) {
        val result = try {
            StringOps.apply(op, target.text, options)
        } catch (ex: StringOpException) {
            notify(target.project, ex.message ?: "转换失败", NotificationType.WARNING)
            return
        }

        val editor = target.editor
        val project = target.project
        if (editor == null || project == null || !editor.document.isWritable) {
            CopyPasteManager.copyTextToClipboard(result)
            notify(project, "「${op.title}」的结果已复制到剪贴板", NotificationType.INFORMATION)
            return
        }
        if (result == target.text) return

        // 弹窗打开期间文档可能被改短，写回前把范围收进当前文档，避免越界
        val length = editor.document.textLength
        val end = target.end.coerceAtMost(length)
        val start = target.start.coerceAtMost(end)
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.replaceString(start, end, result)
            editor.selectionModel.setSelection(start, start + result.length)
            editor.caretModel.moveToOffset(start + result.length)
        }
    }

    fun notify(project: Project?, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(STRING_NOTIFICATION_GROUP)
            .createNotification("String Manipulation", content, type)
            .notify(project)
    }

    fun readClipboard(): String? {
        val managed = runCatching {
            CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        }.getOrNull()
        if (!managed.isNullOrEmpty()) return managed
        // 浏览器 / 终端复制的内容不一定是平台自己的 Transferable，兜底问系统剪贴板
        return runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
        }.getOrNull()
    }
}
