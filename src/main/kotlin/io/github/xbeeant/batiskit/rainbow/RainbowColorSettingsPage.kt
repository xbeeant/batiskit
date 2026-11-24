package io.github.xbeeant.batiskit.rainbow

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.PlainTextSyntaxHighlighterFactory
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.options.colors.AttributesDescriptor
import com.intellij.openapi.options.colors.ColorDescriptor
import com.intellij.openapi.options.colors.ColorSettingsPage
import com.intellij.psi.tree.IElementType
import javax.swing.Icon

/**
 * Settings → Editor → Color Scheme → Rainbow Brackets（平台配色分组，不属于 Tools 下的菜单）。
 *
 * 十层颜色是标准 TextAttributesKey，跟着配色方案保存 / 导出；预览用纯文本 lexer，
 * demo 里的 `rb1..rb10` 标签只是给预览区标色，不会出现在编辑器里。
 * 实际启用几层由设置页的「循环颜色数」决定（默认 5）。
 */
class RainbowColorSettingsPage : ColorSettingsPage {

    override fun getIcon(): Icon? = null

    override fun getHighlighter(): SyntaxHighlighter = PreviewHighlighter()

    override fun getDemoText(): String = """
        <rb1>(</rb1>最外层 · 层级 1<rb1>)</rb1>
          <rb2>(</rb2>层级 2<rb3>[</rb3>层级 3<rb4>{</rb4>层级 4<rb5>{</rb5>层级 5<rb5>}</rb5><rb4>}</rb4><rb3>]</rb3><rb2>)</rb2>
        <rb1>(</rb1>另一组最外层括号仍是层级 1<rb1>)</rb1>
        <rb2>[</rb2>数组下标<rb3>{</rb3>map(foo(a, b))<rb3>}</rb3><rb2>]</rb2>
        <rb6>(</rb6>层级 6<rb7>(</rb7>7<rb8>(</rb8>8<rb9>(</rb9>9<rb10>(</rb10>10<rb10>)</rb10><rb9>)</rb9><rb8>)</rb8><rb7>)</rb7><rb6>)</rb6>
    """.trimIndent()

    override fun getAdditionalHighlightingTagToDescriptorMap(): Map<String, TextAttributesKey> =
        RainbowColors.KEYS.withIndex().associate { (index, key) -> RainbowColors.tag(index) to key }

    override fun getAttributeDescriptors(): Array<AttributesDescriptor> =
        RainbowColors.KEYS.withIndex()
            .map { (index, key) -> AttributesDescriptor(RainbowColors.NAMES[index], key) }
            .toTypedArray()

    override fun getColorDescriptors(): Array<ColorDescriptor> = ColorDescriptor.EMPTY_ARRAY

    override fun getDisplayName(): String = "Rainbow Brackets"

    private class PreviewHighlighter : SyntaxHighlighter {
        override fun getHighlightingLexer(): Lexer = PlainTextSyntaxHighlighterFactory.createPlainTextLexer()

        override fun getTokenHighlights(tokenType: IElementType): Array<TextAttributesKey> = TextAttributesKey.EMPTY_ARRAY
    }
}
