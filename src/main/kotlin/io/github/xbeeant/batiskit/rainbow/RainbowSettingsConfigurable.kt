package io.github.xbeeant.batiskit.rainbow

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_SHORT
import com.intellij.ui.dsl.builder.COLUMNS_TINY
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Settings → Tools → Batis Kit → Rainbow Brackets：只管行为，颜色在 Color Scheme 页里改。
 *
 * 「颜色生成器」是对标 Rainbow Brackets 的能力：选色相 / 亮度 / 数量先出预览，确认后才把
 * 生成的颜色写进当前配色方案。生成器参数与色块是手动绑定的 Swing 组件，其余字段仍走 ui.dsl 绑定。
 */
class RainbowSettingsConfigurable : SearchableConfigurable {

    override fun getId(): String = "io.github.xbeeant.batiskit.rainbow.settings"

    private val state = RainbowSettings.getInstance().state
    private var panel: DialogPanel? = null

    private val hueBox = ComboBox(RainbowPalette.Hue.values())
    private val luminosityBox = ComboBox(RainbowPalette.Luminosity.values())
    private val countBox = ComboBox((1..RainbowPalette.MAX_COLORS).toList().toTypedArray())
    private val baseHexField = JBTextField(10)
    private val swatchPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))

    /** 最近一次生成的颜色，供「应用到配色方案」复用 */
    private var generated: List<Color> = emptyList()

    override fun getDisplayName(): String = "Rainbow Brackets"

    override fun createComponent(): JComponent = panel {
        group("着色") {
            row {
                checkBox("启用彩虹括号（按嵌套层级给 () [] {} 着色）")
                    .bindSelected(state::enabled)
            }
            row {
                checkBox("XML / HTML 标签的尖括号也按标签层级着色")
                    .bindSelected(state::xmlTagBrackets)
                    .comment("按 XmlTag 的父子层级取色；Java 泛型与比较运算符不参与，避免把 a < b 当成括号")
            }
            row {
                checkBox("标签名本身也按层级着色")
                    .bindSelected(state::rainbowifyTagNames)
                    .comment("开标签里的名字逐字符取同层级色；与尖括号开关相互独立，默认关闭以保持旧版观感")
            }
            row("循环颜色数") {
                textField()
                    .columns(COLUMNS_TINY)
                    .bindIntText(state::colorCount)
                    .comment("1..${RainbowColors.COUNT}，超过层数的部分从头循环；颜色在 Settings → Editor → Color Scheme → Rainbow Brackets 里改")
            }
        }
        group("范围") {
            row("仅对这些语言 / 文件生效") {
                textField()
                    .columns(COLUMNS_SHORT)
                    .bindText(state::onlyLanguages)
                    .comment("逗号分隔，可写语言 ID、文件名或扩展名：JAVA, kotlin, pom.xml, *.java；留空 = 所有语言")
            }
            row("排除这些语言 / 文件") {
                textField()
                    .columns(COLUMNS_SHORT)
                    .bindText(state::excludeLanguages)
                    .comment("写法同上，优先级高于白名单；例如填 *.min.js 可跳过压缩后的大文件")
            }
            row("单文件大小上限 (KB)") {
                textField()
                    .columns(COLUMNS_TINY)
                    .bindIntText(state::maxFileSizeKb)
                    .comment("超过上限的文件整体跳过，避免超大文件每次重算层级")
            }
            row {
                comment(
                    "括号层级按整份文件算一次，增量高亮只重算局部时也不会错位；" +
                        "字符串 / 注释里的括号不是独立 token，不会参与着色。"
                )
            }
        }
        group("颜色生成器") {
            row("色相倾向") { cell(hueBox) }
            row("亮度") { cell(luminosityBox) }
            row("生成颜色数") { cell(countBox) }
            row("起始色（可选）") {
                cell(baseHexField)
                    .comment("填 #RRGGBB 则以它的色相为起点；留空用上面的色相倾向")
            }
            row {
                button("生成预览") { regenerate() }
                button("应用到配色方案…") { applyPalette() }
            }
            row { cell(swatchPanel) }
            row {
                comment(
                    "按黄金角在色环上铺开，相邻层级色相差距足够大。应用会覆盖当前 Color Scheme → " +
                        "Rainbow Brackets 的前 N 项（可在配色页改回），并同步把「循环颜色数」设为 N。"
                )
            }
        }
        row {
            comment("编辑器右键「彩虹括号（Batis Kit）」可快速开关；「高亮当前作用域」重复触发会向外扩展一层，到最外层后再触发即清除。")
        }
    }.also {
        panel = it
        hueBox.selectedItem = RainbowPalette.Hue.fromName(state.paletteHue)
        luminosityBox.selectedItem = RainbowPalette.Luminosity.fromName(state.paletteLuminosity)
        countBox.selectedItem = state.colorCount.coerceIn(1, RainbowPalette.MAX_COLORS)
        swatchPanel.border = BorderFactory.createEmptyBorder(2, 0, 2, 0)
        swatchPanel.minimumSize = Dimension(0, 34)
        regenerate()
    }

    override fun isModified(): Boolean {
        // createComponent 之前下拉还是构造默认值，不能拿它跟 state 比，否则设置页一打开就是脏的
        if (panel == null) return false
        val dslModified = panel?.isModified() ?: false
        return dslModified ||
            (hueBox.selectedItem as? RainbowPalette.Hue)?.name != state.paletteHue ||
            (luminosityBox.selectedItem as? RainbowPalette.Luminosity)?.name != state.paletteLuminosity
    }

    override fun apply() {
        panel?.apply()
        state.colorCount = state.colorCount.coerceIn(1, RainbowColors.COUNT)
        state.maxFileSizeKb = state.maxFileSizeKb.coerceIn(1, 100_000)
        syncPalettePrefs()
        RainbowSettings.restartHighlighting()
    }

    override fun reset() {
        panel?.reset()
        hueBox.selectedItem = RainbowPalette.Hue.fromName(state.paletteHue)
        luminosityBox.selectedItem = RainbowPalette.Luminosity.fromName(state.paletteLuminosity)
        countBox.selectedItem = state.colorCount.coerceIn(1, RainbowPalette.MAX_COLORS)
        regenerate()
    }

    private fun syncPalettePrefs() {
        state.paletteHue = (hueBox.selectedItem as? RainbowPalette.Hue ?: RainbowPalette.Hue.BLUE).name
        state.paletteLuminosity =
            (luminosityBox.selectedItem as? RainbowPalette.Luminosity ?: RainbowPalette.Luminosity.BRIGHT).name
    }

    private fun regenerate() {
        val hue = hueBox.selectedItem as? RainbowPalette.Hue ?: RainbowPalette.Hue.BLUE
        val luminosity = luminosityBox.selectedItem as? RainbowPalette.Luminosity
            ?: RainbowPalette.Luminosity.BRIGHT
        val count = (countBox.selectedItem as? Int ?: RainbowColors.DEFAULT_ACTIVE_COLORS)
            .coerceIn(1, RainbowPalette.MAX_COLORS)
        generated = RainbowPalette.generate(count, hue, luminosity, baseHexField.text.takeIf { it.isNotBlank() })
        renderSwatches()
    }

    private fun renderSwatches() {
        swatchPanel.removeAll()
        generated.forEachIndexed { index, color ->
            val label = JLabel(" ${index + 1} ")
            label.isOpaque = true
            label.background = color
            label.foreground = if (isLight(color)) Color(0x22, 0x22, 0x22) else Color.WHITE
            label.toolTipText = RainbowPalette.hex(color)
            label.border = BorderFactory.createEmptyBorder(5, 8, 5, 8)
            swatchPanel.add(label)
        }
        swatchPanel.revalidate()
        swatchPanel.repaint()
    }

    private fun isLight(color: Color): Boolean =
        (0.299 * color.red + 0.587 * color.green + 0.114 * color.blue) / 255.0 > 0.62

    /** 把预览色写进当前全局配色方案；用户确认后才会执行，且同步启用相同数量的层级色。 */
    private fun applyPalette() {
        if (generated.isEmpty()) regenerate()
        val colors = generated
        val parent = panel ?: return
        val answer = Messages.showYesNoDialog(
            parent,
            "将用生成的 ${colors.size} 个颜色覆盖当前配色方案中 Rainbow Brackets 的前 ${colors.size} 项，" +
                "并把「循环颜色数」设为 ${colors.size}。已手动调过的配色会被替换，继续？",
            "应用生成配色",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return

        // 先把设置页里其它未 Apply 的改动落下来，避免同步 colorCount 后 reset 把它们丢掉
        panel?.apply()
        syncPalettePrefs()
        state.colorCount = colors.size
        state.maxFileSizeKb = state.maxFileSizeKb.coerceIn(1, 100_000)
        writeToScheme(colors)
        panel?.reset()
        regenerate()
        RainbowSettings.restartHighlighting()
    }

    private fun writeToScheme(colors: List<Color>) {
        val scheme = EditorColorsManager.getInstance().globalScheme
        colors.forEachIndexed { index, color ->
            val key = RainbowColors.KEYS[index]
            val previous = scheme.getAttributes(key)
            val attributes = TextAttributes().apply {
                foregroundColor = color
                previous?.let {
                    backgroundColor = it.backgroundColor
                    effectColor = it.effectColor
                    effectType = it.effectType
                    fontType = it.fontType
                }
            }
            scheme.setAttributes(key, attributes)
        }
    }
}
