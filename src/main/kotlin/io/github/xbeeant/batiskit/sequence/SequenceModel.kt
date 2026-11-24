package io.github.xbeeant.batiskit.sequence

import io.github.xbeeant.batiskit.sequence.BasicClassRules

/**
 * 时序图的数据模型：参与者（生命线）、消息（箭头）与生成选项。
 *
 * 模型刻意与「怎么分析」（PSI 调用链，见 [CallChainAnalyzer]）和「怎么画」
 * （[SequenceLayout] / SVG / PlantUML / Mermaid）解耦：纯逻辑层只认这份模型，
 * 测试可以直接手工构造参与者与消息，不必起 IDE 夹具。
 */

/** 一条消息的类型 */
enum class MessageKind {
    /** 普通方法调用 */
    CALL,

    /** 构造调用 `new X()` */
    CREATE,

    /** 返回箭头（可选，虚线） */
    RETURN,

    /** 不是消息，是居中备注（截断提示、无调用提示） */
    NOTE,

    /** 数据库层：Mapper 方法落到数据库表的 SQL 箭头（MyBatis / MyBatis-Plus） */
    SQL,
}

/**
 * 消息所处的分支类型。分析器在遍历方法体时把 if / else / 循环 / switch / 三元 / try 的
 * 条件原文挂到消息上，布局层据此画 `alt` / `loop` / `opt` 片段框，文本导出据此生成
 * `alt` / `loop` / `opt` 关键字。以前只画线性调用流，分支条件与互斥关系会整段丢失。
 */
enum class BranchKind(val keyword: String) {
    /** `if (cond)` 的 then 分支 */
    IF("alt"),
    /** `if` 的 else 分支（以及 else-if 的外层） */
    ELSE("else"),
    /** for / foreach / while / do-while */
    LOOP("loop"),
    /** `switch (expr)` 整体 */
    SWITCH("alt"),
    /** `case X:` / `default:` */
    CASE("alt"),
    /** 三元 `cond ? a : b` */
    TERNARY("opt"),
    /** 接口 / 抽象方法有多个实现：每个实现一条互斥分支，分别展开各自的方法体与 SQL */
    IMPL("alt"),
    /** try / catch / finally 块 */
    TRY("opt"),
    CATCH("opt"),
    FINALLY("opt"),
}

/** 时序图上的一个参与者（一条生命线） */
data class SeqParticipant(
    /** 全限定名，作为消息 from / to 的身份键；内部类用 `$`、匿名类带 `$1` */
    val qualified: String,
    /** 图上显示的短名（类名，不带包） */
    val display: String,
    /** 接口 / 抽象类：表头用虚线边框区分 */
    val isInterface: Boolean = false,
    /** 数据库表：柱形配色 + PlantUML `database` 关键字；双击表头跳 Database 视图里的表 */
    val isDatabase: Boolean = false,
    /** 对应的 PSI 类 / DbTable（面板双击跳转声明用；纯逻辑构造时为 null） */
    val psiClass: Any? = null,
    /**
     * 未截断的完整名称：**文本导出**（PlantUML / Mermaid）用它，图形页签仍用 [display] 的短名，
     * 免得为了画得下就把导出文件里的类名 / 表名也砍掉。默认与 [display] 相同，手工构造的图不受影响。
     */
    val fullDisplay: String = display,
)

/** 时序图上的一条消息（一根箭头） */
class SeqMessage(
    val kind: MessageKind,
    /** 发起方参与者（[qualified]）；NOTE 消息与 to 相同 */
    val from: String,
    /** 接收方参与者 */
    val to: String,
    /** 箭头标签：`method(arg, …)` / `new X()` / `return` */
    val label: String,
    /** 调用深度：根方法是 0，一次调用 +1 */
    val depth: Int = 0,
    /** 虚线（智能接口的实现调用、返回箭头） */
    val dashed: Boolean = false,
    /** 递归 / 回边：同一条调用链上再次到达，不再展开 */
    val recursive: Boolean = false,
    /** 解析到的方法（面板双击跳转声明用；NOTE / 手工构造时为 null） */
    val psiMethod: Any? = null,
    /**
     * 未截断的完整标签。图形层为了排得下会把 [label] 缩成短串（`…`），但 PlantUML / Mermaid
     * 是可编辑、可进版本库的文本，必须保留方法参数 / SQL 正文原样——文本导出只读这个字段。
     * 放在最后一个参数并给默认值：既有调用点（含大量位置参数）签名与行为都不变。
     */
    val fullLabel: String = label,
    /** 所在分支类型；不在任何分支里为 null */
    val branchKind: BranchKind? = null,
    /** 分支条件原文（`if (x != null)` / `else` / `for (i : list)` …），嵌套用 ` / ` 连接 */
    val guard: String = "",
) {
    /**
     * 本次调用展开出的**最后一条**消息的下标（布局层画激活条：从本消息到它）。
     * 叶子调用等于自身下标；截断 / 没下钻时也回落到自身。
     */
    var endIndex: Int = -1

    /** 对标 SequencePlugin 的层次编号（1 / 1.1 / 1.1.2 …），由 [SequenceDiagram.seal] 统一算出 */
    var number: String = ""
}

/** 一段连续、处于同一分支路径的消息区间（布局画片段框、文本导出生成 alt / loop / opt） */
class SeqFragment(
    val kind: BranchKind,
    val guard: String,
    val startIndex: Int,
    val endIndex: Int,
)

/** 一张时序图：参与者按出现顺序排列，消息按时间顺序排列 */
class SequenceDiagram {
    /** 参与者按首次出现顺序（根参与者永远第一个） */
    val participants = ArrayList<SeqParticipant>()

    /** 消息按时间顺序 */
    val messages = ArrayList<SeqMessage>()

    /** 消息条数达到上限被截断时置位（图里加一条备注） */
    var truncated: Boolean = false

    fun participantOf(qualified: String): SeqParticipant? = participants.firstOrNull { it.qualified == qualified }

    fun addParticipant(p: SeqParticipant): SeqParticipant {
        participantOf(p.qualified)?.let { return it }
        participants.add(p)
        return p
    }

    fun addMessage(m: SeqMessage): Int {
        messages.add(m)
        return messages.lastIndex
    }

    /**
     * 补齐每条消息的 endIndex，并按调用树算出**层次编号**（1 / 1.1 / 1.1.2 …）。
     * 编号算法：一条消息若 `endIndex > 自身下标`，说明它展开了子调用，后续消息落在它的
     * 激活区间内，于是把它压成「父」；同一父下的消息共享一个自增计数器，编号即 `父编号.序号`。
     * 手工构造的图（测试 / 解析器）同样走这里，图形与导出不必各自算一遍。
     */
    fun seal() {
        messages.forEachIndexed { index, m -> if (m.endIndex < 0) m.endIndex = index }
        val counters = HashMap<Int, Int>()
        val parents = ArrayDeque<Int>()
        messages.forEachIndexed { index, m ->
            while (parents.isNotEmpty() && messages[parents.last()].endIndex < index) {
                parents.removeLast()
            }
            val parent = parents.lastOrNull() ?: -1
            val n = (counters[parent] ?: 0) + 1
            counters[parent] = n
            m.number = if (parent < 0) n.toString() else "${messages[parent].number}.$n"
            if (m.endIndex > index && m.kind != MessageKind.NOTE) {
                parents.addLast(index)
            }
        }
    }

    /**
     * 把连续、处于同一分支路径的消息合并成片段区间。RETURN / SQL / NOTE 这类辅助消息夹在
     * 中间时不打断分组（它们同样发生在那个分支里），否则一个片段会被切成好几块。
     */
    fun fragments(): List<SeqFragment> {
        val result = ArrayList<SeqFragment>()
        var i = 0
        while (i < messages.size) {
            val kind = messages[i].branchKind
            if (kind == null) {
                i++
                continue
            }
            val guard = messages[i].guard
            var j = i
            while (j + 1 < messages.size) {
                val next = messages[j + 1]
                val same = next.branchKind == kind && next.guard == guard
                val filler = next.branchKind == null &&
                    (next.kind == MessageKind.RETURN || next.kind == MessageKind.SQL ||
                        next.kind == MessageKind.NOTE)
                if (!same && !filler) break
                j++
            }
            result.add(SeqFragment(kind, guard, i, j))
            i = j + 1
        }
        return result
    }
}

/**
 * 生成选项。对话框里可改（深度 / 消息上限 / 过滤开关 / 返回箭头 / 数据库层），
 * 上一轮的选择用 `PropertiesComponent` 记住，下次打开自动带回。
 */
data class DiagramOptions(
    /** 调用链下钻深度上限（根方法为 0） */
    val maxDepth: Int = 5,
    /** 跳过 getter / setter / toString / 日志这类噪声调用 */
    val skipTrivial: Boolean = true,
    /**
     * 跳过基础类 / 工具类（JDK 集合与字符串、Kotlin 标准库、Spring / Apache Commons / Guava 等
     * `CollectionUtils` 这类工具）：连箭头和生命线都不生成，而不是只不下钻。
     */
    val skipBasicClasses: Boolean = true,
    /** 智能接口：接口方法只有唯一实现时自动展开到实现类（虚线箭头） */
    val smartInterface: Boolean = true,
    /** 只展开本工程代码：第三方 / JDK 的方法只画一根箭头，不再下钻 */
    val projectOnly: Boolean = true,
    /** 为非 void 调用补一根虚线返回箭头 */
    val showReturns: Boolean = false,
    /** MyBatis / MyBatis-Plus：Mapper 方法继续下钻到 SQL 与数据库表（可在对话框里关掉） */
    val traceDatabase: Boolean = true,
    /**
     * 消息条数上限：**0（默认）表示不限**，不再一生成就只画 300 条。只有用户显式设成正数
     * 才截断并补备注，防止大方法把图撑爆；对话框里可改、按工程记忆。
     */
    val maxMessages: Int = DEFAULT_MAX_MESSAGES,
    /**
     * 本次分析使用的基础类 / 工具类名单。由设置页（Settings → Tools → Batis Kit → 时序图）
     * 在每次生成 / 重算时注入，**不进 [toName] 的工程记忆串**：名单是 IDE 全局偏好，
     * 与深度 / 开关这些按工程记忆的对话框选项分开管理。
     */
    val basicClassRules: BasicClassRules = BasicClassRules.DEFAULT,
) {
    companion object {
        /** 默认取消消息上限 */
        const val DEFAULT_MAX_MESSAGES = 0
        /** 0 / 负数都表示「不限消息数」 */
        const val NO_MAX_MESSAGES = 0
        const val MIN_MAX_MESSAGES = 10
        const val MAX_MAX_MESSAGES = 10000

        fun fromName(name: String?): DiagramOptions {
            // name = "depth,skip,smart,project,returns,database,maxMessages,basic"（PropertiesComponent 记忆格式）
            // 第 6 段（数据库层）、第 7 段（消息上限）、第 8 段（基础类过滤）都是后加的：
            // 旧配置缺段时分别默认开启 / 不限消息数 / 开启
            if (name.isNullOrBlank()) return DiagramOptions()
            return runCatching {
                val p = name.split(',')
                DiagramOptions(
                    maxDepth = p[0].toIntOrNull()?.coerceIn(1, 10) ?: 5,
                    skipTrivial = p.getOrNull(1)?.toBooleanStrictOrNull() ?: true,
                    skipBasicClasses = p.getOrNull(7)?.toBooleanStrictOrNull() ?: true,
                    smartInterface = p.getOrNull(2)?.toBooleanStrictOrNull() ?: true,
                    projectOnly = p.getOrNull(3)?.toBooleanStrictOrNull() ?: true,
                    showReturns = p.getOrNull(4)?.toBooleanStrictOrNull() ?: false,
                    traceDatabase = p.getOrNull(5)?.toBooleanStrictOrNull() ?: true,
                    maxMessages = p.getOrNull(6)?.toIntOrNull()?.let { raw ->
                        if (raw <= NO_MAX_MESSAGES) NO_MAX_MESSAGES
                        // 只夹上限、不夹下限：spinner 的 10 是 UI 范围，记忆串里更小的
                        // 历史值（例如测试 / 旧配置里的 2）要原样往返，不能被悄悄改成 10
                        else raw.coerceAtMost(MAX_MAX_MESSAGES)
                    } ?: DEFAULT_MAX_MESSAGES,
                )
            }.getOrDefault(DiagramOptions())
        }
    }

    /** 是否显式启用了消息上限（默认 false = 不限） */
    val messagesLimited: Boolean get() = maxMessages > NO_MAX_MESSAGES

    /** 当前消息数是否触及显式设置的上限；默认不限时恒为 false，分析器不再截断 */
    fun limitReached(size: Int): Boolean = messagesLimited && size >= maxMessages

    fun toName(): String = listOf(
        maxDepth, skipTrivial, smartInterface, projectOnly, showReturns, traceDatabase, maxMessages,
        skipBasicClasses,
    ).joinToString(",")
}
