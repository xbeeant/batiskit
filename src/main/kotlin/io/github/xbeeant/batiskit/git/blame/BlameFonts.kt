package io.github.xbeeant.batiskit.git.blame

import com.intellij.openapi.editor.Editor
import java.awt.Font
import java.util.concurrent.ConcurrentHashMap

/**
 * 行内 blame 的字体回退。
 *
 * 编辑器字体（JetBrains Mono / Consolas / Fira Code 等）通常不含 CJK 字形。
 * 编辑器正文由 IntelliJ 自己的字体回退机制兜住，而自定义 inlay 只能拿到
 * `Graphics2D.drawString`，主字体缺字时中文会画成方框（□□□）。
 *
 * 这里手动按字符切 run：主字体能显示的用主字体，其余换成本机可显示中文的字体，
 * 绘制与宽度计算都走同一套 run，避免截断/换行判断与实际绘制不一致。
 */
object BlameFonts {

    /** 常见的中文/回退字体，按优先级挑选；最后兜底到逻辑字体 Dialog */
    private val CJK_FAMILIES = listOf(
        "Microsoft YaHei UI",
        "Microsoft YaHei",
        "SimSun",
        "Noto Sans CJK SC",
        "Source Han Sans SC",
        "PingFang SC",
        "Hiragino Sans GB",
        "WenQuanYi Micro Hei"
    )

    private const val PROBE = "中文测试"

    /** key = family|style|size */
    private val fallbackCache = ConcurrentHashMap<String, Font>()

    data class Run(val text: String, val font: Font)

    /** 按「主字体能否显示该字符」把文本切成若干 run（相邻同字体合并） */
    fun runs(base: Font, text: String): List<Run> {
        if (text.isEmpty()) {
            return emptyList()
        }
        val fallback = fallbackFor(base)
        val out = mutableListOf<Run>()
        val sb = StringBuilder()
        var current: Font = base
        text.forEach { ch ->
            val font = if (base.canDisplay(ch)) base else fallback
            if (font !== current) {
                if (sb.isNotEmpty()) {
                    out += Run(sb.toString(), current)
                    sb.setLength(0)
                }
                current = font
            }
            sb.append(ch)
        }
        if (sb.isNotEmpty()) {
            out += Run(sb.toString(), current)
        }
        return out
    }

    /** 整段文本用一个字体：主字体能全显就用主字体，否则用回退字体 */
    fun fontFor(base: Font, text: String): Font =
        if (text.isEmpty() || base.canDisplayUpTo(text) < 0) base else fallbackFor(base)

    /** 逐 run 累加宽度（不同字体度量不同） */
    fun width(editor: Editor, base: Font, text: String): Int =
        runs(base, text).sumOf { editor.contentComponent.getFontMetrics(it.font).stringWidth(it.text) }

    fun fallbackFor(base: Font): Font {
        val key = "${base.family}|${base.style}|${base.size}"
        return fallbackCache.computeIfAbsent(key) {
            CJK_FAMILIES
                .map { Font(it, base.style, base.size) }
                .firstOrNull { it.canDisplayUpTo(PROBE) < 0 }
                ?: Font(Font.DIALOG, base.style, base.size)
        }
    }
}
