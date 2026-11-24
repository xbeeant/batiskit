package io.github.xbeeant.batiskit.log.ui

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import io.github.xbeeant.batiskit.log.SqlHighlighter
import io.github.xbeeant.batiskit.ui.UiTokens
import io.github.xbeeant.batiskit.ui.UiTokens.Density
import java.awt.Color
import javax.swing.JTextPane
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument

/**
 * 只读的 SQL 预览区：关键字、字符串参数、数值、注释、未还原的 `?` 分色。
 *
 * 用 StyledDocument 而不是自建 Editor，是为了不引入 PSI / 文档模型；
 * 颜色全部取 [UiTokens] 的 JBColor，IDE 切明暗主题时不需要重建组件。
 */
class SqlPreviewPane : JTextPane() {

    init {
        isEditable = false
        isOpaque = true
        background = UiTokens.surfaceSunken
        border = UiTokens.emptyPx(Density.fieldPadV, Density.fieldPadH)
        font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
    }

    /** 展示还原后的 SQL 并重新着色 */
    fun setSql(sql: String) {
        val base = baseAttributes(UiTokens.textPrimary)
        replaceDocument(sql, base)
        val doc = styledDocument
        SqlHighlighter.tokenize(sql).forEach { token ->
            doc.setCharacterAttributes(token.start, token.length, colorAttributes(token.kind), false)
        }
        caretPosition = 0
    }

    /** 无选中语句 / 解析失败时的占位提示，用弱化色且不着色 */
    fun setHint(text: String) {
        replaceDocument(text, baseAttributes(UiTokens.textMuted))
        caretPosition = 0
    }

    private fun replaceDocument(text: String, base: SimpleAttributeSet) {
        val doc: StyledDocument = styledDocument
        doc.remove(0, doc.length)
        doc.insertString(0, text, base)
    }

    private fun baseAttributes(foreground: Color): SimpleAttributeSet {
        val font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        return SimpleAttributeSet().apply {
            StyleConstants.setFontFamily(this, font.family)
            StyleConstants.setFontSize(this, font.size)
            StyleConstants.setForeground(this, foreground)
        }
    }

    private fun colorAttributes(kind: SqlHighlighter.Kind): SimpleAttributeSet = SimpleAttributeSet().apply {
        when (kind) {
            SqlHighlighter.Kind.KEYWORD -> {
                StyleConstants.setForeground(this, UiTokens.accent)
                StyleConstants.setBold(this, true)
            }

            SqlHighlighter.Kind.STRING -> StyleConstants.setForeground(this, UiTokens.success)
            SqlHighlighter.Kind.NUMBER -> StyleConstants.setForeground(this, UiTokens.warning)
            SqlHighlighter.Kind.COMMENT -> StyleConstants.setForeground(this, UiTokens.textMuted)
            SqlHighlighter.Kind.PLACEHOLDER -> {
                StyleConstants.setForeground(this, UiTokens.error)
                StyleConstants.setBold(this, true)
            }
        }
    }
}
