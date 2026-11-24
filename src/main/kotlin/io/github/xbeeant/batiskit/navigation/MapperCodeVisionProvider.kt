package io.github.xbeeant.batiskit.navigation

import com.intellij.codeInsight.codeVision.CodeVisionAnchorKind
import com.intellij.codeInsight.codeVision.CodeVisionEntry
import com.intellij.codeInsight.codeVision.CodeVisionProvider
import com.intellij.codeInsight.codeVision.CodeVisionRelativeOrdering
import com.intellij.codeInsight.codeVision.CodeVisionState
import com.intellij.codeInsight.codeVision.ui.model.ClickableTextCodeVisionEntry
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.xml.XmlTag

/**
 * Mapper 接口方法上方的 Code Vision：
 *
 *  - 有对应 XML 语句：`XML 语句 · N 行`，点击跳到该语句标签；
 *  - 没有对应语句但接口绑定了 XML：`缺 XML 语句`，点击跳到 XML 根标签；
 *  - 非 Mapper 接口（没有任何 namespace 匹配的 XML）：完全不产提示。
 *
 * 实现为平台 `com.intellij.codeInsight.codeVision.CodeVisionProvider`（2024.1 中该接口在
 * `codeInsight.codeVision` 包，而非 `codeInsight.hints.codeVision`），注册到
 * `com.intellij.codeInsight.codeVisionProvider` 扩展点。
 *
 * 计算跑在后台线程且不在 read action 里，因此 PSI 访问统一包在 [ReadAction.compute] 中；
 * 索引未就绪时返回 [CodeVisionState.NotReady]，让平台在索引完成后重算，避免把「索引还没建好」
 * 误报成「缺 XML 语句」。点击时只用 `VirtualFile` + 偏移量跳转，不把 PSI 存进 lens。
 */
class MapperCodeVisionProvider : CodeVisionProvider<Unit> {

    override val name: String = "Mapper XML 语句"

    override val relativeOrderings: List<CodeVisionRelativeOrdering> = emptyList()

    /** 显示在方法上方 */
    override val defaultAnchor: CodeVisionAnchorKind = CodeVisionAnchorKind.Top

    override val id: String = ID

    override fun isAvailableFor(project: Project): Boolean = !project.isDefault

    override fun precomputeOnUiThread(editor: Editor) = Unit

    override fun computeCodeVision(editor: Editor, uiData: Unit): CodeVisionState {
        val project = editor.project ?: return CodeVisionState.READY_EMPTY
        if (project.isDisposed || editor.isDisposed) return CodeVisionState.READY_EMPTY
        // 索引未就绪：不产提示也不产「缺语句」的假阴性，等 daemon 重算
        if (DumbService.isDumb(project)) return CodeVisionState.NotReady
        return try {
            ReadAction.compute<CodeVisionState, RuntimeException> { collect(project, editor) }
        } catch (e: ProcessCanceledException) {
            // 读动作被取消（索引重建 / 用户继续输入）：让平台重算，别把取消吞成「没有提示」
            CodeVisionState.NotReady
        } catch (e: RuntimeException) {
            CodeVisionState.READY_EMPTY
        }
    }

    /**
     * 必须在 read action 里调用。按「类」组织扫描：每个 Mapper 接口只解析一次绑定 XML，
     * 一次收集全部语句 id，再逐个方法生成 lens，避免方法数 × XML 文件数的重复扫描。
     */
    private fun collect(project: Project, editor: Editor): CodeVisionState {
        val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) as? PsiJavaFile
            ?: return CodeVisionState.READY_EMPTY
        val lenses = ArrayList<Pair<TextRange, CodeVisionEntry>>()
        var classCount = 0
        for (clazz in file.classes) {
            if (classCount++ >= MAX_CLASSES) break
            if (!clazz.isInterface) continue

            // 非 Mapper 接口（无 namespace 匹配的 XML）直接不产提示
            val xmlFiles = MapperXmlResolver.xmlFiles(clazz).take(MAX_XML_FILES)
            if (xmlFiles.isEmpty()) continue

            val statementsById = LinkedHashMap<String, MutableList<XmlTag>>()
            var tagCount = 0
            loop@ for (xml in xmlFiles) {
                val root = xml.rootTag ?: continue
                for (tag in root.subTags) {
                    if (tagCount >= MAX_STATEMENT_TAGS) break@loop
                    if (!MapperXmlResolver.isStatementTag(tag)) continue
                    val statementId = tag.getAttributeValue("id")?.trim().orEmpty()
                    if (statementId.isEmpty()) continue
                    statementsById.getOrPut(statementId) { ArrayList() }.add(tag)
                    tagCount++
                }
            }

            var methodCount = 0
            for (method in clazz.methods) {
                if (methodCount++ >= MAX_METHODS) break
                val anchor = method.nameIdentifier ?: method
                val range = anchor.textRange ?: continue
                val own = statementsById[method.name].orEmpty().take(MAX_STATEMENTS_PER_METHOD)
                val target: XmlTag? = own.firstOrNull()
                    ?: xmlFiles.firstNotNullOfOrNull { it.rootTag }
                val targetFile: VirtualFile? = target?.containingFile?.virtualFile
                val targetOffset: Int = target?.textOffset ?: 0
                val hint = if (own.isEmpty()) "缺 XML 语句" else "XML 语句 · ${lineCount(own.first())} 行"
                // 只捕获 VirtualFile + Int：lens 会跨线程存活，绝不能把 PSI 塞进点击回调
                val entry: CodeVisionEntry = ClickableTextCodeVisionEntry(
                    hint, id,
                    { _, ed -> jump(ed, targetFile, targetOffset) }
                )
                lenses += range to entry
            }
        }
        return if (lenses.isEmpty()) CodeVisionState.READY_EMPTY else CodeVisionState.Ready(lenses)
    }

    /**
     * 点击回调由平台默认的 `handleClick` 分发到 `CodeVisionPredefinedActionEntry.onClick(editor)`。
     * 241 上 PsiElement 不带 navigate，统一用 [OpenFileDescriptor] 落盘定位。
     */
    private fun jump(editor: Editor, target: VirtualFile?, offset: Int) {
        val project = editor.project ?: return
        if (project.isDisposed || target == null || !target.isValid) return
        runCatching { OpenFileDescriptor(project, target, offset.coerceAtLeast(0)).navigate(true) }
    }

    private fun lineCount(tag: XmlTag): Int = tag.text.lineSequence().count().coerceAtLeast(1)

    private companion object {
        const val ID = "io.github.xbeeant.batiskit.mapper.codeVision"
        const val MAX_CLASSES = 8
        const val MAX_XML_FILES = 20
        const val MAX_STATEMENT_TAGS = 500
        const val MAX_METHODS = 200
        const val MAX_STATEMENTS_PER_METHOD = 8
    }
}
