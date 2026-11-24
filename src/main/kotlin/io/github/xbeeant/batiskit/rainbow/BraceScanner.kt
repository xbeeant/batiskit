package io.github.xbeeant.batiskit.rainbow

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

/**
 * 从 PSI 里收集能参与配对的括号叶子。
 *
 * 高亮访问器与「当前作用域高亮」动作共用这一份：只收**单字符叶子**，字符串 / 注释整段是一个
 * 叶子，天然被排除；用迭代式 DFS 且反序压栈，保证结果按文档顺序，深嵌套文件也不会爆栈。
 */
internal object BraceScanner {

    fun collect(file: PsiFile): List<BraceLevels.Brace> {
        val result = ArrayList<BraceLevels.Brace>()
        val pending = ArrayDeque<PsiElement>()
        pending.addLast(file)
        while (pending.isNotEmpty()) {
            val element = pending.removeLast()
            val firstChild = element.firstChild
            if (firstChild == null) {
                val text = element.text
                if (text.length == 1 && BraceLevels.isBrace(text[0])) {
                    result.add(BraceLevels.Brace(element.textRange.startOffset, text[0]))
                }
            } else {
                val children = ArrayList<PsiElement>()
                var child: PsiElement? = firstChild
                while (child != null) {
                    children.add(child)
                    child = child.nextSibling
                }
                for (i in children.indices.reversed()) pending.addLast(children[i])
            }
        }
        return result
    }
}
