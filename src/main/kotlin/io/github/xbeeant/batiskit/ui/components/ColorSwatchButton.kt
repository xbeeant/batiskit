package io.github.xbeeant.batiskit.ui.components

import com.intellij.ui.ColorPicker
import java.awt.Color
import java.awt.Component
import java.awt.Insets
import javax.swing.JButton
import javax.swing.SwingConstants

/**
 * 色块按钮：点一下弹出 IDE 自带的颜色选择器，选完立即把新色同步到配置串和预览。
 *
 * [picker] 可注入，默认走平台 [ColorPicker]；单测里换成固定返回值即可覆盖
 * 「点击 → 颜色更新 → 预览刷新」整条链路，不必在 headless 环境弹模态框。
 */
class ColorSwatchButton(
    text: String,
    initial: Color? = null,
    private val picker: (Component, Color, String) -> Color? = { parent, current, title ->
        platformPicker(parent, current, title)
    },
) : JButton(text, ColorSwatchIcon(initial)) {

    /** 当前色；null 表示「不着色 / 值非法」，图标会画成带斜线的空色块 */
    var swatchColor: Color? = initial
        set(value) {
            field = value
            icon = ColorSwatchIcon(value)
            repaint()
        }

    /** 选完颜色后的回调（由 ColorSpecField 负责写回 hex 串） */
    var onColorPicked: ((Color) -> Unit)? = null

    init {
        toolTipText = "$text：点击打开颜色选择器"
        // JButton 的 AccessibleContext 是懒创建的，直接取字段在构造期可能为 null
        putClientProperty(javax.accessibility.AccessibleContext.ACCESSIBLE_NAME_PROPERTY, text)
        horizontalTextPosition = SwingConstants.TRAILING
        iconTextGap = 4
        margin = Insets(2, 4, 2, 4)
        isBorderPainted = false
        isContentAreaFilled = false
        isFocusPainted = true
        addActionListener {
            val previous = swatchColor ?: Color(0xFFE9A8)
            val picked = picker(this, previous, text) ?: return@addActionListener
            swatchColor = picked
            onColorPicked?.invoke(picked)
        }
    }

    companion object {
        /** parent 必须非 null（ColorPicker.showDialog 标了 @NotNull），直接用按钮自身最稳 */
        fun platformPicker(parent: Component, current: Color, title: String): Color? =
            ColorPicker.showDialog(parent, title, current, true, null, true)
    }
}
