package io.github.xbeeant.batiskit.console

import java.awt.Color

/**
 * 设置页颜色值的纯逻辑层：`浅色hex|深色hex` ↔ [java.awt.Color]。
 *
 * 刻意不 import 任何 IntelliJ 平台类：设置页要在 Light / Dark 两种主题下**同时**展示预览，
 * 不能靠 [com.intellij.ui.JBColor] 那种「跟随当前 LAF」的单一取值；而纯 JVM 单测也必须能直接跑。
 * 与 [GrepConsoleSettings.parseColor] / [GrepHighlightColor] 共用同一套 hex 约定：
 * 只写一段时两个主题共用，空串表示不着色，非法值不当作颜色。
 */
data class ColorSpec(val light: String? = null, val dark: String? = null) {

    val isEmpty: Boolean get() = light == null

    /** 主题对应的 hex；只填了浅色时深色主题复用它 */
    fun hex(darkTheme: Boolean): String? = if (darkTheme) dark ?: light else light

    /** 统一输出两段，避免「单段 / 双段」写法差异让设置页一直显示未保存 */
    fun encode(): String {
        val head = light ?: return ""
        return "$head|${dark ?: head}"
    }

    companion object {
        val EMPTY = ColorSpec()
    }
}

object ColorSpecs {

    private val HEX = Regex("#?[0-9a-fA-F]{6}")

    fun canonical(hex: String?): String? {
        if (hex == null) return null
        val value = hex.trim().removePrefix("#")
        if (!HEX.matches(value)) return null
        return "#" + value.uppercase()
    }

    /**
     * 解析配置串。第一段非法就整项视为空（与 parseColor 的行为一致，不会把脏数据当成浅色）；
     * 第二段非法或缺失则复用浅色，而不是丢掉用户已经选好的颜色。
     */
    fun parse(spec: String?): ColorSpec {
        if (spec.isNullOrBlank()) return ColorSpec.EMPTY
        val parts = spec.split('|').map { it.trim() }
        val light = canonical(parts.getOrNull(0)) ?: return ColorSpec.EMPTY
        val second = parts.getOrNull(1)
        val dark = if (second == null) light else canonical(second) ?: light
        return ColorSpec(light, dark)
    }

    /** 规范化成 `#RRGGBB|#RRGGBB`；空 / 非法返回空串（不着色） */
    fun normalize(spec: String?): String = parse(spec).encode()

    /** 配置串是否「写了东西但格式不对」，UI 用它给出即时反馈 */
    fun isInvalid(spec: String?): Boolean = !spec.isNullOrBlank() && parse(spec).isEmpty

    fun color(hex: String?): Color? {
        val value = canonical(hex) ?: return null
        return runCatching { Color(value.removePrefix("#").toInt(16)) }.getOrNull()
    }

    fun color(spec: String?, darkTheme: Boolean): Color? = color(parse(spec).hex(darkTheme))

    fun hex(color: Color): String = String.format("#%02X%02X%02X", color.red, color.green, color.blue)

    /** 色板选出的颜色转成双主题串；深色主题用亮度压到 30% 的一份，避免亮底在暗色控制台晃眼 */
    fun fromColor(light: Color, dark: Color = deriveDark(light)): String = "${hex(light)}|${hex(dark)}"

    /**
     * 只替换某一主题的那一份颜色。之前两主题共用一个色时同步改（保持共用语义），
     * 用户单独调过另一主题则原样保留，避免一次选色覆盖掉手动调好的配色。
     */
    fun withThemeColor(spec: String?, color: Color, darkTheme: Boolean): String {
        val current = parse(spec)
        val picked = hex(color)
        if (darkTheme) return ColorSpec(current.light ?: picked, picked).encode()
        val dark = if (current.dark == null || current.dark == current.light) picked else current.dark
        return ColorSpec(picked, dark).encode()
    }

    fun deriveDark(light: Color): Color {
        fun dim(value: Int) = (value * 0.30).toInt().coerceIn(0, 255)
        return Color(dim(light.red), dim(light.green), dim(light.blue))
    }

    // ---------------------------------------------------------------- 可读性（WCAG 2.1 相对亮度）

    fun contrastRatio(first: Color, second: Color): Double {
        val a = luminance(first)
        val b = luminance(second)
        val lighter = maxOf(a, b)
        val darker = minOf(a, b)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** 预览里放在自定义背景色上的文字，自动取黑 / 白中对比度更高的一份 */
    fun readableTextOn(background: Color): Color =
        if (contrastRatio(background, Color.WHITE) >= contrastRatio(background, Color.BLACK)) {
            Color.WHITE
        } else {
            Color.BLACK
        }

    private fun luminance(color: Color): Double {
        fun channel(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
    }
}
