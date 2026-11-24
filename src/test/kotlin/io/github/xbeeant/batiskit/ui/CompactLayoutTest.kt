package io.github.xbeeant.batiskit.ui

import com.intellij.ui.components.JBLabel
import io.github.xbeeant.batiskit.ui.UiTokens.Density
import io.github.xbeeant.batiskit.ui.components.BannerPanel
import io.github.xbeeant.batiskit.ui.components.EmptyStatePanel
import io.github.xbeeant.batiskit.ui.components.FilterChipGroup
import io.github.xbeeant.batiskit.ui.components.SearchField
import io.github.xbeeant.batiskit.string.StringOp
import io.github.xbeeant.batiskit.string.StringOps
import io.github.xbeeant.batiskit.string.ui.StringOpRenderer
import io.github.xbeeant.batiskit.ui.components.StatusBarPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JList
import javax.swing.JPanel

/**
 * 紧凑布局的量化回归：防止以后有人把间距、输入框 padding、列表行高又调回去。
 *
 * 用户反馈三张工具窗口截图「留白太多、输入框 padding 太大」，本测试把这些上限钉住：
 * 标尺值、搜索框实际高度 / insets、骨架首屏顶部高度、空状态贴顶。
 */
class CompactLayoutTest {

    @Test
    fun `紧凑标尺各值守住上限`() {
        val scale = UiTokens.scale(1).coerceAtLeast(1)
        assertTrue("根容器上下外边距过大", Density.outerV <= 3 * scale)
        assertTrue("段间距过大", Density.gap <= 2 * scale)
        assertTrue("输入框上下 padding 过大：${Density.fieldPadV}", Density.fieldPadV <= 2 * scale)
        assertTrue("输入框左右 padding 过大：${Density.fieldPadH}", Density.fieldPadH <= 6 * scale)
        assertTrue("列表行内上下 padding 过大", Density.rowPadV <= 2 * scale)
        assertTrue("双行列表行高过大：${Density.listRow}", Density.listRow <= 40 * scale)
        assertTrue("空输入单行行高过大", Density.listRowSingle <= 26 * scale)
        assertTrue("Banner 上下 padding 过大", Density.bannerPadV <= 4 * scale)
        assertTrue("分隔条过厚", Density.divider <= 3 * scale)
    }

    @Test
    fun `搜索框只有薄内边距且高度不超过 32`() {
        val field = SearchField("搜索路径 / 类名 / 方法名") {}
        val insets = field.border.getBorderInsets(field)
        assertTrue("搜索框上下 padding 应为 2px 级，实际 ${insets.top}/${insets.bottom}",
            insets.top <= UiTokens.scale(2) && insets.bottom <= UiTokens.scale(2))
        assertTrue("搜索框左右 padding 应收紧，实际 ${insets.left}/${insets.right}",
            insets.left <= UiTokens.scale(6) && insets.right <= UiTokens.scale(6))
        assertTrue("文字输入框不应再加上下 padding",
            field.textField.border.getBorderInsets(field.textField).top == 0)
        assertTrue("搜索框整体高度 ${field.preferredSize.height} 过大",
            field.preferredSize.height <= UiTokens.scale(32))
    }

    @Test
    fun `骨架不再重复 IDE 标题栏里的工具窗口标题`() {
        val shell = buildShell()
        val labels = descendants(shell.component).filterIsInstance<JBLabel>().map { it.text }
        assertTrue("页面内不应再画一遍标题：$labels", "Spring API" !in labels)
        // NORTH 是 root 里第一个添加的容器；直接取它比按 layout 类型过滤更稳
        val north = shell.component.components[0] as Container
        assertEquals("顶部骨架应只剩动作 / 搜索 / 高级 / Banner 四段", 4, north.components.size)
    }

    @Test
    fun `顶部骨架在 640 宽度下不超过 110px`() {
        val shell = buildShell()
        shell.component.setSize(640, 520)
        layoutRecursively(shell.component)
        val north = shell.component.components[0] as Container
        assertTrue("顶部骨架占 ${north.height}px，仍偏松（上限 110）", north.height <= UiTokens.scale(110))
        assertTrue("内容区要留足高度", shell.component.height - north.height > 300)
    }

    @Test
    fun `空状态贴内容区顶部而不是上下各留一大片空白`() {
        val empty = EmptyStatePanel()
        empty.display("还没发现接口", "确认项目引入了 spring-web", "重新加载") {}
        empty.setSize(600, 400)
        layoutRecursively(empty)
        val column = empty.components.first { it is JPanel && it.layout is java.awt.GridBagLayout }
        assertTrue("空状态应靠上，实际 y=${column.y}", column.y < UiTokens.scale(100))
    }

    @Test
    fun `字符串列表压缩成单行后操作名仍然画得出来`() {
        val renderer = StringOpRenderer { "" }
        renderer.compact = true
        val cell = renderer.getListCellRendererComponent(
            JList<StringOp>().apply { setSize(300, 200) }, StringOps.ALL.first(), 0, false, false
        )
        cell.setSize(300, Density.listRowSingle)
        // doLayout 只布局直接子组件；离屏渲染必须递归，否则内部 label 还是 0x0
        layoutRecursively(cell)
        val image = BufferedImage(300, Density.listRowSingle, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            cell.paint(graphics)
        } finally {
            graphics.dispose()
        }
        var dark = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val color = image.getRGB(x, y)
                val red = (color shr 16) and 0xFF
                val green = (color shr 8) and 0xFF
                val blue = color and 0xFF
                if ((color ushr 24) > 0 && red < 140 && green < 140 && blue < 140) dark++
            }
        }
        assertTrue("压缩成单行后操作名应可见，实际深色像素 $dark", dark > 20)
    }

    @Test
    fun `紧凑 chip 与状态栏仍然可读`() {
        val group = FilterChipGroup(listOf("GET", "POST")) {}
        group.setSize(300, 60)
        group.doLayout()
        val chip = group.getComponent(0)
        assertTrue("chip 文字不能被压没", chip.width > 40 && chip.height > 12)

        val bar = StatusBarPanel()
        bar.update(StatusBarPanel.State.IDLE, "扫描完成", "显示 128 / 128")
        assertTrue("状态栏高度 ${bar.preferredSize.height} 不足以容纳文字",
            bar.preferredSize.height >= 24)
    }

    @Test
    fun `生成字符串列表紧凑单行与两行预览对照快照`() {
        val width = 320
        val ops = StringOps.ALL.take(10)
        val rows = mutableListOf<BufferedImage>()
        ops.forEachIndexed { index, op ->
            rows += renderRow(
                StringOpRenderer { "（空结果）" }.apply { compact = true },
                op, width, Density.listRowSingle, index
            )
        }
        ops.take(4).forEachIndexed { index, op ->
            rows += renderRow(
                StringOpRenderer { "HELLO_WORLD → helloWorld" },
                op, width, Density.listRowDouble, index
            )
        }
        var height = 0
        rows.forEach { height += it.height + 2 }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            var y = 0
            rows.forEach { graphics.drawImage(it, 0, y, null); y += it.height + 2 }
        } finally {
            graphics.dispose()
        }
        val dir = File("build/ui-snapshots").apply { mkdirs() }
        ImageIO.write(image, "png", File(dir, "compact-string-rows.png"))
        var dark = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val color = image.getRGB(x, y)
                if ((color ushr 24) > 0 &&
                    ((color shr 16) and 0xFF) < 140 &&
                    ((color shr 8) and 0xFF) < 140 &&
                    (color and 0xFF) < 140
                ) dark++
            }
        }
        assertTrue("对照快照里应能看到操作名，实际深色像素 $dark", dark > 100)
    }

    private fun renderRow(
        renderer: StringOpRenderer,
        op: StringOp,
        width: Int,
        height: Int,
        index: Int,
    ): BufferedImage {
        val cell = renderer.getListCellRendererComponent(
            JList<StringOp>().apply { setSize(width, 400) }, op, index, index == 0, false
        )
        cell.setSize(width, height)
        layoutRecursively(cell)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            cell.paint(graphics)
        } finally {
            graphics.dispose()
        }
        return image
    }

    private fun buildShell(): ToolWindowShell {
        val shell = ToolWindowShell("Spring API", JPanel())
        shell.setActions(listOf("重新加载", "复制全部", "清空", "导入剪贴板日志").map {
            ToolWindowShell.Action(it, "提示：$it") {}
        })
        shell.setSearchField(SearchField("搜索路径 / 类名 / 方法名") {})
        val chips = FilterChipGroup(listOf("GET", "POST", "PUT")) {}
        shell.setAdvanced("方法", chips) { chips.activeCount() }
        shell.showBanner(
            BannerPanel.Type.INFO,
            "接口地址",
            "http://localhost:8080/order-api   ·   context path /order-api",
            "更改…"
        ) {}
        shell.setStatus(StatusBarPanel.State.IDLE, "扫描完成", "显示 128 / 128")
        shell.setCount("共 128 个接口")
        return shell
    }

    private fun descendants(root: Component): List<Component> {
        val result = mutableListOf<Component>()
        fun walk(component: Component) {
            result.add(component)
            if (component is Container) component.components.forEach(::walk)
        }
        walk(root)
        return result
    }

    /** 两轮布局：WrapLayout / HTML label 的高度会在拿到真实宽度后才收敛 */
    private fun layoutRecursively(component: Component) {
        repeat(2) { layoutOnce(component) }
    }

    private fun layoutOnce(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutOnce)
        }
    }
}
