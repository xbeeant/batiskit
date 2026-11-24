package io.github.xbeeant.batiskit.rainbow

import java.awt.Color

/**
 * 彩虹括号配色生成器（纯 Kotlin，只依赖 JDK 的 [Color]，零 IntelliJ 平台依赖）。
 *
 * 对标 Rainbow Brackets 的 color generator：给一个色相倾向与亮度档位，按黄金角在色环上
 * 均匀铺开 [count] 个颜色，保证相邻层级色相差距足够大、不会糊成一片；单色档位把饱和度压到
 * 很低，只靠明度区分。也支持从 `#RRGGBB` 提取色相当起点。
 */
object RainbowPalette {

    /** 色相倾向；[MONOCHROME] 只按明度区分层级。 */
    enum class Hue(val label: String, val degrees: Float) {
        RED("红", 0f),
        ORANGE("橙", 30f),
        YELLOW("黄", 60f),
        GREEN("绿", 120f),
        BLUE("蓝", 210f),
        PURPLE("紫", 270f),
        PINK("粉", 330f),
        MONOCHROME("单色", 210f),
        ;

        override fun toString(): String = label

        companion object {
            fun fromName(name: String?): Hue = values().firstOrNull { it.name.equals(name, true) } ?: BLUE
        }
    }

    enum class Luminosity(val label: String, val saturation: Float, val brightness: Float) {
        BRIGHT("明亮", 0.72f, 0.92f),
        LIGHT("浅", 0.48f, 0.98f),
        DARK("深", 0.70f, 0.62f),
        ;

        override fun toString(): String = label

        companion object {
            fun fromName(name: String?): Luminosity = values().firstOrNull { it.name.equals(name, true) } ?: BRIGHT
        }
    }

    /** HSL 里的黄金角比例，用来把相邻层级拉开到约 137.5° */
    private const val GOLDEN_RATIO_CONJUGATE = 0.618033988749895f

    const val MAX_COLORS = 10

    /** 生成 [count] 个颜色；count 会被收敛到 1..[MAX_COLORS]。 */
    fun generate(
        count: Int,
        hue: Hue = Hue.BLUE,
        luminosity: Luminosity = Luminosity.BRIGHT,
        baseHex: String? = null,
    ): List<Color> {
        val n = count.coerceIn(1, MAX_COLORS)
        val startHue = baseHue(baseHex) ?: hue.degrees
        return List(n) { index ->
            val h = ((startHue + 360f * GOLDEN_RATIO_CONJUGATE * index) % 360f + 360f) % 360f / 360f
            if (hue == Hue.MONOCHROME) {
                // 单色：饱和度压到极低，只让明度随层级线性变化
                val light = luminosity.brightness * (0.55f + 0.45f * (1f - index.toFloat() / n))
                Color.getHSBColor(h, 0.08f, light.coerceIn(0.25f, 1f))
            } else {
                Color.getHSBColor(h, luminosity.saturation, luminosity.brightness)
            }
        }
    }

    /** 从 `#RRGGBB` / `RRGGBB` 里提取色相（度）；非法输入返回 null，由调用方回退到预设色相。 */
    fun baseHue(hex: String?): Float? {
        val raw = hex?.trim()?.removePrefix("#") ?: return null
        if (raw.length != 6 || raw.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        val r = raw.substring(0, 2).toInt(16)
        val g = raw.substring(2, 4).toInt(16)
        val b = raw.substring(4, 6).toInt(16)
        return Color.RGBtoHSB(r, g, b, null)[0] * 360f
    }

    fun hex(color: Color): String = "#%02X%02X%02X".format(color.red, color.green, color.blue)
}
