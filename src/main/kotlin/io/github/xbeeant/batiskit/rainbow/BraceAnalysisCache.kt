package io.github.xbeeant.batiskit.rainbow

import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager

/**
 * 括号层级分析缓存：绑定在 [PsiFile] 上，文件没改、颜色数没变时，
 * 高亮 pass / 当前作用域动作直接复用上一次的 [BraceLevels.Analysis]。
 *
 * 原来每次高亮都 `collect(file)` + `analyze(...)`，大文件编辑时会反复遍历整棵 PSI；
 * 这里用平台缓存跟随文件 modificationStamp 自动失效，既不持有过期元素，也不额外堆全局表。
 */
internal object BraceAnalysisCache {

    private val KEY: Key<CachedValue<BraceLevels.Analysis>> =
        Key.create("batiskit.rainbow.brace.analysis")

    fun analysis(file: PsiFile, colorCount: Int): BraceLevels.Analysis {
        val count = colorCount.coerceAtLeast(1)
        return CachedValuesManager.getManager(file.project).getCachedValue(
            file,
            KEY,
            CachedValueProvider {
                val analysis = BraceLevels.analyze(BraceScanner.collect(file), count)
                // 依赖放到文件自身：别的文件改动不会让这份缓存失效；颜色数变化也算失效
                val tracker = ModificationTracker {
                    file.modificationStamp xor (count.toLong() shl 32)
                }
                CachedValueProvider.Result.create(analysis, tracker)
            },
            false,
        )
    }
}
