package io.github.xbeeant.batiskit.ui.components

import com.intellij.ui.components.JBTextField
import io.github.xbeeant.batiskit.console.ColorSpecs
import io.github.xbeeant.batiskit.ui.UiTokens
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/** 颜色用途：前景 = 文字色，背景 = 命中片段底色 / 规则背景色 */
enum class ColorUsage { FOREGROUND, BACKGROUND }

/**
 * 设置页的一行颜色编辑控件：hex 手输 + 浅 / 深色板 + 双主题实时预览。
 *
 * 配置串仍是 `浅色hex|深色hex`（[ColorSpecs] 统一解析），所以持久化格式、控制台过滤器
 * 和旧的 xml 配置完全不变；这里只是把「盲填 hex」换成「选色 + 看得见效果」。
 * [picker] 默认用平台 ColorPicker，测试可注入固定颜色；[onChange] 供设置页刷新整页预览。
 */
class ColorSpecField(
    private val usage: ColorUsage = ColorUsage.FOREGROUND,
    private val allowEmpty: Boolean = true,
    private val defaultSpec: String? = null,
    private val picker: (Component, Color, String) -> Color? = { parent, current, title ->
        ColorSwatchButton.platformPicker(parent, current, title)
    },
    private val onChange: (() -> Unit)? = null,
) : JPanel(BorderLayout()) {

    private val hexField = JBTextField(18)
    private val defaultForeground = hexField.foreground
    private val preview = InlinePreview()

    val lightButton = ColorSwatchButton("浅色", picker = picker)
    val darkButton = ColorSwatchButton("深色", picker = picker)

    private val autoButton = JButton("自动").apply {
        toolTipText = "由浅色自动派生深色主题颜色（亮度压到 30%）"
    }
    private val defaultButton = JButton("默认").apply {
        toolTipText = "恢复这一项的默认颜色"
    }
    private val clearButton = JButton("清除").apply {
        toolTipText = "清空颜色：该项不着色"
    }

    private var suppressEvents = false

    /** 当前配置串原样（保留用户手输，非法值由 parseColor 安全地忽略） */
    var spec: String
        get() = hexField.text.trim()
        set(value) {
            suppressEvents = true
            try {
                hexField.text = value
            } finally {
                suppressEvents = false
            }
            sync()
            // 代码赋值（reset / 恢复默认 / 选色）与手输走同一条通知链，预览不会停在旧颜色
            notifyChange()
        }

    /** 规范化后的配置串，供 isModified 指纹 / 写入 state 使用 */
    val normalizedSpec: String get() = ColorSpecs.normalize(spec)

    init {
        isOpaque = false
        hexField.toolTipText = "格式：#RRGGBB|#RRGGBB；只填一个时浅色 / 深色主题共用；留空 = 不着色"
        hexField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(event: DocumentEvent) = edited()
            override fun removeUpdate(event: DocumentEvent) = edited()
            override fun changedUpdate(event: DocumentEvent) = edited()

            private fun edited() {
                if (suppressEvents) return
                sync()
                onChange?.invoke()
            }
        })

        lightButton.onColorPicked = { color ->
            spec = ColorSpecs.withThemeColor(spec, color, darkTheme = false)
        }
        darkButton.onColorPicked = { color ->
            spec = ColorSpecs.withThemeColor(spec, color, darkTheme = true)
        }
        autoButton.addActionListener {
            val light = ColorSpecs.color(ColorSpecs.parse(spec).light) ?: Color(0xFFE9A8)
            spec = ColorSpecs.fromColor(light)
        }
        defaultButton.addActionListener {
            spec = defaultSpec.orEmpty()
        }
        clearButton.addActionListener {
            spec = ""
        }

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, UiTokens.scale(4), 0)).apply {
            isOpaque = false
            add(hexField)
            add(lightButton)
            add(darkButton)
            add(autoButton)
            if (defaultSpec != null) add(defaultButton)
            if (allowEmpty) add(clearButton)
            add(preview)
        }
        add(controls, BorderLayout.CENTER)
        sync()
    }

    /** 颜色刷新也走这里：文档变更、代码 setValue、主题切换后都能重画 */
    fun sync() {
        val raw = hexField.text
        val parsed = ColorSpecs.parse(raw)
        lightButton.swatchColor = ColorSpecs.color(parsed.light)
        darkButton.swatchColor = ColorSpecs.color(parsed.dark)
        val invalid = ColorSpecs.isInvalid(raw)
        hexField.foreground = if (invalid) UiTokens.error else defaultForeground
        hexField.toolTipText = if (invalid) {
            "格式应为 #RRGGBB|#RRGGBB，当前值不会生效"
        } else {
            "格式：#RRGGBB|#RRGGBB；只填一个时浅色 / 深色主题共用；留空 = 不着色"
        }
        preview.repaint()
    }

    private fun notifyChange() = onChange?.invoke()

    /** 并排两个小样本：左边浅色主题、右边深色主题，用各自那一份 hex 渲染 */
    private inner class InlinePreview : JPanel() {

        init {
            toolTipText = "颜色预览：左半为浅色主题，右半为深色主题"
            preferredSize = Dimension(UiTokens.scale(168), UiTokens.scale(24))
            minimumSize = preferredSize
            border = UiTokens.line(UiTokens.borderSubtle, 1, 1, 1, 1)
        }

        override fun paintComponent(graphics: Graphics) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.font = UiTokens.fontSmall()
                val metrics = g.fontMetrics
                val half = width / 2
                val sample = "Aa 示例"
                val sampleWidth = metrics.stringWidth(sample)
                val baseline = (height - metrics.height) / 2 + metrics.ascent
                val parsed = ColorSpecs.parse(hexField.text)

                listOf(false, true).forEachIndexed { index, darkTheme ->
                    val x = index * half
                    val w = if (index == 0) half else width - half
                    val consoleBackground = if (darkTheme) DARK_CONSOLE else LIGHT_CONSOLE
                    val normalText = if (darkTheme) LIGHT_CONSOLE_TEXT else DARK_CONSOLE_TEXT
                    g.color = consoleBackground
                    g.fillRect(x, 0, w, height)
                    val chosen = ColorSpecs.color(parsed.hex(darkTheme))
                    val textX = x + ((w - sampleWidth) / 2).coerceAtLeast(2)
                    when {
                        usage == ColorUsage.FOREGROUND -> {
                            g.color = chosen ?: normalText
                            g.drawString(sample, textX, baseline)
                        }
                        chosen != null -> {
                            val pad = UiTokens.scale(2)
                            g.color = chosen
                            g.fillRoundRect(x + pad, pad, w - pad * 2, height - pad * 2, UiTokens.scale(4), UiTokens.scale(4))
                            g.color = ColorSpecs.readableTextOn(chosen)
                            g.drawString(sample, textX, baseline)
                        }
                        else -> {
                            g.color = normalText
                            g.drawString(sample, textX, baseline)
                        }
                    }
                    if (index == 0) {
                        g.color = UiTokens.borderSubtle
                        g.drawLine(half, 0, half, height)
                    }
                }
            } finally {
                g.dispose()
            }
        }
    }

    companion object {
        private val LIGHT_CONSOLE = Color(0xFBFCFE)
        private val DARK_CONSOLE = Color(0x1C2027)
        private val DARK_CONSOLE_TEXT = Color(0x1B2532)
        private val LIGHT_CONSOLE_TEXT = Color(0xE2E8F1)
    }
}

