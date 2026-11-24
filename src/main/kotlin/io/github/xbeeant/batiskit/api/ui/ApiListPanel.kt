package io.github.xbeeant.batiskit.api.ui

import com.intellij.openapi.Disposable
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import io.github.xbeeant.batiskit.support.SensitiveData
import com.intellij.openapi.project.Project
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.UIUtil
import io.github.xbeeant.batiskit.ui.ToolWindowShell
import io.github.xbeeant.batiskit.ui.UiText
import io.github.xbeeant.batiskit.ui.UiTokens
import io.github.xbeeant.batiskit.ui.UiTokens.Density
import io.github.xbeeant.batiskit.ui.components.BannerPanel
import io.github.xbeeant.batiskit.ui.components.ContentSwitcher
import io.github.xbeeant.batiskit.ui.components.FilterChipGroup
import io.github.xbeeant.batiskit.ui.components.MethodBadge
import io.github.xbeeant.batiskit.ui.components.SearchField
import io.github.xbeeant.batiskit.ui.components.StatusBarPanel
import com.intellij.psi.PsiMethod
import io.github.xbeeant.batiskit.api.ApiFilter
import io.github.xbeeant.batiskit.api.ContextPathResolver
import io.github.xbeeant.batiskit.api.ContextPathRules
import io.github.xbeeant.batiskit.api.CurlBuilder
import io.github.xbeeant.batiskit.api.CurlPsiReader
import io.github.xbeeant.batiskit.api.ApiRequestRules
import io.github.xbeeant.batiskit.api.DubboMappings
import io.github.xbeeant.batiskit.api.SpringApiService
import io.github.xbeeant.batiskit.api.SpringMappings
import io.github.xbeeant.batiskit.sequence.DiagramRoot
import io.github.xbeeant.batiskit.sequence.UastCallChainAnalyzer
import io.github.xbeeant.batiskit.sequence.showSequenceDiagram
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants

/**
 * API 接口列表面板：接口地址 + 搜索框 + HTTP 方法过滤 + 列表（双击跳转、右键复制）。
 *
 * 数据来自 [SpringApiService]，扫描完成前列表为空并给出提示。
 * 顶部接口地址由 [ContextPathResolver] 从 Spring 配置里探测 context path 后拼出，
 * 远端配置中心（Nacos 等）读不到时可在「设置接口地址…」里手动覆盖。
 */
class ApiListPanel(private val project: Project) : Disposable {

    private val search = SearchField("搜索路径 / 类名 / 方法名，空格分隔多个关键字", ::applyFilter)

    private val model = CollectionListModel<SpringApiService.Record>()

    private val list = JBList(model)

    /** 9 个方法从复选框行改为可按压 chip，收进 Advanced 折叠区 */
    private val methodChips = FilterChipGroup(METHODS, METHODS) { applyFilter() }

    private val sortBox = ComboBox(arrayOf("扫描顺序", "HTTP 方法", "路径 A→Z", "接口 A→Z"))

    private lateinit var shell: ToolWindowShell
    private lateinit var switcher: ContentSwitcher

    @Volatile
    private var address = ContextPathResolver.Address()

    private var all: List<SpringApiService.Record> = emptyList()

    private var scanned = false

    /** 同一个 listener 实例才能被 subscribe 去重、被 dispose 反注册 */
    private val onRecords: (List<SpringApiService.Record>) -> Unit = { records ->
        all = records
        scanned = true
        applyFilter()
    }

    val component: JComponent = createComponent()

    /** 订阅服务并触发一次后台扫描；之后源码变化由服务自动重扫并回调 */
    fun load() {
        refreshAddress()
        SpringApiService.getInstance(project).subscribe(onRecords)
    }

    override fun dispose() {
        if (!project.isDisposed) SpringApiService.getInstance(project).unsubscribe(onRecords)
    }

    // ------------------------------------------------------------------ 接口地址

    /**
     * 配置探测要读文件索引，放后台线程；手动覆盖时干脆不扫，省一次全工程文件名遍历。
     * [ContextPathResolver.detect] 内部会等到 smart mode + 读锁。
     */
    private fun refreshAddress() {
        val manual = ContextPathResolver.override(project)
        ApplicationManager.getApplication().executeOnPooledThread {
            val detected = if (manual.isNullOrBlank()) {
                runCatching { ContextPathResolver.detect(project) }
                    .getOrElse { ContextPathRules.Detection(note = "接口地址探测失败：${it.message}") }
            } else {
                ContextPathRules.Detection()
            }
            val next = ContextPathResolver.address(project, detected, manual)
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                address = next
                updateAddressBar()
            }
        }
    }

    /** 地址行升级为 Context Banner：地址 + context path + 来源 / 警告 + 同行「更改…」 */
    private fun updateAddressBar() {
        val current = address
        val detail = buildString {
            append(current.baseUrl)
            if (current.contextPath.isNotEmpty()) append("   ·   context path ").append(current.contextPath)
            if (current.manual) append("   ·   手动设置")
            if (current.candidates.size > 1) append("   ·   候选 ").append(current.candidates.size).append(" 个")
            current.note?.let { append("   ·   ").append(it) }
        }
        val heading = when {
            current.nacos -> "配置中心：请确认接口地址"
            current.note != null -> "接口地址探测异常"
            current.candidates.size > 1 -> "多个候选：请确认接口地址"
            else -> "接口地址"
        }
        val warn = !current.manual && (current.nacos || current.note != null || current.candidates.size > 1)
        shell.showBanner(
            if (warn) BannerPanel.Type.WARN else BannerPanel.Type.INFO,
            heading,
            detail,
            "更改…",
            ::openAddressDialog
        )
    }

    /** 远端（Nacos / Config Server）配置的 context path 静态读不到，留工程级手动覆盖入口 */
    private fun openAddressDialog() {
        val current = address
        val message = buildString {
            append("复制为 cURL / 完整 URL 时使用的接口地址（主机 + context path）。\n\n")
            append("当前：").append(current.baseUrl).append("\n")
            append("来源：").append(current.source).append("\n")
            current.note?.let { append("提示：").append(it).append("\n") }
            if (current.candidates.size > 1) {
                append("探测到的候选：").append(current.candidates.joinToString("、")).append("\n")
            }
            append("\nNacos / 配置中心里的 context path 静态读不到，请按实际部署填完整地址，")
            append("例如 http://localhost:8080/order-api 或 http://10.0.0.8:9000/order-api。\n")
            append("留空并确定 = 恢复自动探测。")
        }
        val input = Messages.showInputDialog(
            project,
            message,
            "设置接口地址",
            Messages.getQuestionIcon(),
            current.baseUrl,
            null
        ) ?: return
        ContextPathResolver.setOverride(project, input)
        refreshAddress()
        notify(
            if (input.isBlank()) {
                "已恢复自动探测接口地址"
            } else {
                "接口地址已设为 ${ContextPathRules.normalizeBaseUrl(input)}"
            },
            NotificationType.INFORMATION
        )
    }

    // ------------------------------------------------------------------ UI

    private fun createComponent(): JComponent {
        list.selectionMode = ListSelectionModel.SINGLE_SELECTION
        list.cellRenderer = ApiRenderer()
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && !javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    navigateSelected()
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showPopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showPopup(e)
            }
        })
        list.registerKeyboardAction(
            { navigateSelected() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED
        )

        val scroll = ScrollPaneFactory.createScrollPane(
            list,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        )
        switcher = ContentSwitcher(scroll)
        shell = ToolWindowShell("Spring API", switcher)

        shell.setActions(
            listOf(
                ToolWindowShell.Action("重新加载", "重新扫描工程里的 Spring / Dubbo 接口", ::load),
                ToolWindowShell.Action("设置接口地址…", "手动指定主机与 context path（Nacos 等远端配置适用）", ::openAddressDialog),
                ToolWindowShell.Action("重新探测地址", "清除手动覆盖，重新扫描工程里的 Spring 配置", ::redetectAddress)
            )
        )
        shell.setSearchField(search)

        sortBox.toolTipText = "列表排序方式，切换后当前过滤结果立即重排"
        sortBox.addActionListener { applyFilter() }
        val chipControls = JPanel(FlowLayout(FlowLayout.LEFT, UiTokens.scale(4), 0)).apply {
            isOpaque = false
            add(ActionLink("全选") { methodChips.setAll(true) })
            add(ActionLink("清空") { methodChips.setAll(false) })
            add(JBLabel("排序："))
            add(sortBox)
        }
        val advanced = JPanel(BorderLayout(0, Density.gap)).apply {
            isOpaque = false
            add(methodChips, BorderLayout.CENTER)
            add(chipControls, BorderLayout.SOUTH)
        }
        shell.setAdvanced("方法", advanced) { methodChips.activeCount() }
        shell.onClearFilters(::clearFilters)
        // 宽度变化后让渲染器按新宽度重算省略号，而不是硬切元信息
        shell.onNarrowChanged { list.revalidate(); list.repaint() }

        updateAddressBar()
        applyFilter()
        return shell.component
    }

    private fun redetectAddress() {
        ContextPathResolver.clearOverride(project)
        refreshAddress()
        notify("已清除手动覆盖，正在重新探测接口地址", NotificationType.INFORMATION)
    }

    private fun clearFilters() {
        search.text = ""
        methodChips.setAll(true)
    }

    // ------------------------------------------------------------------ 过滤 / 排序

    /** 扫描结果本身按方法 + 路径排过序，这里再给用户一次重排的机会（同值保持原顺序） */
    private fun sortRecords(records: List<SpringApiService.Record>): List<SpringApiService.Record> =
        when (sortBox.selectedIndex) {
            1 -> records.sortedWith(
                compareBy<SpringApiService.Record> { it.endpoint.httpMethod.uppercase() }
                    .thenBy { it.endpoint.path.lowercase() }
            )
            2 -> records.sortedBy { it.endpoint.path.lowercase() }
            3 -> records.sortedBy { it.endpoint.qualifiedLabel.lowercase() }
            else -> records
        }

    private fun enabledMethods(): Set<String> = methodChips.selected()

    private fun applyFilter() {
        val terms = ApiFilter.terms(search.text)
        val methods = enabledMethods()
        val filtered = sortRecords(all.filter { ApiFilter.matches(it.endpoint, terms, methods) })
        model.replaceAll(filtered)

        shell.setCount("共 ${all.size} 个接口")
        shell.setStatus(
            if (scanned) StatusBarPanel.State.IDLE else StatusBarPanel.State.BUSY,
            when {
                !scanned -> "正在扫描索引"
                filtered.size != all.size -> "已过滤"
                else -> "扫描完成"
            },
            "显示 ${filtered.size} / ${all.size}"
        )
        shell.refreshAdvancedToggle()
        when {
            all.isEmpty() && !scanned -> switcher.showEmpty(
                "正在扫描 Spring / Dubbo 接口…",
                "索引建立完成后会自动出现在这里"
            )

            all.isEmpty() -> switcher.showEmpty(
                "还没发现接口",
                "确认项目引入了 spring-web 或 dubbo，索引建立完成后重新加载",
                "重新加载", ::load
            )

            filtered.isEmpty() -> switcher.showEmpty(
                "没有匹配的接口",
                "换个关键词，或者把方法筛选恢复成全部",
                "清除筛选", ::clearFilters
            )

            else -> switcher.showContent()
        }
    }

    // ------------------------------------------------------------------ 交互

    private fun selectedRecord(): SpringApiService.Record? = list.selectedValue

    private fun navigateSelected() {
        val record = selectedRecord() ?: return
        navigate(record)
    }

    private fun navigate(record: SpringApiService.Record) {
        val method = record.pointer.element
        val file = method?.containingFile?.virtualFile
        if (method == null || file == null) {
            notify("接口已失效，请点「重新加载」", NotificationType.WARNING)
            return
        }
        OpenFileDescriptor(project, file, method.textOffset).navigate(true)
    }

    private fun showPopup(e: MouseEvent) {
        val index = list.locationToIndex(e.point)
        if (index < 0) {
            return
        }
        list.selectedIndex = index
        val record = list.model.getElementAt(index)

        val group = DefaultActionGroup()
        group.add(object : AnAction("复制路径") {
            override fun actionPerformed(event: AnActionEvent) {
                copy(record.endpoint.label)
            }
        })
        group.add(object : AnAction("复制「方法 + 路径」") {
            override fun actionPerformed(event: AnActionEvent) {
                copy(record.endpoint.qualifiedLabel)
            }
        })
        // 非 HTTP 接口（定时任务 / Dubbo）没有 URL / cURL 形态：给禁用项保持菜单结构稳定。
        // 参数信息只在点击菜单的这一刻从 PSI 指针按需解析（不加索引/缓存，常驻内存零开销）
        val params = runCatching<CurlBuilder.Params?> {
            ReadAction.compute<CurlBuilder.Params?, RuntimeException> {
                val element = record.pointer.element
                if (element is PsiMethod) CurlPsiReader.readParams(element) else null
            }
        }.getOrNull()
        // 地址里已经含自动探测 / 手动覆盖的 context path，cURL 与完整 URL 共用这一份
        val baseUrl = address.baseUrl
        val fullUrl = CurlBuilder.buildUrl(record.endpoint, baseUrl, params)
        val curlCommand = fullUrl?.let { CurlBuilder.build(record.endpoint, baseUrl, params) }
        group.add(object : AnAction("复制完整 URL") {
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = fullUrl != null
                event.presentation.description = if (fullUrl == null) {
                    "定时任务 / Dubbo 接口不是 HTTP 请求，没有 URL"
                } else {
                    "复制 $baseUrl 下的完整地址（含 context path）"
                }
            }

            override fun actionPerformed(event: AnActionEvent) {
                copy(fullUrl ?: return)
            }
        })
        group.add(object : AnAction("复制为 cURL") {
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = curlCommand != null
                event.presentation.description = if (curlCommand == null) {
                    "定时任务 / Dubbo 接口不是 HTTP 请求，无法生成 cURL"
                } else {
                    "当前接口地址 $baseUrl，粘贴后改成真实地址"
                }
            }

            override fun actionPerformed(event: AnActionEvent) {
                copy(curlCommand ?: return)
            }
        })
        // 接口调试闭环：扫描 / context path / cURL 都在，这里只差一次真实请求与另存 .http
        val initialSpec = ApiRequestRules.initialSpec(record.endpoint, baseUrl, params)
        group.add(object : AnAction("发送请求…") {
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = initialSpec != null
                event.presentation.description = if (initialSpec == null) {
                    "定时任务 / Dubbo 接口不是 HTTP 请求"
                } else {
                    "在 IDE 里试调该接口；可另存为 .http 交给原生 HTTP Client"
                }
            }

            override fun actionPerformed(event: AnActionEvent) {
                val spec = initialSpec ?: return
                ApiRequestDialog(project, spec, record.endpoint.qualifiedLabel).show()
            }
        })
        group.add(object : AnAction("设置接口地址…") {
            override fun update(event: AnActionEvent) {
                event.presentation.description = "手动指定主机与 context path（Nacos 等远端配置适用）"
            }

            override fun actionPerformed(event: AnActionEvent) {
                openAddressDialog()
            }
        })
        group.add(object : AnAction("重新探测接口地址") {
            override fun update(event: AnActionEvent) {
                event.presentation.description = "清除手动覆盖，重新扫描工程里的 Spring 配置"
            }

            override fun actionPerformed(event: AnActionEvent) {
                ContextPathResolver.clearOverride(project)
                refreshAddress()
                notify("已清除手动覆盖，正在重新探测接口地址", NotificationType.INFORMATION)
            }
        })
        group.add(Separator.getInstance())
        group.add(object : AnAction("跳转到声明") {
            override fun actionPerformed(event: AnActionEvent) {
                navigate(record)
            }
        })
        // 从 Controller 入口直接看跨层调用链：Java 走 PSI、Kotlin 走 UAST，共用同一个时序图入口
        val diagramRoot = runCatching {
            ReadAction.compute<DiagramRoot?, RuntimeException> {
                val element = record.pointer.element ?: return@compute null
                val isKotlin = element.containingFile?.name?.endsWith(".kt", ignoreCase = true) == true
                if (isKotlin) {
                    UastCallChainAnalyzer.rootAt(element)?.let { DiagramRoot.Uast(it) }
                } else {
                    (element as? PsiMethod)?.let { DiagramRoot.Java(it) }
                }
            }
        }.getOrNull()
        group.add(object : AnAction("生成时序图（调用链）") {
            override fun update(event: AnActionEvent) {
                event.presentation.isEnabled = diagramRoot != null
                event.presentation.description = if (diagramRoot == null) {
                    "当前接口不是可解析的 Java / Kotlin 方法"
                } else {
                    "从该方法出发生成 PSI / UAST 调用链时序图"
                }
            }

            override fun actionPerformed(event: AnActionEvent) {
                showSequenceDiagram(project, diagramRoot ?: return)
            }
        })

        JBPopupFactory.getInstance()
            .createActionGroupPopup(
                "API 接口",
                group,
                com.intellij.openapi.actionSystem.impl.SimpleDataContext.getProjectContext(project),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true
            )
            .show(RelativePoint(e.component, e.point))
    }

    private fun copy(text: String) {
        // cURL / 参数里可能带 password、token 之类的值，复制到剪贴板与通知前统一脱敏
        val safe = SensitiveData.maskText(text)
        CopyPasteManager.getInstance().setContents(StringSelection(safe))
        notify("已复制：$safe", NotificationType.INFORMATION)
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("API 接口", content, type)
            .notify(project)
    }

    /**
     * 列表绘制（两行布局，REST 与 Dubbo 通用）：
     *  - 第一行：方法名（按类型着色）+ 路径 / `类#方法` 定位文本；
     *  - 第二行：detail（@Scheduled 的 cron、@DubboService 的 version/group、@DubboReference 的接口类型）+ 声明位置 + 模块，
     *    用 HTML 按列表宽度自动换行，长接口 FQN / 长路径不再被截断。
     */
    private class ApiRenderer : JPanel(BorderLayout()), ListCellRenderer<SpringApiService.Record> {

        private val badge = MethodBadge()
        private val pathLabel = JBLabel()
        private val secondary = JBLabel()
        private val headline = JPanel(BorderLayout(UiTokens.scale(4), 0))

        init {
            isOpaque = true
            headline.isOpaque = false
            pathLabel.isOpaque = false
            pathLabel.font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
            pathLabel.foreground = UiTokens.textPrimary
            secondary.isOpaque = false
            secondary.font = UiTokens.fontSmall()
            secondary.foreground = UiTokens.textSecondary
            headline.add(badge, BorderLayout.WEST)
            headline.add(pathLabel, BorderLayout.CENTER)
            val lines = JPanel(GridLayout(2, 1)).apply {
                isOpaque = false
                add(headline)
                add(secondary)
            }
            add(lines, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out SpringApiService.Record>,
            value: SpringApiService.Record?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ): Component {
            val endpoint = value?.endpoint
            if (endpoint != null) {
                badge.badgeText = endpoint.httpMethod
                val pathWidth = (list.width - UiTokens.scale(36) - badge.preferredSize.width).coerceAtLeast(0)
                pathLabel.text = UiText.ellipsize(
                    endpoint.label.ifEmpty { "/" },
                    pathLabel.getFontMetrics(pathLabel.font),
                    pathWidth
                )
                val parts = buildList {
                    if (endpoint.detail.isNotEmpty()) add(endpoint.detail)
                    add("${endpoint.className}#${endpoint.methodName}")
                    if (endpoint.moduleName.isNotEmpty()) add("(${endpoint.moduleName})")
                }
                val full = parts.joinToString("   ·   ")
                secondary.text = UiText.ellipsize(
                    full,
                    secondary.getFontMetrics(secondary.font),
                    (list.width - UiTokens.scale(28)).coerceAtLeast(0)
                )
                secondary.toolTipText = full
            } else {
                badge.badgeText = ""
                pathLabel.text = ""
                secondary.text = ""
                secondary.toolTipText = null
            }
            background = if (selected) {
                UIUtil.getListSelectionBackground(hasFocus)
            } else {
                UIUtil.getListBackground()
            }
            foreground = UIUtil.getListForeground()
            // 选中用 2px 内边条 + 选中底色，不只靠背景色
            border = UiTokens.compound(
                UiTokens.emptyPx(Density.rowPadV, Density.rowPadH),
                UiTokens.line(if (selected) UiTokens.accent else background, left = 2)
            )
            // 行高固定；宽度交给 JBList 决定，避免渲染器把列表撑出横向滚动
            preferredSize = Dimension(0, Density.listRow)
            return this
        }
    }

    companion object {
        const val NOTIFICATION_GROUP = "Batis Kit API"

        /** 过滤用的选项；[SpringMappings.REQUEST] 是未指定 method 的 @RequestMapping */
        private val METHODS = listOf(
            "GET",
            "POST",
            "PUT",
            "DELETE",
            "PATCH",
            SpringMappings.REQUEST,
            SpringMappings.SCHEDULE,
            DubboMappings.DUBBO,
            DubboMappings.DUBBO_REFERENCE
        )
    }
}
