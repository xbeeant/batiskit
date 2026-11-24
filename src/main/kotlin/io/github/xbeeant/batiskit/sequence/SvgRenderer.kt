package io.github.xbeeant.batiskit.sequence

/**
 * 布局 → SVG 文本。不引任何第三方库：导出的 SVG 用文本编辑器 / 浏览器 / PlantUML
 * 生态都能打开，图标尺寸与 Swing 面板共用同一份 [SequenceLayout] 坐标。
 */
object SvgRenderer {

    fun render(layout: SequenceLayout, diagram: SequenceDiagram): String {
        val sb = StringBuilder()
        sb.append(
            """<svg xmlns="http://www.w3.org/2000/svg" width="${fmt(layout.width + PAD * 2)}" """ +
                """height="${fmt(layout.height + PAD * 2)}" viewBox="${fmt(-PAD)} ${fmt(-PAD)} """ +
                """${fmt(layout.width + PAD * 2)} ${fmt(layout.height + PAD * 2)}">""",
        ).append('\n')
        // 背景
        sb.append("<rect x=\"0\" y=\"0\" width=\"100%\" height=\"100%\" fill=\"#FFFFFF\"/>\n")

        // 生命线（虚线）
        for (x in layout.lifelines) {
            sb.append(line(x, SequenceLayout.LIFELINE_TOP, x, layout.height - SequenceLayout.BOTTOM_PAD,
                stroke = C_LIFELINE, dash = "4 4")).append('\n')
        }

        // 分支片段框（alt / loop / opt）：虚线边框，不填充以免盖断生命线；标题留给后面统一画在最上层
        for (box in layout.fragmentBoxes) {
            sb.append(rect(box.x, box.y, box.w, box.h, fill = "none", stroke = C_FRAGMENT_BORDER,
                rx = 0.0, dash = "5 4")).append('\n')
        }

        // 激活条
        for (a in layout.activations) {
            val x = a.x - SequenceLayout.ACTIVATION_W / 2
            sb.append(rect(x, a.top, SequenceLayout.ACTIVATION_W, a.bottom - a.top,
                fill = C_ACTIVATION, stroke = C_ACTIVATION_BORDER)).append('\n')
        }

        // 箭头 + 标签
        for (arrow in layout.arrows) {
            val color = if (arrow.recursive) C_RECURSIVE else if (arrow.sql) C_SQL else C_ARROW
            if (arrow.self) {
                val x = arrow.x1
                val y = arrow.y
                val w = arrow.x2 - arrow.x1
                // 自调用回环：右出 → 下折 → 回头
                sb.append(path("M $x $y H ${fmt(x + w)} V ${fmt(y + 12)} H $x", color, arrow.dashed)).append('\n')
                sb.append(polygon("$x,${fmt(y + 12)} ${fmt(x + 7)},${fmt(y + 8)} ${fmt(x + 7)},${fmt(y + 16)}", color))
                    .append('\n')
                sb.append(labelBackground(arrow)).append('\n')
                sb.append(text(arrow.labelX, arrow.labelY + SequenceLayout.LABEL_BASELINE, arrow.displayText,
                    color = color, anchor = "middle",
                    title = arrow.fullText.takeIf { it != arrow.displayText })).append('\n')
            } else {
                val (head, tipX) = arrowHead(arrow.x1, arrow.y, arrow.x2)
                sb.append(line(arrow.x1, arrow.y, tipX, arrow.y, stroke = color, dash = if (arrow.dashed) "6 4" else null))
                    .append('\n')
                sb.append(polygon(head, color)).append('\n')
                sb.append(labelBackground(arrow)).append('\n')
                sb.append(text(arrow.labelX, arrow.labelY + SequenceLayout.LABEL_BASELINE, arrow.displayText,
                    color = color, anchor = "middle",
                    title = arrow.fullText.takeIf { it != arrow.displayText })).append('\n')
            }
        }

        // 片段标题画在箭头 / 激活条之上（背景不透明），y 在框上边界之外，与消息标签错开一行。
        // 背景框按标题实际文字宽度贴合，尽量少遮住后面的生命线。
        for (box in layout.fragmentBoxes) {
            val titleW = minOf(box.titleW,
                box.title.sumOf { SequenceLayout.charWidth(it) } + 6.0)
            sb.append(rect(box.x + 3, box.titleY - SequenceLayout.LABEL_BASELINE, titleW,
                SequenceLayout.LABEL_H, fill = C_LABEL_BG, stroke = "none", rx = 0.0)).append('\n')
            sb.append(text(box.x + 6, box.titleY, box.title, color = C_FRAGMENT_TEXT, anchor = "start"))
                .append('\n')
        }

        // 备注
        for (note in layout.notes) {
            sb.append(rect(note.x, note.y, note.w, note.h, fill = "#FFF8DD", stroke = "#D9B44A", rx = 4.0)).append('\n')
            sb.append(text(note.x + note.w / 2, note.y + note.h / 2 + 4, note.text, color = "#7A5B00", anchor = "middle"))
                .append('\n')
        }

        // 表头：不铺底色块，只给类名上色（普通 / 接口 / 数据库表各一色）+ 一条淡分隔线。
        // 生命线从 LIFELINE_TOP 起、已在表头下方，文字不会被虚线穿过。
        sb.append(line(0.0, SequenceLayout.HEADER_H - 2, layout.width, SequenceLayout.HEADER_H - 2,
            stroke = C_HEADER_UNDERLINE)).append('\n')
        for (h in layout.headers) {
            val color = if (h.database) C_HEADER_DB_TEXT else if (h.dashed) C_HEADER_LIB_TEXT else C_HEADER_TEXT
            sb.append(text(h.x + h.w / 2, h.y + h.h / 2 + 4, h.text, color = color, anchor = "middle", bold = true))
                .append('\n')
        }

        sb.append("</svg>\n")
        return sb.toString()
    }

    /** 箭头三角（指向 x2）：返回三角形顶点串与线段应收在哪个 x */
    private fun arrowHead(x1: Double, y: Double, x2: Double): Pair<String, Double> {
        val toRight = x2 > x1
        val size = 9.0
        val tip = x2
        val base = tip + (if (toRight) -size else size)
        val head = "$tip,$y ${fmt(base)},${fmt(y - 4.5)} ${fmt(base)},${fmt(y + 4.5)}"
        return head to base
    }

    private fun line(x1: Double, y1: Double, x2: Double, y2: Double, stroke: String, dash: String? = null,
                     width: Double = 1.4): String = buildString {
        append("<line x1=\"").append(fmt(x1)).append("\" y1=\"").append(fmt(y1))
        append("\" x2=\"").append(fmt(x2)).append("\" y2=\"").append(fmt(y2))
        append("\" stroke=\"").append(stroke).append("\" stroke-width=\"").append(fmt(width)).append('"')
        if (dash != null) append(" stroke-dasharray=\"").append(dash).append('"')
        append("/>")
    }

    private fun rect(x: Double, y: Double, w: Double, h: Double, fill: String, stroke: String,
                     rx: Double = 2.0, dash: String? = null): String = buildString {
        append("<rect x=\"").append(fmt(x)).append("\" y=\"").append(fmt(y))
        append("\" width=\"").append(fmt(w)).append("\" height=\"").append(fmt(h))
        append("\" fill=\"").append(fill).append("\" stroke=\"").append(stroke).append('"')
        if (rx > 0) append(" rx=\"").append(fmt(rx)).append('"')
        if (dash != null) append(" stroke-dasharray=\"").append(dash).append('"')
        append("/>")
    }

    private fun path(d: String, stroke: String, dashed: Boolean): String = buildString {
        append("<path d=\"").append(d).append("\" fill=\"none\" stroke=\"").append(stroke)
        append("\" stroke-width=\"1.4\"")
        if (dashed) append(" stroke-dasharray=\"6 4\"")
        append("/>")
    }

    private fun polygon(points: String, fill: String): String =
        "<polygon points=\"$points\" fill=\"$fill\"/>"

    private fun text(x: Double, y: Double, value: String, color: String, anchor: String,
                     bold: Boolean = false, title: String? = null): String = buildString {
        append("<text x=\"").append(fmt(x)).append("\" y=\"").append(fmt(y))
        append("\" font-family=\"sans-serif\" font-size=\"11")
        if (bold) append("\" font-weight=\"bold")
        append("\" fill=\"").append(color).append("\" text-anchor=\"").append(anchor)
        append("\" xml:space=\"preserve\">")
        // 图形上显示的是按像素裁剪后的短标签，完整原文放进 <title>：悬停可见，导出不丢信息
        if (title != null) append("<title>").append(escape(title)).append("</title>")
        append(escape(value)).append("</text>")
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** 标签的不透明底色：宽度贴合实际显示文字，尽量少遮住后面的生命线 / 激活条 */
    private fun labelBackground(arrow: SequenceLayout.Arrow): String {
        val w = minOf(arrow.labelW,
            arrow.displayText.sumOf { SequenceLayout.charWidth(it) } + 6.0)
        return rect(arrow.labelX - w / 2, arrow.labelY, w,
            SequenceLayout.LABEL_H, fill = C_LABEL_BG, stroke = "none", rx = 0.0)
    }

    private fun fmt(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)

    private const val PAD = 26.0

    // 与 SequenceDiagramPanel 的亮色主题一套配色（SVG 导出固定亮底）
    private const val C_LIFELINE = "#B8B8B8"
    private const val C_ARROW = "#5A4A32"
    private const val C_RECURSIVE = "#C25E00"
    private const val C_SQL = "#0F6E86"
    // 表头不再画色块，用彩色文字区分参与者类型
    private const val C_HEADER_TEXT = "#2B5C8A"
    private const val C_HEADER_LIB_TEXT = "#7A7A7A"
    private const val C_HEADER_DB_TEXT = "#0F6E86"
    private const val C_HEADER_UNDERLINE = "#E2E2E2"
    private const val C_ACTIVATION = "#FFD79A"
    private const val C_ACTIVATION_BORDER = "#E08A00"
    private const val C_FRAGMENT_BORDER = "#C9A227"
    private const val C_FRAGMENT_TEXT = "#8A6D1F"
    private const val C_LABEL_BG = "#FFFFFF"
}
