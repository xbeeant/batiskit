package io.github.xbeeant.batiskit.ui

import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import io.github.xbeeant.batiskit.ui.components.BannerPanel
import io.github.xbeeant.batiskit.ui.components.FilterChipGroup
import io.github.xbeeant.batiskit.ui.components.SearchField
import io.github.xbeeant.batiskit.ui.components.StatusBarPanel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JPanel

/**
 * 工具窗口骨架的布局回归测试 + 离屏快照。
 *
 * 覆盖曾经的两个问题：动作被「更多」菜单截断、Advanced 注释说折叠但实现常驻展开，
 * 以及窄宽度下顶部区域互相挤压。快照写到 build/ui-snapshots 供人工 QA。
 */
class ToolWindowShellTest {

    private fun buildShell(
        title: String,
        actions: List<String> = listOf("重新加载", "复制全部", "清空", "导入剪贴板日志", "清空筛选"),
    ): Pair<ToolWindowShell, FilterChipGroup> {
        val content = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("  GET   /api/orders"), BorderLayout.NORTH)
        }
        val shell = ToolWindowShell(title, content)
        shell.setActions(actions.map { ToolWindowShell.Action(it, "提示：$it") {} })
        shell.setSearchField(SearchField("搜索路径 / 类名 / 方法名") {})
        val chips = FilterChipGroup(
            listOf("GET", "POST", "PUT", "DELETE", "PATCH", "REQUEST", "SCHEDULE", "DUBBO", "DUBBO_REF")
        ) {}
        shell.setAdvanced("方法", chips) { chips.activeCount() }
        shell.showBanner(
            BannerPanel.Type.INFO,
            "接口地址",
            "http://localhost:8080/order-api   ·   context path /order-api   ·   候选 2 个",
            "更改…"
        ) {}
        shell.setStatus(StatusBarPanel.State.IDLE, "扫描完成", "显示 128 / 128")
        shell.setCount("共 128 个接口")
        return shell to chips
    }

    @Test
    fun `所有瞬时动作都直接可见且文字完整`() {
        val actions = listOf("重新加载", "复制全部", "清空", "导入剪贴板日志", "清空筛选")
        val (shell, _) = buildShell("Spring API", actions)
        val labels = descendants(shell.component)
            .filterIsInstance<ActionLink>()
            .map { it.text }
            .toList()
        actions.forEach { action ->
            assertTrue("动作「$action」被折叠进菜单或文字丢失：$labels", action in labels)
        }
    }

    @Test
    fun `高级筛选默认折叠且展开器显示启用数量`() {
        val (shell, chips) = buildShell("Spring API")
        val host = descendants(shell.component)
            .first { it === chips.parent }
        assertFalse("Advanced 首屏应折叠，避免占用列表高度", host.isVisible)
        val toggle = descendants(shell.component)
            .filterIsInstance<javax.swing.JButton>()
            .first { it.text.startsWith("方法") }
        assertTrue("折叠时也要显示已启用项数量：${toggle.text}", toggle.text.contains("${chips.activeCount()}"))
    }

    @Test
    fun `搜索框独占过滤行且可压缩到窄宽度`() {
        val (shell, _) = buildShell("Spring API")
        val field = descendants(shell.component).filterIsInstance<SearchField>().single()
        assertEquals("过滤行只放搜索框", 1, field.parent.componentCount)
        assertEquals(0, field.textField.minimumSize.width)
    }

    @Test
    fun `顶部区域在 320px 宽度下不超过内容区一半`() {
        val (shell, _) = buildShell("Spring API")
        shell.component.setSize(320, 560)
        layoutRecursively(shell.component)
        val north = shell.component.components.first { it is JPanel && it !== shell.component }
        val all = descendants(shell.component).filterIsInstance<Container>().toList()
        val topPanels = all.filter { it.layout is javax.swing.BoxLayout }
        assertTrue("应存在纵向骨架容器", topPanels.isNotEmpty())
        val height = topPanels.maxOf { it.height }.coerceAtLeast(1)
        assertTrue(
            "顶部骨架占 $height px，超过 560px 窗口的一半（${height > 280}）",
            height <= 280
        )
        assertTrue("内容区必须有可见高度", shell.component.height - height > 200)
        assertTrue("north 已布局", north.width > 0)
    }

    @Test
    fun `状态栏完整落在窗口内且高度足够`() {
        val (shell, _) = buildShell("Spring API")
        shell.component.setSize(320, 520)
        layoutRecursively(shell.component)
        val bar = descendants(shell.component).filterIsInstance<StatusBarPanel>().single()
        assertTrue("状态栏高度 ${bar.height} 不足以容纳文字", bar.height >= 24)
        assertTrue(
            "状态栏被窗口底边裁掉：y=${bar.y} h=${bar.height}",
            bar.y + bar.height <= shell.component.height
        )
    }

    @Test
    fun `生成 320 与 640 宽度的离屏快照`() {
        listOf(320, 640).forEach { width ->
            val (shell, _) = buildShell("Spring API")
            shell.component.setSize(width, 520)
            layoutRecursively(shell.component)
            val image = BufferedImage(width, 520, BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try {
                shell.component.paint(graphics)
            } finally {
                graphics.dispose()
            }
            val dir = File("build/ui-snapshots").apply { mkdirs() }
            val output = File(dir, "shell-$width.png")
            ImageIO.write(image, "png", output)
            assertTrue("快照应成功写出", output.length() > 2_000)

            // 顶部 2 倍放大图，方便人工核对文字是否被裁切
            val zoom = BufferedImage(width * 2, 400, BufferedImage.TYPE_INT_ARGB)
            val zg = zoom.createGraphics()
            try {
                zg.scale(2.0, 2.0)
                zg.drawImage(image, 0, 0, width, 200, 0, 0, width, 200, null)
            } finally {
                zg.dispose()
            }
            ImageIO.write(zoom, "png", File(dir, "shell-$width-top2x.png"))
            var nonBlank = 0
            for (y in 0 until 520 step 4) {
                for (x in 0 until width step 4) {
                    if (image.getRGB(x, y) != 0) nonBlank++
                }
            }
            assertTrue("快照不应是空白图", nonBlank > 500)
        }
    }

    /** 不用 sequence{}：测试运行时 IDEA 平台自带的旧 kotlin-stdlib 缺协程挂起支撑类 */
    private fun descendants(root: Component): List<Component> {
        val result = mutableListOf<Component>()
        fun walk(component: Component) {
            result.add(component)
            if (component is Container) component.components.forEach(::walk)
        }
        walk(root)
        return result
    }

    /** 两轮布局：WrapLayout / HTML label 的高度会在拿到真实宽度后变化，模拟 EDT 的 validate 收敛 */
    private fun layoutRecursively(component: Component) {
        repeat(2) {
            layoutOnce(component)
        }
    }

    private fun layoutOnce(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutOnce)
        }
    }
}
