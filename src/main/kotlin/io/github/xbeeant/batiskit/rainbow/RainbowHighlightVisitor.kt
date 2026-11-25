package io.github.xbeeant.batiskit.rainbow

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import io.github.xbeeant.batiskit.settings.ModuleToggles

/**
 * 语言无关的彩虹括号高亮。
 *
 * 走 `com.intellij.highlightVisitor` 而不是 `annotator`：后者按语言注册，而括号在 Java / Kotlin / SQL /
 * XML / JSON 里长得一样，按语言逐个注册既漏语言又会和语言插件自己的 annotator 抢顺序。
 *
 * 层级在 [analyze] 里**按整份文件算一次**（不依赖 `visit` 的遍历顺序，也不受增量高亮只重算局部范围影响），
 * `visit` 只做「按偏移查表 + 加一条 INFORMATION 高亮」；颜色由 TextAttributesKey 决定，随配色方案走。
 */
class RainbowHighlightVisitor : HighlightVisitor, DumbAware {

    private var holder: HighlightInfoHolder? = null
    private var colors: Map<Int, Int> = emptyMap()

    override fun suitableForFile(file: PsiFile): Boolean {
        if (!ModuleToggles.isEnabled(ModuleToggles.RAINBOW)) return false
        val settings = RainbowSettings.getInstance().state
        return settings.enabled &&
            file.textLength <= RainbowSettings.maxFileLength() &&
            RainbowSettings.acceptsFile(file.language.id, file.name)
    }

    override fun analyze(
        file: PsiFile,
        updateWholeFile: Boolean,
        holder: HighlightInfoHolder,
        action: Runnable
    ): Boolean {
        val settings = RainbowSettings.getInstance().state
        val count = RainbowSettings.colorCount()
        // 1) 通用的 () [] {}：复用按文件 + 修改戳缓存的分析，文件未变时不重复扫整棵 PSI
        val braceAnalysis = BraceAnalysisCache.analysis(file, count)
        val combined = HashMap<Int, Int>(braceAnalysis.colors.size)
        combined.putAll(braceAnalysis.colors)

        // 2) XML 标签尖括号：不能按 "<"…">" 配对（同一行就闭合了，嵌套标签会全落第 0 层），
        //    改按 XmlTag 的父子层级算，开闭标签的四个尖括号同色
        if ((settings.xmlTagBrackets || settings.rainbowifyTagNames) && file is XmlFile) {
            combined.putAll(xmlTagColors(file, count, settings))
        }

        this.holder = holder
        this.colors = combined
        try {
            action.run()
        } finally {
            this.holder = null
            this.colors = emptyMap()
        }
        return true
    }

    override fun visit(element: PsiElement) {
        val currentHolder = holder ?: return
        if (colors.isEmpty() || element.firstChild != null) return

        val range = element.textRange
        // 大多数括号是单字符叶子；XML 的 "</"、"/>" 与标签名是多字符叶子，
        // 要按真实字符所在的偏移逐个找颜色（上限 64 防止超长叶子退化成逐字符扫表）
        val offsets: IntArray = when {
            range.length <= 1 -> intArrayOf(range.startOffset)
            range.length <= 64 -> IntArray(range.length) { range.startOffset + it }
            else -> intArrayOf(range.startOffset, range.endOffset - 1)
        }

        for (offset in offsets) {
            val color = colors[offset] ?: continue
            val key = RainbowColors.KEYS[color.coerceIn(0, RainbowColors.COUNT - 1)]
            val info = HighlightInfo.newHighlightInfo(HighlightInfoType.INFORMATION)
                .range(element, offset, offset + 1)
                .textAttributes(key)
                .create() ?: continue
            currentHolder.add(info)
        }
    }

    override fun clone(): HighlightVisitor = RainbowHighlightVisitor()

    /**
     * XML / HTML：标签自身的 `<` `>` `</` `/>` 四个尖括号按标签嵌套深度取色；
     * 打开「标签名着色」后，开标签里的名字也逐字符取同一层级色。
     */
    private fun xmlTagColors(file: XmlFile, count: Int, settings: RainbowSettings.State): Map<Int, Int> {
        val root = file.rootTag ?: return emptyMap()
        val text = file.text
        val result = HashMap<Int, Int>()

        fun putIfBracket(offset: Int, expected: Char, color: Int) {
            if (offset in text.indices && text[offset] == expected) result[offset] = color
        }

        fun walk(tag: XmlTag, depth: Int) {
            val color = depth % count
            val range = tag.textRange
            // 标签名只在开标签头部（第一个 '>' 之前）里找，避免误伤同名的属性值 / 正文
            val gt = text.indexOf('>', range.startOffset)
            val headerEnd = if (gt in range.startOffset until range.endOffset) gt else range.endOffset
            var child: PsiElement? = tag.firstChild
            while (child != null) {
                if (child.firstChild == null) {
                    val token = child.text
                    val start = child.textRange.startOffset
                    if (settings.xmlTagBrackets) {
                        when (token) {
                            "<", "</" -> putIfBracket(start, '<', color)
                            ">" -> putIfBracket(start, '>', color)
                            "/>" -> putIfBracket(start + 1, '>', color)
                        }
                    }
                    if (settings.rainbowifyTagNames && token == tag.name &&
                        start in range.startOffset until headerEnd
                    ) {
                        for (offset in start until child.textRange.endOffset) result[offset] = color
                    }
                }
                child = child.nextSibling
            }
            if (settings.xmlTagBrackets) {
                // token 切分方式因语言而异（HTML / JSP / 自定义 XML），兜底至少把标签首尾的尖括号带上
                putIfBracket(range.startOffset, '<', color)
                putIfBracket(range.endOffset - 1, '>', color)
            }
            for (sub in tag.subTags) walk(sub, depth + 1)
        }

        walk(root, 0)
        return result
    }
}
