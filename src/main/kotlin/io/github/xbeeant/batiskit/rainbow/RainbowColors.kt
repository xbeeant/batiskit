package io.github.xbeeant.batiskit.rainbow

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor

/**
 * 十个可配色的层级色（前五个是早期版本的默认色，逐字保留，升级后已改过的配色不会被冲掉）。
 *
 * 颜色是 IDE 标准的 TextAttributesKey：Settings → Editor → Color Scheme → Rainbow Brackets
 * 里改的是它们，配色方案会跟着方案一起保存 / 导出。`JBColor` 让亮色与暗色主题各有默认值，
 * 不依赖某一份具体的 .icls 文件。设置页的「循环颜色数」决定实际用到前几个。
 */
object RainbowColors {

    const val COUNT = 10

    /** 默认实际启用的循环颜色数：扩到 10 色后仍保持 5，避免老用户升级后配色周期突变 */
    const val DEFAULT_ACTIVE_COLORS = 5

    /** 亮色主题：在白底上有足够对比度 */
    private val LIGHT = intArrayOf(
        0xC62828, 0xA8620A, 0x1B7F4B, 0x1F6FBF, 0x8E44AD,
        0x00838F, 0xD81B60, 0x5E35B1, 0x6D4C41, 0x33691E,
    )

    /** 暗色主题：在深底上不刺眼 */
    private val DARK = intArrayOf(
        0xE06C75, 0xE5C07B, 0x98C379, 0x61AFEF, 0xC678DD,
        0x56B6C2, 0xF06292, 0xB39DDB, 0xD7A86E, 0xAED581,
    )

    val NAMES: List<String> = List(COUNT) { "括号层级 ${it + 1}" }

    val KEYS: Array<TextAttributesKey> = Array(COUNT) { index ->
        val attributes = TextAttributes().apply {
            foregroundColor = JBColor(LIGHT[index], DARK[index])
        }
        @Suppress("DEPRECATION")
        TextAttributesKey.createTextAttributesKey("SPRING_MYBATIS_RAINBOW_BRACKET_${index + 1}", attributes)
    }

    /** 预览页的标签名（ColorSettingsPage 的 demo 文本用） */
    fun tag(index: Int): String = "rb${index + 1}"
}
