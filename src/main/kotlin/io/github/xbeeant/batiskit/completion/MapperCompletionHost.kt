package io.github.xbeeant.batiskit.completion

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiElement
import com.intellij.psi.xml.XmlFile

/**
 * 把注入语言（SQL / OGNL）里的光标位置映射回宿主 Mapper XML。
 *
 * IntelliJ 在 `<select>` 正文上注入 SQL 后，补全拿到的 PSI 属于注入文件，
 * 直接找 `XmlText` / `namespace` 会一无所获；MybatisX 与 Free-Mybatis-Tool
 * 也是先 `getTopLevelFile()` 再 `injectedToHost()`。两个 contributor 共用这里的实现。
 */
internal object MapperCompletionHost {

    /**
     * @return 宿主文件里的 PSI 元素与偏移；本来就在 XML 里时原样返回。
     */
    fun positionOf(position: PsiElement, offset: Int): Pair<PsiElement, Int> {
        val manager = InjectedLanguageManager.getInstance(position.project)
        val top = manager.getTopLevelFile(position)
        if (top !is XmlFile || top === position.containingFile) {
            return position to offset
        }
        val hostOffset = manager.injectedToHost(
            position,
            offset.coerceIn(position.textRange.startOffset, position.textRange.endOffset),
        )
        return (top.findElementAt(hostOffset) ?: top) to hostOffset
    }
}
