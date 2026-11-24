package io.github.xbeeant.batiskit.rainbow

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import java.awt.Font

/**
 * 「当前作用域高亮」在每个编辑器上的临时标记与层级状态。
 *
 * 用 editor 的 user data 而不是全局 Map：编辑器关闭后自然回收，不会留着失效的
 * [RangeHighlighter] 引用。高亮是临时提示，不写进配色方案、也不持久化。
 */
internal object ScopeHighlightTracker {

    private val KEY = Key.create<Session>("batiskit.rainbow.scope.session")

    private val SCOPE_BACKGROUND = JBColor(0xFFF1C4, 0x4A3B1C)
    private val BRACE_BACKGROUND = JBColor(0xFFD54F, 0x6E5417)

    data class Session(
        val highlighters: List<RangeHighlighter>,
        val caret: Int,
        /** 当前显示的是从内向外的第几层，0 = 最内层 */
        val level: Int,
    )

    fun session(editor: Editor): Session? = editor.getUserData(KEY)

    fun clear(editor: Editor) {
        val old = editor.getUserData(KEY) ?: return
        editor.putUserData(KEY, null)
        runCatching {
            val model = editor.markupModel
            old.highlighters.forEach { runCatching { model.removeHighlighter(it) } }
        }
    }

    fun show(editor: Editor, scope: BraceLevels.Scope, level: Int, caret: Int) {
        clear(editor)
        val docEnd = editor.document.textLength
        val from = scope.open.coerceIn(0, docEnd)
        val to = (scope.close + 1).coerceIn(from, docEnd)
        val created = ArrayList<RangeHighlighter>(4)
        runCatching {
            val model = editor.markupModel
            val soft = TextAttributes().apply { backgroundColor = SCOPE_BACKGROUND }
            created += model.addRangeHighlighter(
                from, to, HighlighterLayer.SELECTION - 100, soft, HighlighterTargetArea.EXACT_RANGE
            )
            val strong = TextAttributes().apply {
                backgroundColor = BRACE_BACKGROUND
                fontType = Font.BOLD
            }
            for (offset in intArrayOf(scope.open, scope.close)) {
                if (offset in 0 until docEnd) {
                    created += model.addRangeHighlighter(
                        offset, offset + 1, HighlighterLayer.SELECTION - 99, strong,
                        HighlighterTargetArea.EXACT_RANGE
                    )
                }
            }
        }
        editor.putUserData(KEY, Session(created, caret, level))
    }
}
