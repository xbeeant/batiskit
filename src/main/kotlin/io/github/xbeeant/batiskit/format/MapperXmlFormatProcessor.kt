package io.github.xbeeant.batiskit.format

import com.intellij.ide.highlighter.XmlFileType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.ExternalFormatProcessor
import com.intellij.psi.xml.XmlFile

/**
 * Mapper XML 格式化的平台接入层。
 *
 * - [MapperXmlExternalFormatter]：注册 `externalFormatProcessor`，对 Mapper XML **接管 Ctrl+Alt+L**——
 *   平台自带的 XML 格式化器会把动态 SQL 排坏，Mapper 文件改由 [MapperXmlFormatter] 处理；
 * - [MapperXmlFormatAction]：编辑器右键「格式化 Mapper XML」，手动触发整文件格式化。
 */
class MapperXmlExternalFormatter : ExternalFormatProcessor {

    override fun activeForFile(source: PsiFile): Boolean =
        MapperXmlFormatSettings.getInstance().state.enabled && isMapperFile(source)

    override fun getId(): String = "BatisKitMapperXmlFormatter"

    override fun format(
        source: PsiFile,
        range: TextRange,
        canChangeWhiteSpacesOnly: Boolean,
        enableBulkOperation: Boolean,
        formatRangesRegardlessOfTooLong: Boolean,
        groupId: Int,
    ): TextRange {
        val document = source.viewProvider.document ?: return range
        val fullText = document.text
        if (!MapperXmlFormatter.isMapperXml(fullText)) return range
        val options = MapperXmlFormatSettings.getInstance().options()
        // 把选区扩到整行，避免半行把标签切断；整文件格式化仍走原来的 format()
        val startLine = document.getLineNumber(range.startOffset.coerceIn(0, document.textLength))
        val endLine = document.getLineNumber(range.endOffset.coerceIn(0, document.textLength))
        val start = document.getLineStartOffset(startLine)
        val end = document.getLineEndOffset(endLine)
        val original = fullText.substring(start, end)
        if (original.isBlank()) return range
        val wholeFile = start == 0 && end >= document.textLength
        val formatted = if (wholeFile) {
            MapperXmlFormatter.formatOrNull(fullText, options)
        } else {
            // indentAt 给出选区起点所在的层级缩进，格式化后整体平移，和上下文对齐
            MapperXmlFormatter.formatFragment(original, MapperXmlFormatter.indentAt(fullText, start, options).length, options)
        }
        if (formatted == null) {
            // 标签不自洽（选区截断 / SQL 里裸写 <）时静默跳过：格式化失败不弹窗打扰用户
            return range
        }
        if (formatted == original) return range
        WriteCommandAction.runWriteCommandAction(source.project, COMMAND_TITLE, GROUP_ID, {
            document.replaceString(start, end, formatted)
        })
        return TextRange(start, start + formatted.length)
    }

    /** Enter 键续行的缩进提示：按所在元素的层级给 4 空格 */
    override fun indent(source: PsiFile, elementOffset: Int): String {
        val document = source.viewProvider.document ?: return ""
        val text = document.text
        val offset = elementOffset.coerceIn(0, text.length)
        return MapperXmlFormatter.indentAt(text, offset, MapperXmlFormatSettings.getInstance().options())
    }

    companion object {
        private const val COMMAND_TITLE = "格式化 Mapper XML"
        private const val GROUP_ID = "BatisKit"

        fun isMapperFile(file: PsiFile?): Boolean {
            if (file == null || file.fileType != XmlFileType.INSTANCE) return false
            val root = (file as? XmlFile)?.rootTag ?: return false
            return root.localName == MAPPER_ROOT
        }

        const val MAPPER_ROOT: String = "mapper"
    }
}

/** 编辑器右键 / Project View 右键：对当前 Mapper XML 整文件格式化（不依赖代码风格设置） */
class MapperXmlFormatAction : AnAction("格式化 Mapper XML（Batis Kit）") {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val file = event.getData(CommonDataKeys.PSI_FILE)
        event.presentation.isEnabledAndVisible =
            MapperXmlFormatSettings.getInstance().state.enabled && MapperXmlExternalFormatter.isMapperFile(file)
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.PSI_FILE) ?: return
        val document = file.viewProvider.document ?: return
        formatDocument(project, document)
        PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    companion object {

        /** 对外复用：工具窗口等地方也可以直接对 Document 做格式化 */
        fun formatDocument(project: Project, document: Document) {
            val formatted = MapperXmlFormatter.formatOrNull(document.text, MapperXmlFormatSettings.getInstance().options())
            // 解析失败静默跳过，不弹窗提醒
            if (formatted == null) return
            if (formatted == document.text) return
            WriteCommandAction.runWriteCommandAction(project, "格式化 Mapper XML", "BatisKit", {
                document.replaceString(0, document.textLength, formatted)
            })
        }
    }
}
