package io.github.xbeeant.batiskit.generate

import io.github.xbeeant.batiskit.config.GenerationConfig
import io.github.xbeeant.batiskit.template.TemplateSpec

/**
 * 生成计划：把「渲染产物」和「写盘」解耦后的中间结果。
 *
 * 以前点确定就直接写文件，用户看不到哪些文件会被合并、哪些同签名方法会被覆盖。
 * 现在 [CodeGenerator.plan] 在后台线程做纯渲染 + 只读地取回现有文件内容，
 * 用户在 [io.github.xbeeant.batiskit.ui.GenerationPreviewDialog] 里逐项确认，
 * 最后 [CodeGenerator.apply] 在 EDT 的一个写命令里落盘（整批可撤销）。
 */

/** 一份产物相对当前磁盘的变更类型 */
enum class ChangeKind(val title: String) {
    CREATE("新建"),
    MERGE("合并"),
    OVERWRITE("覆盖"),
    UNCHANGED("无变化");

    val writable: Boolean get() = this != UNCHANGED
}

class PlannedFile(
    val spec: TemplateSpec,
    val root: String,
    val relativePath: String,
    val proposedText: String,
    val merge: String,
    val existingText: String?,
    val kind: ChangeKind,
    /** 产物归属的表；漂移比对按表分组时用 */
    val tableName: String = "",
    /** 预览里用户是否勾选了这一项 */
    var selected: Boolean = kind.writable
) {
    val fileName: String = relativePath.substringAfterLast('/')

    /** 「方案:模板 id」——预览里按模板整组排除时用 */
    val templateKey: String = "${spec.set}:${spec.id}"

    val templateLabel: String get() = spec.name.ifBlank { spec.id }

    /** 表格里的状态列：模板被整组排除时明确标出来，避免用户困惑为什么没生效 */
    fun status(disabledTemplates: Set<String>): String = when {
        templateKey in disabledTemplates -> "已排除"
        !kind.writable -> kind.title
        !selected -> "已跳过"
        else -> kind.title
    }
}

class GenerationPlan(
    val setId: String,
    val config: GenerationConfig,
    /** 本方案里的全部模板（含被排除的），预览左侧按它列出勾选框 */
    val templates: List<TemplateSpec>,
    val files: MutableList<PlannedFile> = mutableListOf(),
    val errors: MutableList<String> = mutableListOf(),
    val warnings: MutableList<String> = mutableListOf(),
    disabledTemplates: Collection<String> = emptyList()
) {
    /** 被整组排除的模板 key；勾选结果会回写进 [GenerationConfig.disabledTemplates] */
    val disabledTemplates: MutableSet<String> = LinkedHashSet(disabledTemplates)

    /** 真正要落盘的文件：勾选了、模板没被排除、且确实有变化 */
    fun selected(): List<PlannedFile> = files.filter {
        it.selected && it.kind.writable && it.templateKey !in disabledTemplates
    }

    fun count(kind: ChangeKind): Int =
        files.count { it.kind == kind && it.templateKey !in disabledTemplates }

    val hasChanges: Boolean get() = selected().isNotEmpty()
}

/**
 * 极简的 unified diff：预览里只需要让人看清「多了什么、少了什么」，
 * 不需要 IntelliJ 的完整 diff 引擎（也不想在对话框里嵌一个编辑器）。
 */
object TextDiff {

    private const val CONTEXT = 2
    private const val MAX_CELLS = 2_000_000

    fun unified(old: String?, new: String, maxLines: Int = 240): String {
        val b = new.lines()
        if (old == null) {
            return buildString {
                append("（文件不存在，以下为完整新内容）\n")
                b.take(maxLines).forEach { append("+ ").append(it).append('\n') }
                if (b.size > maxLines) append("… 其余 ").append(b.size - maxLines).append(" 行省略\n")
            }
        }
        val a = old.lines()
        if (a.size.toLong() * b.size > MAX_CELLS) {
            return "文件较大，跳过逐行比对（${a.size} 行 → ${b.size} 行）"
        }

        // LCS 动态规划：模板产物一般几百行，规模可控
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) {
            for (j in b.size - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val ops = ArrayList<Pair<Char, String>>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { ops += ' ' to a[i]; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { ops += '-' to a[i]; i++ }
                else -> { ops += '+' to b[j]; j++ }
            }
        }
        while (i < a.size) { ops += '-' to a[i]; i++ }
        while (j < b.size) { ops += '+' to b[j]; j++ }

        // 只保留变化行附近的上下文，避免整份文件刷屏
        val changed = ops.indices.filter { ops[it].first != ' ' }.toSet()
        if (changed.isEmpty()) return "（内容一致）"
        val keep = BooleanArray(ops.size)
        changed.forEach { index ->
            for (k in maxOf(0, index - CONTEXT)..minOf(ops.lastIndex, index + CONTEXT)) keep[k] = true
        }
        return buildString {
            var lastKept = -1
            var emitted = 0
            for (index in ops.indices) {
                if (!keep[index]) continue
                if (lastKept >= 0 && index > lastKept + 1) append("  …\n")
                val (sign, line) = ops[index]
                append(sign).append(' ').append(line).append('\n')
                lastKept = index
                emitted++
                if (emitted >= maxLines) {
                    append("… 其余变化省略\n")
                    break
                }
            }
        }
    }
}
