package io.github.xbeeant.batiskit.template.ide

import com.intellij.lexer.Lexer
import com.intellij.lexer.LexerBase
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.tree.IElementType

/**
 * `.tpl` 的词法高亮。
 *
 * 刻意做得很薄：只把「模板语法」和「普通文本」分开，不试图理解 Java / SQL 片段
 * （那需要完整的嵌套语言注入，收益远不如下面的模板工作台）。
 * `#{...}` 是 MyBatis 的参数占位符，不能当模板指令，所以 `#` 后紧跟 `{` 时按普通文本处理。
 */
object TemplateTokens {
    val TEXT = IElementType("SMT_TEXT", TemplateLanguage)
    val VARIABLE = IElementType("SMT_VARIABLE", TemplateLanguage)
    val PLACEHOLDER = IElementType("SMT_PLACEHOLDER", TemplateLanguage)
    val DIRECTIVE = IElementType("SMT_DIRECTIVE", TemplateLanguage)
    val COMMENT = IElementType("SMT_COMMENT", TemplateLanguage)
    val RAW = IElementType("SMT_RAW", TemplateLanguage)

    val VARIABLE_KEY = TextAttributesKey.createTextAttributesKey(
        "SMT_VARIABLE", DefaultLanguageHighlighterColors.INSTANCE_FIELD
    )
    val PLACEHOLDER_KEY = TextAttributesKey.createTextAttributesKey(
        "SMT_PLACEHOLDER", DefaultLanguageHighlighterColors.INSTANCE_FIELD
    )
    val DIRECTIVE_KEY = TextAttributesKey.createTextAttributesKey(
        "SMT_DIRECTIVE", DefaultLanguageHighlighterColors.KEYWORD
    )
    val COMMENT_KEY = TextAttributesKey.createTextAttributesKey(
        "SMT_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT
    )
    val RAW_KEY = TextAttributesKey.createTextAttributesKey(
        "SMT_RAW", DefaultLanguageHighlighterColors.STRING
    )
}

class TemplateLexer : LexerBase() {

    private var buffer: CharSequence = ""
    private var endOffset = 0
    private var tokenStart = 0
    private var tokenEnd = 0
    private var tokenType: IElementType? = null

    override fun start(buffer: CharSequence, startOffset: Int, endOffset: Int, initialState: Int) {
        this.buffer = buffer
        this.endOffset = endOffset
        tokenStart = startOffset
        tokenEnd = startOffset
        tokenType = null
        advance()
    }

    override fun getState(): Int = 0
    override fun getTokenType(): IElementType? = tokenType
    override fun getTokenStart(): Int = tokenStart
    override fun getTokenEnd(): Int = tokenEnd
    override fun getBufferSequence(): CharSequence = buffer
    override fun getBufferEnd(): Int = endOffset

    override fun advance() {
        if (tokenEnd >= endOffset) {
            tokenStart = endOffset
            tokenType = null
            return
        }
        tokenStart = tokenEnd
        val c = buffer[tokenStart]
        when (c) {
            '$' -> scanVariable()
            '{' -> scanPlaceholder()
            '#' -> scanDirective()
            else -> scanPlain()
        }
        if (tokenEnd <= tokenStart) tokenEnd = tokenStart + 1
    }

    private fun scanVariable() {
        var i = tokenStart + 1
        if (i < endOffset && buffer[i] == '{') {
            i++
            while (i < endOffset && buffer[i] != '}') i++
            if (i < endOffset) i++
        } else {
            while (i < endOffset && (buffer[i].isLetterOrDigit() || buffer[i] == '_' || buffer[i] == '.')) i++
        }
        tokenEnd = i
        tokenType = TemplateTokens.VARIABLE
    }

    private fun scanPlaceholder() {
        var i = tokenStart + 1
        while (i < endOffset && buffer[i] != '}' && buffer[i] != '\n') i++
        val closed = i < endOffset && buffer[i] == '}' && i > tokenStart + 1
        if (i < endOffset && buffer[i] == '}') i++
        if (closed) {
            tokenEnd = i
            tokenType = TemplateTokens.PLACEHOLDER
        } else {
            tokenEnd = tokenStart + 1
            tokenType = TemplateTokens.TEXT
        }
    }

    private fun scanDirective() {
        val next = if (tokenStart + 1 < endOffset) buffer[tokenStart + 1] else ' '
        when {
            next == '{' -> { tokenEnd = tokenStart + 1; tokenType = TemplateTokens.TEXT }
            next == '#' -> {
                var i = tokenStart + 2
                while (i < endOffset && buffer[i] != '\n') i++
                tokenEnd = i
                tokenType = TemplateTokens.COMMENT
            }
            next == '*' -> {
                val stop = indexOf("*#", tokenStart + 2)
                tokenEnd = if (stop < 0) endOffset else stop + 2
                tokenType = TemplateTokens.COMMENT
            }
            next == '[' && tokenStart + 2 < endOffset && buffer[tokenStart + 2] == '[' -> {
                val stop = indexOf("]]#", tokenStart + 3)
                tokenEnd = if (stop < 0) endOffset else stop + 3
                tokenType = TemplateTokens.RAW
            }
            next.isLetter() -> {
                var i = tokenStart + 1
                while (i < endOffset && (buffer[i].isLetterOrDigit() || buffer[i] == '_')) i++
                tokenEnd = i
                tokenType = TemplateTokens.DIRECTIVE
            }
            else -> { tokenEnd = tokenStart + 1; tokenType = TemplateTokens.TEXT }
        }
    }

    private fun scanPlain() {
        var i = tokenStart + 1
        while (i < endOffset && buffer[i] != '$' && buffer[i] != '{' && buffer[i] != '#') i++
        tokenEnd = i
        tokenType = TemplateTokens.TEXT
    }

    private fun indexOf(marker: String, from: Int): Int {
        if (marker.isEmpty()) return -1
        var i = from
        while (i + marker.length <= endOffset) {
            var hit = true
            for (k in marker.indices) {
                if (buffer[i + k] != marker[k]) { hit = false; break }
            }
            if (hit) return i
            i++
        }
        return -1
    }
}

class TemplateSyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = TemplateLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> = when (tokenType) {
        TemplateTokens.VARIABLE -> arrayOf(TemplateTokens.VARIABLE_KEY)
        TemplateTokens.PLACEHOLDER -> arrayOf(TemplateTokens.PLACEHOLDER_KEY)
        TemplateTokens.DIRECTIVE -> arrayOf(TemplateTokens.DIRECTIVE_KEY)
        TemplateTokens.COMMENT -> arrayOf(TemplateTokens.COMMENT_KEY)
        TemplateTokens.RAW -> arrayOf(TemplateTokens.RAW_KEY)
        else -> emptyArray()
    }
}

class TemplateSyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        TemplateSyntaxHighlighter()
}
