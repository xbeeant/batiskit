package io.github.xbeeant.batiskit.sequence

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.components.*
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import io.github.xbeeant.batiskit.sequence.SequenceDiagramSettings
import java.awt.*
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.awt.event.ItemListener
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.*
import javax.swing.event.ChangeListener
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 编辑器右键「生成时序图（Sequence Diagram）」：光标放在一个 Java / Kotlin 方法内，
 * 从该方法出发做调用链分析并弹出时序图（对标原版 SequencePlugin 的 `Alt S` / `⌥S`）。
 * Java 走 PSI 分析器，Kotlin 走 UAST 分析器，两者共用同一份模型与对话框。
 */
class SequenceDiagramAction :
    AnAction("生成时序图（Sequence Diagram）", "从光标所在方法生成调用链时序图，可导出 SVG / PlantUML / Mermaid",
        AllIcons.Toolwindows.ToolWindowHierarchy) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val root = rootAt(event)
        event.presentation.isEnabledAndVisible = root != null
        if (root != null) {
            event.presentation.text = "生成时序图（${root.displayName}）"
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val root = rootAt(event) ?: return
        showSequenceDiagram(project, root)
    }

    /** 光标所在的 Java 方法或 Kotlin / UAST 方法；两者都找不到时菜单项隐藏 */
    private fun rootAt(event: AnActionEvent): DiagramRoot? {
        val project = event.project ?: return null
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return null
        // update() 跑在 BGT，文档 / caret / PSI 的读取必须放进 read action；
        // actionPerformed 在 EDT 上复用同一入口，包一层读动作也不会改变行为。
        return ReadAction.compute<DiagramRoot?, RuntimeException> {
            val file = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return@compute null
            val element = file.findElementAt(editor.caretModel.offset) ?: return@compute null
            // Java 优先：保持原来的 PSI 分析器与行为
            PsiTreeUtil.getParentOfType(element, PsiMethod::class.java, false)?.let {
                return@compute DiagramRoot.Java(it)
            }
            // Kotlin（以及其它 UAST 语言）：从光标元素逐级向上找 UMethod
            UastCallChainAnalyzer.rootAt(element)?.let { return@compute DiagramRoot.Uast(it) }
            null
        }
    }
}

/**
 * 生成时序图的公共入口：编辑器右键与 Spring API 面板右键共用。
 * PSI 分析放后台读动作，完成后在 EDT 弹对话框（大方法不会冻结 UI）。
 */
fun showSequenceDiagram(project: Project, method: PsiMethod) =
    showSequenceDiagram(project, DiagramRoot.Java(method))

/** 语言无关入口：Java / Kotlin 起点都走这里，对话框只认 [DiagramRoot] */
fun showSequenceDiagram(project: Project, root: DiagramRoot) {
    // 深度 / 开关按工程记忆，基础类名单是 IDE 级设置：每次打开都现读，改完设置不用重启 IDE
    val options = SequenceDiagramOptionsService.getInstance(project).load()
        .copy(basicClassRules = SequenceDiagramSettings.getInstance().rules())
    ProgressManager.getInstance().run(object : Task.Backgroundable(project, "分析调用链…", true) {
        private var diagram: SequenceDiagram? = null

        override fun run(indicator: ProgressIndicator) {
            diagram = ReadAction.compute<SequenceDiagram, RuntimeException> {
                root.analyze(project, options)
            }
        }

        override fun onSuccess() {
            diagram?.let { SequenceDiagramDialog(project, root, it, options).show() }
        }
    })
}

/** 上次使用的生成选项（工程级 `PropertiesComponent`，对话框「关闭」时保存） */
@Service(Service.Level.PROJECT)
class SequenceDiagramOptionsService(private val project: Project) {

    fun load(): DiagramOptions =
        DiagramOptions.fromName(PropertiesComponent.getInstance(project).getValue(PREF_KEY))

    fun save(options: DiagramOptions) {
        PropertiesComponent.getInstance(project).setValue(PREF_KEY, options.toName())
    }

    companion object {
        private const val PREF_KEY = "batiskit.sequence.diagram.options"
        fun getInstance(project: Project): SequenceDiagramOptionsService = project.service()
    }
}

/**
 * 时序图对话框：顶部一排选项（改动即时重新分析），中间三个页签——图形 / PlantUML / Mermaid，
 * 左下导出按钮。选项在对话框关闭时记进工程设置，下次打开自动带回。
 * 「追踪到数据库层」控制 MyBatis / MyBatis-Plus 的 Mapper 调用是否继续画到 SQL 与表。
 */
class SequenceDiagramDialog(
    private val project: Project,
    private val root: DiagramRoot,
    diagram: SequenceDiagram,
    private var options: DiagramOptions,
) : DialogWrapper(project) {

    private var diagram: SequenceDiagram = diagram
    private var panel: SequenceDiagramPanel = SequenceDiagramPanel(diagram, project)
    private var tabs: JBTabbedPane? = null
    private val plantUmlArea = textArea()
    private val mermaidArea = textArea()
    private val status = JBLabel(" ")
    /** 选项改动频繁（Spinner / CheckBox），防抖后再起后台分析，连点不会排队跑多轮 */
    private val debounce = javax.swing.Timer(220) { recomputeInBackground() }.apply { isRepeats = false }
    private var analysisSequence = 0L
    private var closed = false
    private val depthSpinner = JSpinner(SpinnerNumberModel(options.maxDepth, 1, 10, 1))
    /** 默认不限消息数：勾选时 [messagesSpinner] 禁用，[currentOptions] 写 0 */
    private val unlimitedBox = JBCheckBox("不限消息数",
        options.maxMessages <= DiagramOptions.NO_MAX_MESSAGES).apply {
        toolTipText = "默认不限制消息条数，调用链完整展开；取消勾选后可在右侧输入上限"
    }
    /** 消息上限：选项记忆串的第 7 段，0 = 不限；不限时仍显示一个可改的备用值 */
    private val messagesSpinner = JSpinner(
        SpinnerNumberModel(
            (if (options.maxMessages > DiagramOptions.NO_MAX_MESSAGES) options.maxMessages else 300)
                .coerceIn(DiagramOptions.MIN_MAX_MESSAGES, DiagramOptions.MAX_MAX_MESSAGES),
            DiagramOptions.MIN_MAX_MESSAGES,
            DiagramOptions.MAX_MAX_MESSAGES,
            50,
        ),
    )
    /** createCenterPanel 里造出来的内容根，用来反查底层 AWT 窗口（不依赖平台版本特有的 API） */
    private var contentRoot: JPanel? = null
    private var maximizeAction: DialogWrapperAction? = null
    /** 最大化之前的窗口 bounds，点「还原窗口」时恢复 */
    private var savedBounds: Rectangle? = null
    private val trivialBox = JBCheckBox("跳过 getter/setter 与日志", options.skipTrivial)
    private val basicBox = JBCheckBox("跳过基础类（JDK/集合/工具类）", options.skipBasicClasses).apply {
        toolTipText = "基础类与工具类的调用连箭头和生命线都不画；具体名单在 Settings → Tools → Batis Kit → 时序图 里编辑"
    }
    private val smartBox = JBCheckBox("智能接口（唯一实现自动展开）", options.smartInterface)
    private val projectBox = JBCheckBox("只展开本工程代码", options.projectOnly)
    private val returnBox = JBCheckBox("显示返回箭头", options.showReturns)
    private val databaseBox = JBCheckBox("追踪到数据库层", options.traceDatabase).apply {
        toolTipText = "MyBatis / MyBatis-Plus 工程：Mapper 方法继续画到 SQL 与数据库表，双击箭头跳语句、双击表头跳 Database 视图"
    }

    init {
        title = "时序图 — ${root.displayName}"
        setOKButtonText("关闭")
        isModal = false
        init()
        // init() 里已经建好底层 AWT 窗口；等 EDT 队列里 show() 也走完再开口子 / 恢复尺寸，
        // 免得平台随后 pack() 把我们设的 bounds 覆盖掉
        SwingUtilities.invokeLater { configureWindow() }
        refreshTexts()
    }

    override fun createCenterPanel(): JComponent {
        // FlowLayout 会自动换行：窗口缩小时多出来的选项不会像 BoxLayout 那样被裁掉
        val optionsRow = JPanel(FlowLayout(FlowLayout.LEFT, 10, 2))
        optionsRow.border = JBUI.Borders.empty(4, 4, 8, 4)
        optionsRow.add(JBLabel("深度上限："))
        depthSpinner.preferredSize = Dimension(56, depthSpinner.preferredSize.height)
        optionsRow.add(depthSpinner)
        optionsRow.add(JBLabel("消息上限："))
        messagesSpinner.preferredSize = Dimension(76, messagesSpinner.preferredSize.height)
        messagesSpinner.toolTipText = "取消「不限消息数」后生效：消息条数达到上限就截断并补备注（10–10000）"
        optionsRow.add(unlimitedBox)
        optionsRow.add(messagesSpinner)
        listOf(trivialBox, basicBox, smartBox, projectBox, returnBox, databaseBox).forEach { optionsRow.add(it) }
        // 明确的重绘入口：改完上限不用等失焦 / 回车，点一下立即按当前选项重新分析
        optionsRow.add(JButton("重新生成").apply {
            toolTipText = "按当前选项立即重新分析并重绘（改动消息上限后可直接点这里）"
            addActionListener { recomputeNow() }
        })

        val root = JPanel(BorderLayout())
        root.border = JBUI.Borders.empty(8)
        val north = JPanel(BorderLayout())
        north.add(optionsRow, BorderLayout.CENTER)
        status.border = BorderFactory.createEmptyBorder(0, 4, 2, 4)
        north.add(status, BorderLayout.SOUTH)
        root.add(north, BorderLayout.NORTH)
        root.add(tabs(), BorderLayout.CENTER)
        root.preferredSize = Dimension(1000, 640)
        contentRoot = root

        // 选项改动即时重新分析（深度 / 消息上限 / 七个开关）
        val relisten = ChangeListener { recompute() }
        depthSpinner.addChangeListener(relisten)
        messagesSpinner.addChangeListener(relisten)
        val itemListen = ItemListener { syncSpinnerEnabled(); recompute() }
        listOf(unlimitedBox, trivialBox, basicBox, smartBox, projectBox, returnBox, databaseBox)
            .forEach { it.addItemListener(itemListen) }
        // JSpinner 手输数字不会即时 commit（要回车 / 失焦才发 stateChanged），以前改完上限
        // 看不到重绘就差在这里：补文档监听「边输边防抖重绘」，回车立即提交并重绘。
        val spinnerField = (messagesSpinner.editor as? JSpinner.DefaultEditor)?.textField
        spinnerField?.document?.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = recompute()
            override fun removeUpdate(e: DocumentEvent) = recompute()
            override fun changedUpdate(e: DocumentEvent) = recompute()
        })
        spinnerField?.addActionListener {
            runCatching { messagesSpinner.commitEdit() }
            recomputeNow()
        }
        syncSpinnerEnabled()
        return root
    }

    /** 勾了「不限消息数」就把上限输入框置灰，避免显示一个并不生效的数字误导用户 */
    private fun syncSpinnerEnabled() {
        messagesSpinner.isEnabled = !unlimitedBox.isSelected
    }

    private fun tabs(): JComponent {
        val tabs = JBTabbedPane()
        val scroll = JBScrollPane(panel)
        scroll.verticalScrollBar.unitIncrement = 24
        tabs.addTab("图形", scroll)
        tabs.addTab("PlantUML", JBScrollPane(plantUmlArea))
        tabs.addTab("Mermaid", JBScrollPane(mermaidArea))
        this.tabs = tabs
        return tabs
    }

    private fun textArea() = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        font = JBFont.create(java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 13))
    }

    private fun refreshTexts() {
        plantUmlArea.text = SequenceTextGenerator.plantUml(diagram)
        mermaidArea.text = SequenceTextGenerator.mermaid(diagram)
        plantUmlArea.caretPosition = 0
        mermaidArea.caretPosition = 0
        status.text = buildString {
            append("参与者 ${diagram.participants.size} 个 · 消息 ${diagram.messages.size} 条")
            val tables = diagram.participants.count { it.isDatabase }
            if (tables > 0) append(" · 数据库表 $tables 张")
            if (diagram.truncated) append(" · 已达消息上限（${currentOptions().maxMessages} 条），剩余调用被截断")
        }
    }

    private fun currentOptions(): DiagramOptions = DiagramOptions(
        maxDepth = depthSpinner.value as Int,
        skipTrivial = trivialBox.isSelected,
        skipBasicClasses = basicBox.isSelected,
        smartInterface = smartBox.isSelected,
        projectOnly = projectBox.isSelected,
        showReturns = returnBox.isSelected,
        traceDatabase = databaseBox.isSelected,
        maxMessages = if (unlimitedBox.isSelected) DiagramOptions.NO_MAX_MESSAGES
        else (messagesSpinner.value as Number).toInt()
            .coerceIn(DiagramOptions.MIN_MAX_MESSAGES, DiagramOptions.MAX_MAX_MESSAGES),
        // 对话框开着时也能改设置页：每次重算现读名单，点「重新生成」立即生效
        basicClassRules = SequenceDiagramSettings.getInstance().rules(),
    )

    /** 只在 EDT 上排一个防抖请求，真正的 PSI 分析交给后台任务 */
    private fun recompute() {
        debounce.restart()
    }

    /** 跳过防抖立即重算：给「重新生成」按钮与输入框回车用，改完消息上限马上能看到结果 */
    private fun recomputeNow() {
        debounce.stop()
        recomputeInBackground()
    }

    private fun recomputeInBackground() {
        if (closed) return
        options = currentOptions()
        val seq = ++analysisSequence
        status.text = "正在重新分析调用链…"
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "重新分析调用链…", true) {
            private var result: SequenceDiagram? = null

            override fun run(indicator: ProgressIndicator) {
                result = ReadAction.compute<SequenceDiagram, RuntimeException> {
                    root.analyze(project, options)
                }
            }

            override fun onSuccess() {
                val next = result ?: return
                // 过期结果 / 对话框已关闭：直接丢弃，不能覆盖更新的图
                if (closed || seq != analysisSequence) return
                diagram = next
                panel = SequenceDiagramPanel(next, project)
                val scroll = JBScrollPane(panel)
                scroll.verticalScrollBar.unitIncrement = 24
                tabs?.setComponentAt(0, scroll)
                refreshTexts()
            }
        })
    }

    override fun doOKAction() {
        debounce.stop()
        closed = true
        SequenceDiagramOptionsService.getInstance(project).save(options)
        saveWindowBounds()
        super.doOKAction()
    }

    override fun doCancelAction() {
        debounce.stop()
        closed = true
        SequenceDiagramOptionsService.getInstance(project).save(options)
        saveWindowBounds()
        super.doCancelAction()
    }

    override fun createActions(): Array<DialogWrapper.DialogWrapperAction> {
        val saveAction = object : DialogWrapperAction("保存…") {
            override fun doAction(e: ActionEvent?) {
                saveCurrent()
            }
        }
        val savePngAction = object : DialogWrapperAction("保存 PNG…") {
            override fun doAction(e: ActionEvent?) {
                savePng()
            }
        }
        val copyPuml = object : DialogWrapperAction("复制 PlantUML") {
            override fun doAction(e: ActionEvent?) {
                copy(SequenceTextGenerator.plantUml(diagram), "PlantUML")
            }
        }
        val copyMermaid = object : DialogWrapperAction("复制 Mermaid") {
            override fun doAction(e: ActionEvent?) {
                copy(SequenceTextGenerator.mermaid(diagram), "Mermaid")
            }
        }
        val maximize = object : DialogWrapperAction("最大化") {
            override fun doAction(e: ActionEvent?) {
                toggleMaximize()
            }
        }
        maximizeAction = maximize
        @Suppress("UNCHECKED_CAST")
        return arrayOf(saveAction, savePngAction, copyPuml, copyMermaid, maximize,
            myOKAction as DialogWrapper.DialogWrapperAction)
    }

    // ---------- 窗口：可缩放 / 最大化 / 尺寸记忆 ----------

    /**
     * `DialogWrapper` 建出来的 dialog 默认 `resizable = false`，标题栏没有最大化按钮、拖边框也没用。
     * 这里只碰 AWT 层（JDialog 或 JFrame 都覆盖），不去猜 241 平台里版本差异较大的窗口 API。
     */
    private fun configureWindow() {
        val window = contentRoot?.let { SwingUtilities.getWindowAncestor(it) } ?: return
        runCatching {
            when (window) {
                is Dialog -> window.isResizable = true
                is Frame -> window.isResizable = true
            }
            restoreWindowBounds(window)
        }
    }

    /** 显式按钮：JDialog 没有 `setExtendedState`，用「铺满可用屏幕 + 记住原尺寸」实现最大化 / 还原 */
    private fun toggleMaximize() {
        val window = contentRoot?.let { SwingUtilities.getWindowAncestor(it) } ?: return
        runCatching {
            val previous = savedBounds
            if (previous == null) {
                savedBounds = window.bounds
                window.bounds = usableScreenBounds(window)
                maximizeAction?.putValue(Action.NAME, "还原窗口")
            } else {
                window.bounds = previous
                savedBounds = null
                maximizeAction?.putValue(Action.NAME, "最大化")
            }
        }
    }

    /** 窗口所在显示器去掉任务栏 / 菜单栏后的可用区域；insets 由默认显示器的 maximumWindowBounds 推出来 */
    private fun usableScreenBounds(window: Window): Rectangle {
        val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val screen = (window.graphicsConfiguration ?: env.defaultScreenDevice.defaultConfiguration).bounds
        val max = env.maximumWindowBounds
        val defaultScreen = env.defaultScreenDevice.defaultConfiguration.bounds
        val left = (max.x - defaultScreen.x).coerceAtLeast(0)
        val top = (max.y - defaultScreen.y).coerceAtLeast(0)
        val right = (defaultScreen.x + defaultScreen.width - max.x - max.width).coerceAtLeast(0)
        val bottom = (defaultScreen.y + defaultScreen.height - max.y - max.height).coerceAtLeast(0)
        return Rectangle(
            screen.x + left, screen.y + top,
            (screen.width - left - right).coerceAtLeast(400),
            (screen.height - top - bottom).coerceAtLeast(300),
        )
    }

    private fun saveWindowBounds() {
        val window = contentRoot?.let { SwingUtilities.getWindowAncestor(it) } ?: return
        runCatching {
            val b = window.bounds
            PropertiesComponent.getInstance(project).setValue(
                WINDOW_BOUNDS_KEY, listOf(b.x, b.y, b.width, b.height).joinToString(","),
            )
        }
    }

    /** 最大化后保存的是铺满屏幕的 bounds，下次打开自然还是最大化状态 */
    private fun restoreWindowBounds(window: Window) {
        val raw = PropertiesComponent.getInstance(project).getValue(WINDOW_BOUNDS_KEY) ?: return
        val parts = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (parts.size != 4) return
        val (x, y, w, h) = parts
        if (w < 400 || h < 300 || w > 10000 || h > 10000) return
        val rect = Rectangle(x, y, w, h)
        // 换显示器 / 改分辨率后旧坐标可能整个落在屏幕外，这种情况别把窗口「恢复」到看不见的地方
        val visible = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { device ->
            device.configurations.any { it.bounds.intersects(rect) }
        }
        if (visible) window.bounds = rect
    }

    companion object {
        private const val WINDOW_BOUNDS_KEY = "batiskit.sequence.diagram.window.bounds"
    }

    /** 统一保存入口：按当前选中页签决定内容与扩展名（图形→SVG / PlantUML→puml / Mermaid→mmd） */
    private fun saveCurrent() {
        val (extension, description, content) = when (tabs?.selectedIndex ?: 0) {
            1 -> Triple("puml", "PlantUML 文本", plantUmlArea.text)
            2 -> Triple("mmd", "Mermaid 文本", mermaidArea.text)
            else -> Triple("svg", "SVG 图片", SvgRenderer.render(SequenceLayout(diagram), diagram))
        }
        val descriptor = FileSaverDescriptor("保存时序图", "把当前页签导出为$description", extension)
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(null as VirtualFile?, "${root.displayName}.$extension") ?: return
        val outcome = runCatching { FileUtil.writeToFile(wrapper.file, content) }
        status.text = outcome.fold(
            onSuccess = { "已保存 ${wrapper.file.name}" },
            onFailure = { "保存失败：${it.message ?: "未知错误"}" },
        )
        outcome.exceptionOrNull()?.let {
            com.intellij.openapi.diagnostic.Logger.getInstance(SequenceDiagramDialog::class.java)
                .warn("保存时序图失败", it)
        }
    }

    /** 把图形页签的 Swing 面板离屏画到 BufferedImage，再用 ImageIO 写出（不引 Batik 等第三方库） */
    private fun savePng() {
        val descriptor = FileSaverDescriptor("保存时序图", "把图形页签导出为 PNG 图片", "png")
        val wrapper = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project)
            .save(null as VirtualFile?, "${root.displayName}.png") ?: return
        val outcome = runCatching {
            if (!ImageIO.write(renderPanelImage(), "png", wrapper.file)) {
                throw IllegalStateException("当前 JRE 没有 PNG 编码器")
            }
        }
        status.text = outcome.fold(
            onSuccess = { "已保存 ${wrapper.file.name}" },
            onFailure = { "保存失败：${it.message ?: "未知错误"}" },
        )
        outcome.exceptionOrNull()?.let {
            com.intellij.openapi.diagnostic.Logger.getInstance(SequenceDiagramDialog::class.java)
                .warn("保存时序图 PNG 失败", it)
        }
    }

    private fun renderPanelImage(): BufferedImage {
        // 用一份离屏副本：直接改屏幕上 panel 的 size 会打乱 scrollpane 的布局
        val offscreen = SequenceDiagramPanel(diagram, project)
        val size: Dimension = offscreen.preferredSize
        val width = size.width.coerceAtLeast(1)
        val height = size.height.coerceAtLeast(1)
        offscreen.setSize(width, height)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics() as Graphics2D
        try {
            // 面板非 opaque、自己不画背景；RGB 图初始是黑的，按 SVG 导出的口径铺白底
            g.color = Color.WHITE
            g.fillRect(0, 0, width, height)
            offscreen.paint(g)
        } finally {
            g.dispose()
        }
        return image
    }

    private fun copy(text: String, what: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        status.text = "已复制 $what"
    }
}
