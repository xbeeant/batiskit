package io.github.xbeeant.batiskit.string

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.ide.DataManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import io.github.xbeeant.batiskit.intention.EditorActionIds
import io.github.xbeeant.batiskit.string.ui.StringOpChooser

/**
 * Alt+Enter 入口：光标放在文本上按 Alt+Enter，选「搜索全部转换…」即可唤出带实时预览的
 * 全量操作列表，回车就地替换。名字与编辑器右键父菜单里的同一条目逐字一致（同一个
 * `StringChooserAction` 文案），保证两处菜单不漂移。
 *
 * 与右键菜单 / `⇧⌥M` 共用 [StringTransform.rangeOptions] 与 [StringTransform.apply]，
 * 差别只在于这里由平台提供 editor，不需要从 AnActionEvent 里取。
 *
 * 为什么直接实现 [IntentionAction] 而不是继承 `PsiElementBaseIntentionAction`：
 * 后者要求 `file.findElementAt(caret)` 能拿到 PSI 元素、并且文件 `canModify`，
 * 纯文本 / 控制台 / 只读文件里会直接不出现。字符串转换本来就该语言无关、
 * 只读时也能唤出（结果复制到剪贴板），所以自己实现可用性判断。
 *
 * 另外两个容易踩的坑：
 * 1. [startInWriteAction] 必须关掉——平台默认把 intention 的 invoke 包在 write action 里，
 *    而我们这里要先弹 UI，弹窗里再自己开 WriteCommandAction，否则会弹不出 / 死锁。
 * 2. [generatePreview] 返回 EMPTY——Alt+Enter 菜单的悬浮预览会在后台 read action 里回调
 *    invoke，此时弹窗同样不该出现。
 */
class StringManipulationIntention : IntentionAction, DumbAware {

    override fun getFamilyName(): String =  EditorActionIds.FAMILY

    override fun getText(): String = "搜索全部转换…"

    override fun isDumbAware(): Boolean = true

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        // 语言无关：不看文件类型、不看 PSI，只要这一行 / 选区里有可转换的文本就出现
        if (editor == null) return false
        return StringTransform.rangeOptions(project, editor).any { it.target.text.isNotBlank() }
    }

    override fun startInWriteAction(): Boolean = false

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.EMPTY

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val current = editor ?: return
        val ranges = StringTransform.rangeOptions(project, current)
        if (ranges.isEmpty()) return
        val context = DataManager.getInstance().getDataContext(current.contentComponent)
        StringOpChooser.show(context, ranges) { op, target ->
            StringTransform.apply(target, op)
        }
    }
}
