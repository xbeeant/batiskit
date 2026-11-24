package io.github.xbeeant.batiskit.sequence

import com.intellij.ui.JBColor
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.pom.Navigatable
import com.intellij.psi.PsiElement
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent

/**
 * 时序图画布：直接用 Graphics2D 按同一份 [SequenceLayout] 坐标绘制（不引 SVG 渲染库，
 * 包体小且颜色随 IDE 主题切换）。双击表头跳到参与者类声明（数据库表则跳到 Database 视图），
 * 双击箭头跳到被调方法 / Mapper XML 语句声明。
 */
class SequenceDiagramPanel(val diagram: SequenceDiagram, private val project: Project) : JComponent() {

    val layout = SequenceLayout(diagram)

    init {
        preferredSize = Dimension(
            (layout.width + PAD * 2).toInt().coerceAtLeast(100),
            (layout.height + PAD * 2).toInt().coerceAtLeast(100),
        )
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) {
                    return
                }
                val pad = PAD.toDouble()
                val x = e.x - pad
                val y = e.y - pad
                // 箭头优先（更小的目标）
                val arrow = hitArrow(x, y)
                if (arrow != null) {
                    val element = diagram.messages.getOrNull(arrow.messageIndex)?.psiMethod as? PsiElement
                    if (element != null) {
                        navigate(element)
                        return
                    }
                }
                val header = layout.headers.firstOrNull {
                    x >= it.x && x <= it.x + it.w && y >= it.y && y <= it.y + it.h
                }
                if (header != null) {
                    (diagram.participants.getOrNull(header.index)?.psiClass as? PsiElement)?.let { navigate(it) }
                }
            }
        })
    }

    /** 箭头命中：水平箭头按线段距离，自调用按小回环区域 */
    private fun hitArrow(x: Double, y: Double): SequenceLayout.Arrow? {
        for (arrow in layout.arrows) {
            if (arrow.self) {
                if (x >= arrow.x1 && x <= arrow.x2 + 8 && y >= arrow.y - 10 && y <= arrow.y + 16) {
                    return arrow
                }
            } else {
                val distance = kotlin.math.abs(y - arrow.y)
                if (distance <= 8 && x >= kotlin.math.min(arrow.x1, arrow.x2) - 4 &&
                    x <= kotlin.math.max(arrow.x1, arrow.x2) + 4
                ) {
                    return arrow
                }
            }
        }
        return null
    }

    private fun navigate(element: PsiElement) {
        // Database 视图的 DbTable 没有 containingFile，但自己就是 Navigatable：
        // 先走它，否则会被下面的 virtualFile 判空直接挡掉（数据库表表头就跳不过去了）
        (element as? Navigatable)?.takeIf { it.canNavigate() }?.let {
            it.navigate(true)
            return
        }
        val file = element.containingFile?.virtualFile ?: return
        OpenFileDescriptor(project, file, element.textOffset).navigate(true)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.translate(PAD, PAD)

        // 生命线
        g2.stroke = dashedStroke()
        g2.color = LIFELINE
        for (x in layout.lifelines) {
            g2.drawLine(x.toInt(), SequenceLayout.LIFELINE_TOP.toInt(),
                x.toInt(), (layout.height - SequenceLayout.BOTTOM_PAD).toInt())
        }

        // 分支片段框（alt / loop / opt）：只画虚线边框与左上角标题，不填充，
        // 免得把生命线盖断；标题写的是条件原文，if / else 的互斥关系一眼可见
        for (box in layout.fragmentBoxes) {
            g2.stroke = dashedStroke()
            g2.color = FRAGMENT_BORDER
            g2.drawRect(box.x.toInt(), box.y.toInt(), box.w.toInt(), box.h.toInt())
            g2.stroke = plainStroke()
        }

        // 激活条
        g2.stroke = plainStroke()
        for (a in layout.activations) {
            g2.color = ACTIVATION
            g2.fillRect((a.x - SequenceLayout.ACTIVATION_W / 2).toInt(), a.top.toInt(),
                SequenceLayout.ACTIVATION_W.toInt(), (a.bottom - a.top).toInt())
            g2.color = ACTIVATION_BORDER
            g2.drawRect((a.x - SequenceLayout.ACTIVATION_W / 2).toInt(), a.top.toInt(),
                SequenceLayout.ACTIVATION_W.toInt(), (a.bottom - a.top).toInt())
        }

        // 箭头
        for (arrow in layout.arrows) {
            val color = if (arrow.recursive) RECURSIVE else if (arrow.sql) SQL_ARROW else ARROW
            g2.color = color
            g2.stroke = if (arrow.dashed) dashedStroke() else plainStroke()
            val labelBaseline = arrow.labelY + SequenceLayout.LABEL_BASELINE
            if (arrow.self) {
                val w = arrow.x2 - arrow.x1
                g2.drawLine(arrow.x1.toInt(), arrow.y.toInt(), (arrow.x1 + w).toInt(), arrow.y.toInt())
                g2.drawLine((arrow.x1 + w).toInt(), arrow.y.toInt(), (arrow.x1 + w).toInt(), (arrow.y + 12).toInt())
                g2.drawLine((arrow.x1 + w).toInt(), (arrow.y + 12).toInt(), arrow.x1.toInt(), (arrow.y + 12).toInt())
                drawArrowHead(g2, arrow.x1 + 7.0, arrow.y + 12.0, false, color)
                drawLabel(g2, arrow.labelX, labelBaseline, arrow.displayText, color, true,
                    LABEL_BG, arrow.labelW - 6)
            } else {
                val toRight = arrow.x2 > arrow.x1
                val tipBase = arrow.x2 + (if (toRight) -HEAD else HEAD)
                g2.drawLine(arrow.x1.toInt(), arrow.y.toInt(), tipBase.toInt(), arrow.y.toInt())
                drawArrowHead(g2, arrow.x2, arrow.y, toRight, color)
                drawLabel(g2, arrow.labelX, labelBaseline, arrow.displayText, color, true,
                    LABEL_BG, arrow.labelW - 6)
            }
        }

        // 片段标题画在箭头 / 激活条之上（背景不透明），否则会被激活条盖回去；
        // 标题 y 在框上边界之外，与消息标签错开一行，不再叠字
        for (box in layout.fragmentBoxes) {
            drawLabel(g2, box.x + 6, box.titleY, box.title, FRAGMENT_TEXT, false,
                LABEL_BG, box.titleW - 6)
        }

        // 备注
        for (note in layout.notes) {
            g2.color = NOTE
            g2.fillRoundRect(note.x.toInt(), note.y.toInt(), note.w.toInt(), note.h.toInt(), 8, 8)
            g2.color = NOTE_BORDER
            g2.drawRoundRect(note.x.toInt(), note.y.toInt(), note.w.toInt(), note.h.toInt(), 8, 8)
            drawLabel(g2, note.x + note.w / 2, note.y + note.h / 2 + 4, note.text, NOTE_TEXT, true,
                maxWidth = note.w - 8)
        }

        // 表头：不铺底色块，只给类名上色（普通 / 接口 / 数据库表各一色），另画一条淡分隔线
        // 划开表头区与图区。生命线从 LIFELINE_TOP 起、已在表头下方，不会穿过文字。
        g2.stroke = plainStroke()
        g2.color = HEADER_UNDERLINE
        val underlineY = (SequenceLayout.HEADER_H - 2).toInt()
        g2.drawLine(0, underlineY, layout.width.toInt(), underlineY)
        for (h in layout.headers) {
            val color = if (h.database) HEADER_DB_TEXT else if (h.dashed) HEADER_LIB_TEXT else HEADER_TEXT
            drawLabel(g2, h.x + h.w / 2, h.y + h.h / 2 + 4, h.text, color, true,
                maxWidth = h.w - SequenceLayout.HEADER_PAD)
        }
    }

    private fun drawArrowHead(g2: Graphics2D, tipX: Double, y: Double, toRight: Boolean, color: java.awt.Color) {
        val dir = if (toRight) -1.0 else 1.0
        val xs = doubleArrayOf(tipX, tipX + dir * HEAD, tipX + dir * HEAD)
        val ys = doubleArrayOf(y, y - HEAD / 2, y + HEAD / 2)
        g2.stroke = plainStroke()
        g2.color = color
        g2.fillPolygon(xs.map { it.toInt() }.toIntArray(), ys.map { it.toInt() }.toIntArray(), 3)
    }

    /**
     * 画一段文字。[background] 非空时先填一个不透明底色框：箭头标签 / 片段标题以前直接画在
     * 生命线、激活条和相邻标签上，字号一大就糊成一片；有底色后即便压到线条也是「盖住」而不是「混在一起」。
     * [maxWidth] 用真实字体度量再裁一次，兜住布局层按 CHAR_W 估算的误差。
     */
    private fun drawLabel(g2: Graphics2D, x: Double, y: Double, text: String, color: java.awt.Color,
                          center: Boolean, background: java.awt.Color? = null, maxWidth: Double = 0.0) {
        val metrics = g2.fontMetrics
        val shown = if (maxWidth > 0) fitByMetrics(metrics, text, maxWidth) else text
        val width = metrics.stringWidth(shown)
        val px = (if (center) x - width / 2 else x).toInt()
        if (background != null) {
            g2.color = background
            g2.fillRect(px - 3, y.toInt() - SequenceLayout.LABEL_BASELINE.toInt(), width + 6,
                SequenceLayout.LABEL_H.toInt())
        }
        g2.color = color
        g2.drawString(shown, px, y.toInt())
    }

    private fun fitByMetrics(metrics: java.awt.FontMetrics, text: String, maxWidth: Double): String {
        if (metrics.stringWidth(text) <= maxWidth) return text
        var cut = text.length
        while (cut > 0 && metrics.stringWidth(text.take(cut) + "…") > maxWidth) cut--
        return if (cut <= 0) "" else text.take(cut) + "…"
    }

    private fun plainStroke() = BasicStroke(1.4f)

    private fun dashedStroke() = BasicStroke(1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f,
        floatArrayOf(5f, 4f), 0f)

    // ---------- 主题色（亮 / 暗各一套） ----------

    private companion object {
        const val PAD = 26
        const val HEAD = 9.0
        // 表头不再用米黄底色块：靠文字颜色区分参与者类型，配色仍随 IDE 亮暗主题切换。
        val LIFELINE = JBColor.namedColor("SequenceDiagram.lifeline", JBColor(0xB8B8B8, 0x555555))
        val ARROW = JBColor.namedColor("SequenceDiagram.arrow", JBColor(0x5A4A32, 0xD8C8A8))
        val RECURSIVE = JBColor.namedColor("SequenceDiagram.recursive", JBColor(0xC25E00, 0xE8A33D))
        val SQL_ARROW = JBColor.namedColor("SequenceDiagram.sqlArrow", JBColor(0x0F6E86, 0x64C7E8))
        val HEADER_TEXT = JBColor.namedColor("SequenceDiagram.headerText", JBColor(0x2B5C8A, 0x8EC5FF))
        val HEADER_LIB_TEXT = JBColor.namedColor("SequenceDiagram.headerLibText", JBColor(0x7A7A7A, 0x9A9A9A))
        val HEADER_DB_TEXT = JBColor.namedColor("SequenceDiagram.headerDatabaseText", JBColor(0x0F6E86, 0x64C7E8))
        val HEADER_UNDERLINE = JBColor.namedColor("SequenceDiagram.headerUnderline", JBColor(0xE2E2E2, 0x454545))
        val ACTIVATION = JBColor.namedColor("SequenceDiagram.activation", JBColor(0xFFD79A, 0x6B4E12))
        val ACTIVATION_BORDER = JBColor.namedColor("SequenceDiagram.activationBorder", JBColor(0xE08A00, 0xB37A20))
        val FRAGMENT_BORDER = JBColor.namedColor("SequenceDiagram.fragmentBorder", JBColor(0xC9A227, 0x8F7F3B))
        val FRAGMENT_TEXT = JBColor.namedColor("SequenceDiagram.fragmentText", JBColor(0x8A6D1F, 0xD9C27A))
        val NOTE = JBColor.namedColor("SequenceDiagram.note", JBColor(0xFFF8DD, 0x4A4229))
        val NOTE_BORDER = JBColor.namedColor("SequenceDiagram.noteBorder", JBColor(0xD9B44A, 0x8F7F3B))
        val NOTE_TEXT = JBColor.namedColor("SequenceDiagram.noteText", JBColor(0x7A5B00, 0xD9C27A))
        /** 标签 / 片段标题的不透明底色，随 IDE 亮暗主题切换，避免文字与线条混在一起 */
        val LABEL_BG = JBColor.namedColor("SequenceDiagram.labelBackground", JBColor(0xFFFFFF, 0x2B2B2B))
    }
}
