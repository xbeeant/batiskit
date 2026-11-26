package io.github.xbeeant.batiskit.navigation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * Mapper XML 语句片段的写入与跳转，供 intention 与 inspection quickfix 共用。
 *
 * 片段一律追加到根标签**末尾**。以前用 `root.addSubTag(copied, true)`，那个布尔参数是
 * “插到现有子标签之前”，片段会被塞到 XML 开头；而且它不会为新标签补换行与缩进，
 * 生成出来的文件看起来就像「格式化没生效」。这里直接改 Document，保证：
 *  - 追加在最后一个语句之后；
 *  - 与上一个语句之间恰好一个空行；
 *  - 首行缩进跟随已有语句（没有语句时用一级默认缩进），片段内部相对层次由
 *    [io.github.xbeeant.batiskit.meta.MapperSqlRules.statementXml] 给出。
 */
internal object MapperXmlStatementWriter {

    /** 追加语句并返回新标签；写入失败返回 null。调用方需自行决定是否跳转到它。 */
    fun append(project: Project, root: XmlTag, statementText: String, statementId: String): XmlTag? {
        val file = root.containingFile as? XmlFile ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val text = document.text
        // root.textRange.endOffset 在 </mapper> 之后；往前找根标签闭合处
        val closeTag = text.lastIndexOf("</${root.name}", root.textRange.endOffset - 1)
            .takeIf { it >= root.textRange.startOffset }
            ?: text.lastIndexOf("</", root.textRange.endOffset - 1)
        if (closeTag < root.textRange.startOffset) return null
        val indent = childIndent(root)
        val indented = statementText.lines().joinToString("\n") { line ->
            if (line.isBlank()) "" else indent + line
        }
        val before = text.substring(0, closeTag)
        val insert = buildString {
            if (!before.endsWith("\n")) append('\n')
            if (!before.endsWith("\n\n")) append('\n')
            append(indented).append('\n')
        }
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(closeTag, insert)
        }
        PsiDocumentManager.getInstance(project).commitDocument(document)
        // 插入后旧 PSI 偏移已失效；同名 id 取最后一个，即刚追加的片段
        return file.rootTag?.subTags?.lastOrNull { it.getAttributeValue("id") == statementId }
    }

    /** 打开语句所在文件并把光标定位到片段；延迟到 EDT，避免被 intention / quickfix 的模态状态吞掉。 */
    fun navigateTo(project: Project, xmlFile: PsiFile?, statementId: String) {
        val vf = xmlFile?.virtualFile ?: return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed || !vf.isValid) {
                return@invokeLater
            }
            ApplicationManager.getApplication().runReadAction {
                val manager = FileEditorManager.getInstance(project)
                // 直接用 openFile 返回的编辑器，不依赖 selectedTextEditor 的切换时机
                val editor = manager.openFile(vf, true)
                    .firstNotNullOfOrNull { it as? TextEditor }?.editor
                    ?: manager.selectedTextEditor
                if (editor == null || editor.virtualFile != vf) {
                    return@runReadAction
                }
                val psi = PsiManager.getInstance(project).findFile(vf) as? XmlFile
                val target = psi?.rootTag?.subTags
                    ?.lastOrNull { it.getAttributeValue("id") == statementId }
                    ?: return@runReadAction
                editor.caretModel.moveToOffset(target.textOffset)
                editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            }
        }, ModalityState.nonModal())
    }

    /** 新片段的缩进：跟随后一个已有语句；文件里还没有语句时用格式化器的默认一级缩进 */
    private fun childIndent(root: XmlTag): String {
        val last = root.subTags.lastOrNull() ?: return "    "
        val text = root.containingFile.text
        val lineStart = text.lastIndexOf('\n', last.textRange.startOffset - 1) + 1
        val prefix = text.substring(lineStart, last.textRange.startOffset)
            .takeWhile { it == ' ' || it == '\t' }
        return if (prefix.isEmpty()) "    " else prefix
    }
}
