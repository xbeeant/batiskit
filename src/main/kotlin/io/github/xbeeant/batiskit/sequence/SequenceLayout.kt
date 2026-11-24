package io.github.xbeeant.batiskit.sequence

/**
 * 时序图的**布局引擎**：把 [SequenceDiagram] 摊成具体坐标（表头框 / 生命线 / 箭头 /
 * 激活条 / 备注），供两个渲染器共用——Swing 面板直接按坐标画，SVG 导出按同一份坐标
 * 输出文本。改样式只动渲染器，改排布只动这里。
 */
class SequenceLayout(val diagram: SequenceDiagram) {

    // ---------- 布局常量（Swing 与 SVG 共用同一套尺度，保证两处视觉一致） ----------
    // 按用户反馈加大间距：每条消息独占「标题带 + 标签带 + 箭头行」，文字与线条彻底分开，
    // 类名 / 方法名也有足够列宽，不再挤成一片省略号或横贯整张图压住生命线。
    companion object {
        const val CHAR_W = 6.0          // 11px sans-serif 的平均字符宽
        const val HEADER_H = 38.0       // 原 24：表头区加高，文字不再顶到生命线
        const val HEADER_PAD = 14.0     // 原 10：列名两侧留白
        const val ROW_H = 56.0          // 原 28：一条消息独占一行，标签 / 标题 / 箭头各有独立空间
        /** 生命线起点略低于表头底部，表头文字与虚线之间留出空隙 */
        const val LIFELINE_TOP = HEADER_H + 6.0
        /**
         * 表头与第一条消息之间预留两行：最上面的分支片段标题要画在框上边界之外，
         * 没有这段留白时它会被挤进框里、和第一条消息的标签叠在一起（用户反馈的「文字覆盖」）。
         */
        const val TOP_BAND = 52.0
        const val MESSAGE_TOP = HEADER_H + TOP_BAND
        const val BOTTOM_PAD = 30.0     // 原 16
        const val ACTIVATION_W = 12.0
        const val ACTIVATION_STEP = 5.0 // 嵌套激活条逐层右移
        const val SELF_LOOP_W = 40.0
        const val MAX_LABEL = 44         // 图上标签最多显示的字符数
        const val MIN_COL_W = 140.0      // 原 56：类名 / 方法名不再挤成 "ModuleRestControll…"
        /** 相邻参与者之间的额外留白：列名与箭头标签都不会顶到隔壁列 */
        const val COL_GAP = 18.0
        const val FRAGMENT_PAD = 16.0    // 分支片段框相对生命线的外扩
        /** 片段框上边界在标签框之上再抬高这么多，标题 / 标签 / 箭头三层互不重叠 */
        const val FRAGMENT_TOP_GAP = 8.0
        const val FRAGMENT_TITLE_MAX = 52
        /** 标签背景框高度 / 文字基线偏移量（文字基线 = 框顶 + LABEL_BASELINE） */
        const val LABEL_H = 16.0
        const val LABEL_BASELINE = 12.0
        /** 标签框底与箭头线之间的垂直空隙：标签完全画在线上方，不再压线 */
        const val LABEL_GAP = 14.0
        /** 箭头再短也至少给这么宽的标签空间，否则方法名全被裁成省略号 */
        const val LABEL_MIN_W = 110.0
        /** 单条标签最大宽度：箭头再长也不让文字横贯整图压住一路生命线，全文仍进 SVG `<title>` */
        const val LABEL_MAX_W = 280.0
        const val LABEL_PAD = 10.0
        /** 片段框标题的可用宽度：标题画在框上边界之外，与消息标签错开一行，可以比框宽 */
        const val FRAGMENT_TITLE_MAX_W = 240.0
        /** 标题文字基线在框顶之上的距离 */
        const val FRAGMENT_TITLE_OFFSET = 4.0

        /** CJK / 全角字符按双倍宽度估算，避免中文类名把列宽算小、文字溢出到相邻表头 */
        fun charWidth(ch: Char): Double = if (ch.code > 0x2E80) CHAR_W * 2 else CHAR_W

        fun estimateWidth(text: String): Double = text.sumOf { charWidth(it) } + HEADER_PAD

        /**
         * 按可用像素宽度裁剪文本。以前只按「44 个字符」截断，但列宽收紧后一列的宽度常常
         * 远小于 44×6px：标签居中画在箭头中点就会横向溢出，压住生命线、激活条和其它标签。
         * 图形页签与 SVG 共用这一份裁剪结果，两处不会再各算一套。
         */
        fun fitText(text: String, maxWidth: Double): String {
            if (maxWidth <= CHAR_W * 3) return ""
            var used = 0.0
            var cut = 0
            for (ch in text) {
                val cw = charWidth(ch)
                if (used + cw > maxWidth - CHAR_W) break // 留一个字符的位置给省略号
                used += cw
                cut++
            }
            return if (cut >= text.length) text else text.take(cut) + "…"
        }

        /** 片段框左上角标题：`alt [if (x != null)]` / `loop [for (…)]` / `else` … */
        fun fragmentTitle(kind: BranchKind, guard: String): String {
            val g = if (guard.length > FRAGMENT_TITLE_MAX) {
                guard.take(FRAGMENT_TITLE_MAX - 1) + "…"
            } else {
                guard
            }
            return when (kind) {
                BranchKind.ELSE -> "else"
                BranchKind.LOOP -> "loop [$g]"
                BranchKind.CASE -> "[$g]"
                BranchKind.TERNARY -> "opt ? [$g]"
                BranchKind.IF, BranchKind.SWITCH, BranchKind.IMPL -> "alt [$g]"
                BranchKind.TRY, BranchKind.CATCH, BranchKind.FINALLY -> "opt [$g]"
            }
        }
    }

    // ---------- 产物 ----------

    /** 表头框（参与者标题）；database = 数据库表，用柱形配色与 PlantUML database 关键字 */
    class Header(val index: Int, val x: Double, val y: Double, val w: Double, val h: Double,
                 val text: String, val dashed: Boolean, val database: Boolean = false)

    /** 一根箭头（self = 自调用小回环）；sql = Mapper → 数据库表的 SQL 调用 */
    class Arrow(val x1: Double, val y: Double, val x2: Double, val text: String,
                val dashed: Boolean, val recursive: Boolean, val self: Boolean, val messageIndex: Int,
                val sql: Boolean = false, val number: String = "",
                /** 编号前缀 + 按可用宽度裁剪后的最终显示文本（Swing / SVG 都用它） */
                val displayText: String = text,
                /** 标签背景框：中心 x、宽、顶 y（文字基线 = 顶 y + LABEL_BASELINE） */
                val labelX: Double = 0.0, val labelW: Double = 0.0, val labelY: Double = 0.0,
                /** 未裁剪的完整标签：SVG 写进 `<title>`，悬停可看全文，导出不因裁剪丢信息 */
                val fullText: String = text)

    /** 激活条（参与者生命线上的一段加粗区间） */
    class Activation(val index: Int, val x: Double, val top: Double, val bottom: Double, val level: Int)

    /** 分支片段框（alt / loop / opt）：把一段处于同一条件路径的消息框起来 */
    class FragmentBox(val x: Double, val y: Double, val w: Double, val h: Double,
                      val title: String, val kind: BranchKind,
                      /** 标题文字基线（画在框上边界之外，与消息标签错开一行） */
                      val titleY: Double = y + 12,
                      /** 标题背景框宽度 */
                      val titleW: Double = w - 12)

    /** 居中备注（截断提示等） */
    class Note(val x: Double, val y: Double, val w: Double, val h: Double, val text: String)

    val headers = ArrayList<Header>()
    val arrows = ArrayList<Arrow>()
    val activations = ArrayList<Activation>()
    val notes = ArrayList<Note>()
    val fragmentBoxes = ArrayList<FragmentBox>()

    /** 每条生命线的 x 坐标（参与者下标 → 中心 x） */
    val lifelines = ArrayList<Double>()

    val width: Double
    val height: Double

    /** 消息下标 → 箭头（面板双击导航用） */
    val arrowByMessage = HashMap<Int, Arrow>()

    /** 参与者下标 → 表头框 */
    val headerByIndex = HashMap<Int, Header>()

    /** 一次调用的激活区间：参与者下标、上下 y（箭头偏移要用它，所以先算） */
    private data class Span(val participant: Int, val top: Double, val bottom: Double)

    init {
        diagram.seal()
        val names = diagram.participants.map { it.display }
        val colWidths = names.map { maxOf(MIN_COL_W, estimateWidth(it)) }
        // 每列左右各留 COL_GAP / 2：参与者之间真正有空隙，长标签不会顶到隔壁列的表头
        var x = 0.0
        colWidths.forEach { w ->
            x += COL_GAP / 2
            lifelines.add(x + w / 2)
            x += w + COL_GAP / 2
        }
        width = x

        colWidths.forEachIndexed { index, w ->
            val header = Header(index, lifelines[index] - w / 2, 0.0, w, HEADER_H,
                fitText(names[index], w - HEADER_PAD),
                diagram.participants[index].isInterface, diagram.participants[index].isDatabase)
            headers.add(header)
            headerByIndex[index] = header
        }

        // 1. 激活条区间：CALL / CREATE 消息在接收方参与者上铺一条，从本消息到 endIndex
        val spans = ArrayList<Span>()
        diagram.messages.forEachIndexed { index, m ->
            if (m.kind == MessageKind.CALL || m.kind == MessageKind.CREATE) {
                val to = diagram.participants.indexOfFirst { it.qualified == m.to }
                if (to >= 0) {
                    val top = rowY(index) - 6
                    val bottom = maxOf(rowY(m.endIndex) + 8, top + ROW_H / 2)
                    spans.add(Span(to, top, bottom))
                }
            }
        }
        // 2. 激活条坐标：嵌套层级 = 其它激活条严格包住它的层数，逐层右移
        spans.forEach { span ->
            val level = spans.count { other ->
                other.participant == span.participant && other.top <= span.top && other.bottom >= span.bottom &&
                    (other.top < span.top || other.bottom > span.bottom)
            }
            activations.add(Activation(span.participant, lifelines[span.participant] + level * ACTIVATION_STEP,
                span.top, span.bottom, level))
        }

        // 3. 箭头：起止点贴在激活条外缘（没有激活条时贴生命线）
        val innerWidth = maxOf(width - HEADER_PAD * 2, 200.0)
        /**
         * 标签几何：编号前缀 + 按箭头跨度裁剪，宽度不超过可用跨度（短箭头给个下限），
         * 中心夹在画布内。渲染层据此画不透明背景框，文字就不会压住生命线 / 激活条。
         */
        fun labelGeom(number: String, short: String, center: Double, span: Double): Triple<String, Double, Double> {
            val numbered = if (number.isEmpty()) short else "$number $short"
            // 封顶：箭头跨度再大，标签也只占中间一段，不会横贯整张图压住一路生命线 / 激活条；
            // 被裁掉的原文仍在 fullText 里（SVG 写进 <title>），导出不丢信息。
            val maxW = minOf(
                minOf(span + LABEL_PAD * 2, LABEL_MAX_W).coerceAtLeast(LABEL_MIN_W),
                (width - 8.0).coerceAtLeast(LABEL_MIN_W),
            )
            val text = fitText(numbered, maxW)
            val textW = text.sumOf { charWidth(it) }
            val boxW = minOf(maxW, textW + 6.0)
            val minCx = boxW / 2 + 4.0
            val maxCx = (width - boxW / 2 - 4.0).coerceAtLeast(minCx)
            return Triple(text, center.coerceIn(minCx, maxCx), boxW)
        }
        diagram.messages.forEachIndexed { index, m ->
            val y = rowY(index)
            when (m.kind) {
                MessageKind.NOTE -> {
                    val short = abbreviate(m.label)
                    val w = minOf(estimateWidth(short), innerWidth)
                    notes.add(Note((width - w) / 2, y - ROW_H / 2 + 4, w, ROW_H - 8,
                        fitText(short, w - 8)))
                }
                else -> {
                    val from = diagram.participants.indexOfFirst { it.qualified == m.from }
                    val to = diagram.participants.indexOfFirst { it.qualified == m.to }
                    if (from >= 0 && to >= 0) {
                        // 标签框整体抬到箭头线上方，框底与箭头线之间留 LABEL_GAP，文字不再压线
                        val labelTop = y - LABEL_GAP - LABEL_H
                        val arrow = if (from == to) {
                            val x1 = lifelines[from] + ACTIVATION_W
                            val x2 = x1 + SELF_LOOP_W
                            val (dt, cx, bw) = labelGeom(m.number, abbreviate(m.label), (x1 + x2) / 2, SELF_LOOP_W)
                            Arrow(x1, y, x2, abbreviate(m.label), m.dashed, m.recursive, true, index,
                                sql = m.kind == MessageKind.SQL, number = m.number,
                                displayText = dt, labelX = cx, labelW = bw, labelY = labelTop,
                                fullText = m.fullLabel)
                        } else {
                            val toRight = to > from
                            val x1 = lifelines[from] + edgeOffset(from, y) * (if (toRight) 1 else -1)
                            val x2 = lifelines[to] - edgeOffset(to, y) * (if (toRight) 1 else -1)
                            val (dt, cx, bw) = labelGeom(m.number, abbreviate(m.label), (x1 + x2) / 2,
                                kotlin.math.abs(x2 - x1))
                            Arrow(x1, y, x2, abbreviate(m.label), m.dashed, m.recursive, false, index,
                                sql = m.kind == MessageKind.SQL, number = m.number,
                                displayText = dt, labelX = cx, labelW = bw, labelY = labelTop,
                                fullText = m.fullLabel)
                        }
                        arrows.add(arrow)
                        arrowByMessage[index] = arrow
                    }
                }
            }
        }

        // 4. 分支片段框：把连续处于同一条件路径的消息框起来（alt / loop / opt）
        //    以前 if / else / 循环里的调用只是被线性排进消息流，条件与互斥关系整段丢失
        //    同一接口的多个实现是连续的 IMPL 片段：第一个画 alt、其余画 else，与文本导出一致
        var prevImplEnd = Int.MIN_VALUE
        for (f in diagram.fragments()) {
            // 框顶抬到标签框之上：标题（框外上方）→ 标签（框内上方）→ 箭头，三层垂直错开；
            // 框底收在下一行框顶之前，相邻 / 嵌套片段框不互相压线
            val top = rowY(f.startIndex) - ROW_H / 2 - FRAGMENT_TOP_GAP
            val bottom = rowY(f.endIndex) + ROW_H / 2 - 12
            var minX = Double.POSITIVE_INFINITY
            var maxX = Double.NEGATIVE_INFINITY
            for (k in f.startIndex..f.endIndex) {
                val m = diagram.messages[k]
                if (m.kind == MessageKind.NOTE) continue
                val from = diagram.participants.indexOfFirst { it.qualified == m.from }
                val to = diagram.participants.indexOfFirst { it.qualified == m.to }
                if (from >= 0) {
                    minX = minOf(minX, lifelines[from])
                    maxX = maxOf(maxX, lifelines[from])
                }
                if (to >= 0) {
                    minX = minOf(minX, lifelines[to])
                    maxX = maxOf(maxX, lifelines[to])
                }
            }
            if (minX == Double.POSITIVE_INFINITY) {
                minX = 0.0
                maxX = width
            }
            val x = (minX - FRAGMENT_PAD).coerceAtLeast(0.0)
            val right = (maxX + FRAGMENT_PAD + SELF_LOOP_W / 2).coerceAtMost(width)
            val w = maxOf(right - x, 100.0).coerceAtMost(width - x)
            val baseTitle = fragmentTitle(f.kind, f.guard)
            val followsImpl = f.kind == BranchKind.IMPL && prevImplEnd + 1 == f.startIndex
            val title = fitText(
                if (followsImpl) baseTitle.replaceFirst("alt ", "else ") else baseTitle,
                FRAGMENT_TITLE_MAX_W,
            )
            val titleW = minOf(FRAGMENT_TITLE_MAX_W, title.sumOf { charWidth(it) } + 6.0)
            // 标题画在框上边界之外，与消息标签（rowY - 6）错开一行，不再叠字
            val titleY = maxOf(top - FRAGMENT_TITLE_OFFSET, HEADER_H + LABEL_BASELINE)
            fragmentBoxes.add(
                FragmentBox(x, top, w, maxOf(bottom - top, ROW_H * 0.6),
                    title, f.kind, titleY, titleW),
            )
            prevImplEnd = if (f.kind == BranchKind.IMPL) f.endIndex else Int.MIN_VALUE
        }

        height = MESSAGE_TOP + ROW_H * maxOf(diagram.messages.size, 1) + BOTTOM_PAD
    }

    /** 第 index 条消息的行中心 y */
    fun rowY(index: Int): Double = MESSAGE_TOP + ROW_H * index + ROW_H / 2

    /** 参与者在 y 处的生命线外缘偏移：无激活条 = 0，有 = 激活条半宽 + 嵌套位移 */
    private fun edgeOffset(participant: Int, y: Double): Double {
        val active = activations.filter { it.index == participant && it.top <= y && it.bottom >= y }
        if (active.isEmpty()) {
            return 0.0
        }
        return ACTIVATION_W / 2 + (active.maxOf { it.level }) * ACTIVATION_STEP
    }

    private fun abbreviate(text: String): String =
        if (text.length > MAX_LABEL) text.take(MAX_LABEL - 1) + "…" else text
}
