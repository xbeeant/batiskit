package io.github.xbeeant.batiskit.rainbow

/**
 * 基于 [BraceLevels.Analysis] 的作用域查询（纯 Kotlin，零平台依赖）。
 *
 * 「作用域」指一对成功配上的括号所覆盖的区间（含括号自身）。供编辑器的
 * 「高亮当前作用域」动作使用：光标落在括号上或括号内部都算在该作用域里。
 */
object BracePairs {

    /** 与 offset 处括号配对的另一个括号偏移；offset 不是已配对的括号时返回 null。 */
    fun partner(analysis: BraceLevels.Analysis, offset: Int): Int? = analysis.pairs[offset]

    /**
     * 从内到外列出所有包含 offset 的作用域。
     * 用区间长度排序而不是开括号偏移，交错失配留下的不规则区间也能稳定地「短的在里、长的在外」。
     */
    fun enclosingAll(analysis: BraceLevels.Analysis, offset: Int): List<BraceLevels.Scope> =
        analysis.scopes.filter { it.contains(offset) }.sortedBy { it.length }

    /** 最内层作用域；光标不在任何括号对里时返回 null。 */
    fun enclosing(analysis: BraceLevels.Analysis, offset: Int): BraceLevels.Scope? =
        enclosingAll(analysis, offset).firstOrNull()

    /**
     * 重复触发「高亮作用域」时向外的下一层。
     * [level] 是 0 起的层级（0 = 最内层），超出范围后回到最内层循环。
     */
    fun scopeAt(analysis: BraceLevels.Analysis, offset: Int, level: Int): BraceLevels.Scope? {
        val all = enclosingAll(analysis, offset)
        if (all.isEmpty()) return null
        val safe = ((level % all.size) + all.size) % all.size
        return all[safe]
    }
}
