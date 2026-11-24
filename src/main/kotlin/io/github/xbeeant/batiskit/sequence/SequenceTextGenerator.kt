package io.github.xbeeant.batiskit.sequence

/**
 * 时序图 → 文本格式的交换语言：PlantUML 与 Mermaid。
 *
 * 文本导出的价值在「可编辑 + 可进版本库」：贴进 README / wiki / `*.puml` / `*.mmd`，
 * 后续用各自生态渲染成图。箭头语义与图形渲染一致（虚线 = 智能接口实现调用 / 返回，
 * `⟳` = 递归回边，create 前置声明运行中创建的参与者）。
 */
object SequenceTextGenerator {

    /** 参与者首次出现的消息下标（0 = 一开始就在，>0 = 运行中创建） */
    private fun firstAppearance(diagram: SequenceDiagram, p: SeqParticipant): Int =
        diagram.messages.indexOfFirst {
            it.kind != MessageKind.NOTE && (it.from == p.qualified || it.to == p.qualified)
        }

    private fun indexQualified(diagram: SequenceDiagram, qualified: String): Int =
        diagram.participants.indexOfFirst { it.qualified == qualified }

    // ---------- 分支片段（图形页签画框，文本导出生成 alt / loop / opt） ----------

    private class FragmentLines(
        val openAt: Map<Int, List<String>>,
        val closeAt: Map<Int, List<String>>,
    )

    /**
     * 把 [SequenceDiagram.fragments] 翻译成「第几条消息前开块 / 第几条消息后收块」。
     * `if` 与紧随其后的 `else` 配成一对：`alt [cond] … else … end`，其余各自 `… end`。
     * 图里没有分支时两张表都是空的，导出结果与从前逐字一致。
     */
    private fun fragmentLines(diagram: SequenceDiagram, indent: String, escape: (String) -> String): FragmentLines {
        val openAt = HashMap<Int, MutableList<String>>()
        val closeAt = HashMap<Int, MutableList<String>>()
        val fragments = diagram.fragments()
        fragments.forEachIndexed { i, f ->
            val pairedElse = i + 1 < fragments.size &&
                fragments[i + 1].kind == BranchKind.ELSE && fragments[i + 1].startIndex == f.endIndex + 1
            val followsIf = i > 0 &&
                fragments[i - 1].kind == BranchKind.IF && fragments[i - 1].endIndex + 1 == f.startIndex
            // 连续的 IMPL 片段是同一接口的多个互斥实现：第一个 alt，其余 else，最后统一 end
            val followsImpl = i > 0 && fragments[i - 1].kind == BranchKind.IMPL &&
                fragments[i - 1].endIndex + 1 == f.startIndex
            val nextImpl = i + 1 < fragments.size && fragments[i + 1].kind == BranchKind.IMPL &&
                fragments[i + 1].startIndex == f.endIndex + 1
            val guard = escape(f.guard)
            val open = when (f.kind) {
                BranchKind.ELSE -> if (followsIf) "else" else "alt [else]"
                BranchKind.IMPL -> if (followsImpl) "else [$guard]" else "alt [$guard]"
                BranchKind.LOOP -> "loop [$guard]"
                BranchKind.TERNARY -> "opt [? $guard]"
                BranchKind.TRY, BranchKind.CATCH, BranchKind.FINALLY -> "opt [$guard]"
                else -> "alt [$guard]"
            }
            openAt.getOrPut(f.startIndex) { mutableListOf() }.add(indent + open)
            // if 后面紧跟 else、或后面还有同一接口的另一个实现：本块不收口，交给后续块统一 end
            if (!(f.kind == BranchKind.IF && pairedElse) && !nextImpl) {
                closeAt.getOrPut(f.endIndex) { mutableListOf() }.add(indent + "end")
            }
        }
        return FragmentLines(openAt, closeAt)
    }

    // ---------- PlantUML ----------

    fun plantUml(diagram: SequenceDiagram): String {
        diagram.seal()
        val sb = StringBuilder()
        sb.append("@startuml\n")
        sb.append("autonumber\n")
        diagram.participants.forEachIndexed { index, p ->
            // 数据库表用 PlantUML 的 database 造型（柱形），和类参与者一眼区分
            val keyword = if (p.isDatabase) "database" else "participant"
            // 文本导出用完整名称：图形页签为了排得下会缩写，导出文件不该跟着丢字
            sb.append(keyword).append(" \"").append(escapeLabel(p.fullDisplay)).append("\" as P").append(index).append('\n')
        }

        // 激活计划：需要 activate / deactivate 的 CALL（区间内才画激活条）
        val activateAt = HashMap<Int, SeqMessage>()   // 消息下标 → 该消息要 activate 接收方
        val deactivateAt = HashMap<Int, MutableList<SeqMessage>>()
        diagram.messages.forEachIndexed { index, m ->
            if (m.kind == MessageKind.CALL && m.endIndex > index) {
                activateAt[index] = m
                deactivateAt.getOrPut(m.endIndex) { mutableListOf() }.add(m)
            }
        }

        val firstSeen = diagram.participants.associateWith { firstAppearance(diagram, it) }
        val lines = fragmentLines(diagram, "") { escapeLabel(it) }
        diagram.messages.forEachIndexed { index, m ->
            // 运行中创建的参与者：首条消息前补 create 声明
            diagram.participants.forEachIndexed { pIndex, p ->
                // 数据库表是常驻参与者，不是运行中 create 出来的对象
                if (pIndex > 0 && firstSeen[p] == index && !p.isDatabase) {
                    sb.append("create P").append(pIndex).append('\n')
                }
            }
            lines.openAt[index]?.forEach { sb.append(it).append('\n') }
            val fromIndex = indexQualified(diagram, m.from)
            val toIndex = indexQualified(diagram, m.to)
            when (m.kind) {
                MessageKind.NOTE -> {
                    sb.append("note over ").append(alias(fromIndex)).append("\n  ").append(labelOf(m))
                        .append("\nend note\n")
                }
                MessageKind.CREATE -> {
                    sb.append(arrowLine(m, fromIndex, toIndex)).append(" : ").append(labelOf(m)).append('\n')
                }
                MessageKind.CALL -> {
                    sb.append(arrowLine(m, fromIndex, toIndex)).append(" : ")
                        .append(labelOf(m, if (m.recursive) " ⟳" else "")).append('\n')
                    if (activateAt.containsKey(index)) {
                        sb.append("activate ").append(alias(toIndex)).append('\n')
                    }
                }
                MessageKind.RETURN -> {
                    sb.append(arrowLine(m, fromIndex, toIndex)).append(" : return\n")
                }
                MessageKind.SQL -> {
                    sb.append(arrowLine(m, fromIndex, toIndex)).append(" : ")
                        .append(labelOf(m)).append('\n')
                }
            }
            deactivateAt[index]?.forEach { ended ->
                sb.append("deactivate ").append(alias(indexQualified(diagram, ended.to))).append('\n')
            }
            lines.closeAt[index]?.forEach { sb.append(it).append('\n') }
        }
        sb.append("@enduml\n")
        return sb.toString()
    }

    private fun arrowLine(m: SeqMessage, fromIndex: Int, toIndex: Int): String {
        val solid = if (m.dashed) "-->" else "->"
        return "P$fromIndex $solid P$toIndex"
    }

    private fun alias(index: Int): String = "P$index"

    private fun escape(value: String): String = value.replace("\"", "'")

    /** SQL / 参数标签可能带换行与引号，压成一行再转义；**不截断**，长字符串要原样进导出文件 */
    private fun escapeLabel(value: String): String = escape(value)
        .replace("\r", " ").replace("\n", " ")

    /** PlantUML 的消息文本：一律取 [SeqMessage.fullLabel]，图形用的缩写不参与导出 */
    private fun labelOf(m: SeqMessage, suffix: String = ""): String = escapeLabel(m.fullLabel + suffix)

    /**
     * Mermaid 把 `;` 当语句分隔符，长字符串里带分号会把一条消息切成两段。
     * 用 Mermaid 的实体码写回分号（`#59;`），渲染出来仍是原文。
     */
    private fun escapeMermaid(value: String): String = escapeLabel(value).replace(";", "#59;")

    private fun mermaidLabel(m: SeqMessage, suffix: String = ""): String =
        escapeMermaid(m.fullLabel + suffix)

    // ---------- Mermaid ----------

    fun mermaid(diagram: SequenceDiagram): String {
        diagram.seal()
        val sb = StringBuilder()
        sb.append("sequenceDiagram\n")
        sb.append("    autonumber\n")
        diagram.participants.forEachIndexed { index, p ->
            sb.append("    participant P").append(index).append(" as ")
                .append(escapeMermaid(p.fullDisplay)).append('\n')
        }
        val firstSeen = diagram.participants.associateWith { firstAppearance(diagram, it) }
        val deactivateAt = HashMap<Int, MutableList<SeqMessage>>()
        diagram.messages.forEachIndexed { index, m ->
            if (m.kind == MessageKind.CALL && m.endIndex > index) {
                deactivateAt.getOrPut(m.endIndex) { mutableListOf() }.add(m)
            }
        }
        val lines = fragmentLines(diagram, "    ") { escapeMermaid(it) }
        diagram.messages.forEachIndexed { index, m ->
            diagram.participants.forEachIndexed { pIndex, p ->
                if (pIndex > 0 && firstSeen[p] == index && !p.isDatabase) {
                    sb.append("    create participant P").append(pIndex).append('\n')
                }
            }
            lines.openAt[index]?.forEach { sb.append(it).append('\n') }
            val fromIndex = indexQualified(diagram, m.from)
            val toIndex = indexQualified(diagram, m.to)
            when (m.kind) {
                MessageKind.NOTE -> {
                    sb.append("    note over ").append(alias(fromIndex)).append(": ")
                        .append(escapeMermaid(m.fullLabel)).append('\n')
                }
                MessageKind.CREATE -> {
                    sb.append("    create participant ").append(alias(toIndex)).append('\n')
                    sb.append("    ").append(arrowLine(m, fromIndex, toIndex)).append(": ")
                        .append(mermaidLabel(m)).append('\n')
                }
                MessageKind.CALL -> {
                    sb.append("    ").append(arrowLine(m, fromIndex, toIndex)).append(": ")
                        .append(mermaidLabel(m, if (m.recursive) " ⟳" else "")).append('\n')
                    if (m.endIndex > index) {
                        sb.append("    activate ").append(alias(toIndex)).append('\n')
                    }
                }
                MessageKind.RETURN -> {
                    sb.append("    ").append(arrowLine(m, fromIndex, toIndex)).append(": return\n")
                }
                MessageKind.SQL -> {
                    sb.append("    ").append(arrowLine(m, fromIndex, toIndex)).append(": ")
                        .append(mermaidLabel(m)).append('\n')
                }
            }
            deactivateAt[index]?.forEach { ended ->
                sb.append("    deactivate ").append(alias(indexQualified(diagram, ended.to))).append('\n')
            }
            lines.closeAt[index]?.forEach { sb.append(it).append('\n') }
        }
        return sb.toString()
    }
}
