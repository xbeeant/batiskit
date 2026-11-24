package io.github.xbeeant.batiskit.ui

import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import java.awt.FontMetrics
import javax.swing.BorderFactory
import javax.swing.border.Border

/**
 * 界面设计系统的唯一颜色 / 字阶 / 间距来源（见 .workbuddy/artifacts/batiskit-ui-design-system.md）。
 *
 * 规则：
 *  - 组件里禁止再写 `JBColor(light, dark)` 字面量，一律读这里；
 *  - 平台有对应语义色时优先用 [listBackground] / [selectionBackground] 等跟随 IDE 主题，
 *    精确 hex 只在自定义绘制（徽标、chip、状态点、控件边界）时使用；
 *  - 所有尺寸走 [scale]，不硬编码像素。
 */
object UiTokens {

    // ---------------------------------------------------------------- 表面 / 层级
    val surfaceBase = JBColor(0xFBFCFE, 0x1C2027)
    val surfaceRaised = JBColor(0xF4F7FB, 0x242A33)
    val surfaceSunken = JBColor(0xEEF2F8, 0x171B21)
    val surfaceHover = JBColor(0xEDF2FA, 0x2A313B)
    val surfaceSelected = JBColor(0xE4EDFC, 0x2B3A57)

    val borderSubtle = JBColor(0xE3E8F0, 0x303844)
    val border = JBColor(0xD2DAE6, 0x3A4350)

    /** 输入框 / 按钮 / 下拉的可见边界：Light 3.37:1、Dark 3.90:1，满足 WCAG 1.4.11 */
    val borderControl = JBColor(0x7C8BA1, 0x6E7D92)

    // ---------------------------------------------------------------- 文本 / 强调
    val textPrimary = JBColor(0x1B2532, 0xE2E8F1)
    val textSecondary = JBColor(0x566274, 0xA6B1C1)
    val textMuted = JBColor(0x647086, 0x909CB0)
    val textDisabled = JBColor(0x97A1B0, 0x6B7688)
    val accent = JBColor(0x2C63CE, 0x7DA9FF)
    val accentHover = JBColor(0x2352B0, 0x96BBFF)
    val accentSubtle = JBColor(0xE7EEFC, 0x22304D)
    val textOnAccent = JBColor(0xFFFFFF, 0x10151E)

    // ---------------------------------------------------------------- 语义色
    val success = JBColor(0x167A45, 0x6FCF8B)
    val warning = JBColor(0x9A6200, 0xE3B341)
    val error = JBColor(0xC23B33, 0xF08178)
    val info = JBColor(0x2C63CE, 0x7DA9FF)

    val successBg = JBColor(0xE4F5EB, 0x1D3529)
    val warningBg = JBColor(0xFBF0D9, 0x3A3119)
    val errorBg = JBColor(0xFBE9E7, 0x3D2826)
    val infoBg = JBColor(0xE7EEFC, 0x22304D)

    // ---------------------------------------------------------------- 平台优先的语义色
    /** 列表底色跟随 IDE 主题与第三方主题，优先于固定 hex */
    fun listBackground(): Color = UIUtil.getListBackground()

    fun listForeground(): Color = UIUtil.getListForeground()

    fun selectionBackground(hasFocus: Boolean): Color = UIUtil.getListSelectionBackground(hasFocus)

    fun panelBackground(): Color = UIUtil.getPanelBackground()

    fun secondaryText(): Color = SimpleTextAttributes.GRAYED_ATTRIBUTES.fgColor

    // ---------------------------------------------------------------- 字阶（基准 13px）
    fun fontBase(): Font = JBUI.Fonts.label()

    fun fontSmall(): Font = JBUI.Fonts.smallFont()

    fun fontBadge(): Font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)

    fun fontTitle(): Font = JBUI.Fonts.label().deriveFont(Font.BOLD)

    // ---------------------------------------------------------------- 间距 / 边框
    fun scale(value: Int): Int = JBUI.scale(value)

    fun empty(top: Int, left: Int, bottom: Int, right: Int): Border =
        JBUI.Borders.empty(scale(top), scale(left), scale(bottom), scale(right))

    fun empty(vertical: Int, horizontal: Int): Border = JBUI.Borders.empty(scale(vertical), scale(horizontal))

    /** 入参已是缩放后的像素（通常是 Density.*），不再二次 scale */
    fun emptyPx(vertical: Int, horizontal: Int): Border = JBUI.Borders.empty(vertical, horizontal)

    fun emptyPx(top: Int, left: Int, bottom: Int, right: Int): Border =
        JBUI.Borders.empty(top, left, bottom, right)

    fun line(color: Color, top: Int = 0, left: Int = 0, bottom: Int = 0, right: Int = 0): Border =
        JBUI.Borders.customLine(color, scale(top), scale(left), scale(bottom), scale(right))

    fun compound(outer: Border, inner: Border): Border = BorderFactory.createCompoundBorder(outer, inner)

    /**
     * 紧凑模式的统一间距标尺。工具窗口是常驻面板，纵向空间比横向贵：
     * 顶部每一段、每个输入框、每行列表都从这里取值，禁止各页再写一套 padding。
     * 调密度时只改这里，避免改了 A 页忘了 B 页。
     */
    object Density {
        /** 根容器外边距（上下 / 左右） */
        val outerV: Int get() = scale(3)
        val outerH: Int get() = scale(6)

        /** 顶部段与段之间、BorderLayout 的 vgap */
        val gap: Int get() = scale(2)

        /** 列表行内边距 */
        val rowPadV: Int get() = scale(2)
        val rowPadH: Int get() = scale(6)

        /** 输入框 / 只读文本区的内边距：用户明确要求「不要太多 padding」 */
        val fieldPadV: Int get() = scale(2)
        val fieldPadH: Int get() = scale(5)

        val bannerPadV: Int get() = scale(4)
        val statusPadV: Int get() = scale(5)

        /** 双行列表行高；字符串列表在空输入时切到单行高度 */
        val listRow: Int get() = scale(38)
        val listRowSingle: Int get() = scale(24)
        val listRowDouble: Int get() = scale(40)

        val divider: Int get() = scale(3)

        /** 顶部各段（动作 / 搜索 / 高级 / Banner）统一边框，值已缩放 */
        fun sectionBorder(): Border = JBUI.Borders.empty(0, scale(2), gap, scale(2))
    }

    /** 控件焦点环：2px 强调色 + 2px offset，跨组件一致 */
    val focusRing: Border
        get() = BorderFactory.createCompoundBorder(
            JBUI.Borders.empty(2),
            JBUI.Borders.customLine(accent, scale(2))
        )

    // ---------------------------------------------------------------- 方法 / 级别徽标
    data class BadgeStyle(val foreground: JBColor, val background: JBColor)

    private val badgeSuccess = BadgeStyle(JBColor(0x167A45, 0x6FCF8B), JBColor(0xE4F5EB, 0x1D3529))
    private val badgeInfo = BadgeStyle(JBColor(0x2C63CE, 0x7DA9FF), JBColor(0xE7EEFC, 0x22304D))
    private val badgeWarning = BadgeStyle(JBColor(0x9A6200, 0xE3B341), JBColor(0xFBF0D9, 0x3A3119))
    private val badgeError = BadgeStyle(JBColor(0xC23B33, 0xF08178), JBColor(0xFBE9E7, 0x3D2826))
    private val badgePatch = BadgeStyle(JBColor(0x7A43B8, 0xBE93E4), JBColor(0xF0E7F9, 0x332844))
    private val badgeTeal = BadgeStyle(JBColor(0x0E7370, 0x63C4BF), JBColor(0xDFF2F1, 0x1C3433))
    private val badgeNeutral = BadgeStyle(JBColor(0x5C6878, 0xAAB6C6), JBColor(0xEDF1F6, 0x2B323C))

    /** 徽标文字本身可读，颜色只作增强；未知类型统一中性色 */
    fun badge(method: String): BadgeStyle = when (method.uppercase()) {
        "GET", "INSERT" -> badgeSuccess
        "POST", "SELECT" -> badgeInfo
        "PUT", "DUBBO", "UPDATE" -> badgeWarning
        "DELETE" -> badgeError
        "PATCH" -> badgePatch
        "DUBBO_REF", "DUBBO_REFERENCE" -> badgeTeal
        else -> badgeNeutral
    }

    /** 老式纯文本渲染器（日志级别等）用的属性，颜色同样来自这里 */
    fun badgeAttributes(method: String): SimpleTextAttributes =
        SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, badge(method).foreground)
}

/**
 * 文本省略工具。窄宽度下列表主 / 副行必须用「…」收尾，不能让 Swing 硬切字符。
 * 二分找最长可容纳前缀，避免逐字符测量。
 */
object UiText {
    fun ellipsize(text: String, metrics: FontMetrics, maxWidth: Int): String =
        ellipsizeBy(text, maxWidth) { metrics.stringWidth(it) }

    /** 与 AWT 解耦的纯逻辑，测量方式由调用方给出，便于纯 JVM 单测覆盖边界 */
    fun ellipsizeBy(text: String, maxWidth: Int, measure: (String) -> Int): String {
        if (text.isEmpty() || maxWidth <= 0) return ""
        if (measure(text) <= maxWidth) return text
        val suffix = "…"
        val suffixWidth = measure(suffix)
        if (suffixWidth >= maxWidth) return suffix
        var low = 0
        var high = text.length
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (measure(text.substring(0, middle)) + suffixWidth <= maxWidth) {
                low = middle
            } else {
                high = middle - 1
            }
        }
        return text.substring(0, low) + suffix
    }
}
