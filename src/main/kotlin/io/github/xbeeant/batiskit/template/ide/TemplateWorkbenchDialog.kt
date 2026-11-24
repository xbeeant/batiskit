package io.github.xbeeant.batiskit.template.ide

import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import io.github.xbeeant.batiskit.config.GenerationConfig
import io.github.xbeeant.batiskit.generate.GenContext
import io.github.xbeeant.batiskit.model.ColumnInfo
import io.github.xbeeant.batiskit.model.JavaType
import io.github.xbeeant.batiskit.model.TableInfo
import io.github.xbeeant.batiskit.template.TemplateCatalog
import io.github.xbeeant.batiskit.template.TemplateException
import io.github.xbeeant.batiskit.template.TemplateMigration
import io.github.xbeeant.batiskit.template.TemplateModel
import io.github.xbeeant.batiskit.api.ui.WrapLayout
import io.github.xbeeant.batiskit.template.TemplateRegistry
import io.github.xbeeant.batiskit.template.TemplateSetBundle
import io.github.xbeeant.batiskit.template.TemplateSettings
import io.github.xbeeant.batiskit.template.TemplateSpec
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * 模板工作台：选一份模板 → 改正文 → 立刻看到「用示例表渲染出来的产物」和「哪里写错了」。
 *
 * 以前写模板只能保存后跑一次生成，再看通知里的错误列表；这里把四个环节收进一个窗口：
 * 编辑、模型键 / 指令补全、废弃键与未定义变量校验、实时渲染预览。
 * 全部走现有的 [TemplateRegistry] / [TemplateModel]，不引入第二套引擎或 PSI 解析器定义。
 */
class TemplateWorkbenchDialog(private val project: Project) : DialogWrapper(project, true) {

    private val specs: List<TemplateSpec> = TemplateRegistry.all(project.basePath)
    private val templateCombo = ComboBox<String>()
    private val searchField = JBTextField()
    private val candidateCombo = ComboBox<String>()
    private val editorArea = codeArea()
    private val previewArea = codeArea().apply { isEditable = false }
    private val problemsArea = codeArea().apply { isEditable = false }
    private val status = JBLabel()

    init {
        title = "模板工作台 · Batis Kit"
        setOKButtonText("关闭")
        specs.forEach { templateCombo.addItem("${it.set}/${it.id} · ${it.name}") }
        templateCombo.addActionListener { loadSelected() }
        searchField.document.addDocumentListener(simpleListener { refreshCandidates() })
        editorArea.document.addDocumentListener(simpleListener { refresh() })
        candidateCombo.addActionListener { /* 选中候选不自动插入，等用户点按钮 */ }
        refreshCandidates()
        if (specs.isNotEmpty()) {
            templateCombo.selectedIndex = 0
            loadSelected()
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(1050, 640)
            minimumSize = Dimension(760, 440)
        }

        val toolbar = JPanel(BorderLayout(4, 0)).apply {
            val left = JPanel().apply {
                add(JBLabel("模板："))
                add(templateCombo)
            }
            add(left, BorderLayout.WEST)
            val right = JPanel(WrapLayout(FlowLayout.LEFT, 4, 2)).apply {
                add(JBLabel("补全："))
                add(searchField.apply { preferredSize = Dimension(90, 28) })
                add(candidateCombo.apply { preferredSize = Dimension(180, 28) })
                add(JButton("插入").apply { addActionListener { insertCandidate() } })
                add(JButton("检查全部模板").apply { addActionListener { checkAll() } })
                add(JButton("保存到全局模板").apply { addActionListener { saveToSettings() } })
                add(JButton("导出方案…").apply { addActionListener { exportSet() } })
                add(JButton("导入方案…").apply { addActionListener { importSet() } })
            }
            add(right, BorderLayout.EAST)
        }
        root.add(toolbar, BorderLayout.NORTH)

        val rightSide = JPanel(BorderLayout(0, 6)).apply {
            val previewPanel = JPanel(BorderLayout()).apply {
                add(JBLabel("实时渲染预览（示例表 t_user）").apply {
                    border = JBUI.Borders.emptyBottom(4)
                }, BorderLayout.NORTH)
                add(JBScrollPane(previewArea), BorderLayout.CENTER)
            }
            val problemPanel = JPanel(BorderLayout()).apply {
                add(JBLabel("校验结果（头部 / 废弃键 / 未定义变量 / 渲染错误）").apply {
                    border = JBUI.Borders.emptyBottom(4)
                }, BorderLayout.NORTH)
                add(JBScrollPane(problemsArea).apply { preferredSize = Dimension(0, 170) }, BorderLayout.CENTER)
            }
            add(JSplitPane(JSplitPane.VERTICAL_SPLIT, previewPanel, problemPanel).apply {
                dividerLocation = 330
                resizeWeight = 0.7
            }, BorderLayout.CENTER)
        }

        root.add(
            JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JBScrollPane(editorArea), rightSide).apply {
                dividerLocation = 500
                resizeWeight = 0.48
            },
            BorderLayout.CENTER
        )
        root.add(status, BorderLayout.SOUTH)
        return root
    }

    private fun loadSelected() {
        val spec = currentSpec() ?: return
        editorArea.text = specText(spec)
        editorArea.caretPosition = 0
        refresh()
    }

    /**
     * 用户模板存的是全文，直接取原文；内置模板用 [TemplateSpec.rawText] 按头部原文还原，
     * 注释、字段顺序与 group / order 都不会在「编辑 → 导出」途中丢掉。
     */
    private fun specText(spec: TemplateSpec): String {
        TemplateSettings.getInstance().state.templates
            .firstOrNull { it.id == spec.id }
            ?.let { return it.text }
        return spec.rawText()
    }

    private fun currentSpec(): TemplateSpec? = specs.getOrNull(templateCombo.selectedIndex)

    /** 每次编辑都重跑一遍：头部解析 + 废弃键 + 未定义变量 + 用示例表渲染 */
    private fun refresh() {
        val spec = currentSpec()
        val text = editorArea.text
        val parsed = TemplateSpec.parse(text, spec?.fileName ?: "draft.tpl", builtin = false)
        val problems = mutableListOf<String>()
        parsed.error?.let { problems += "头部：$it" }
        TemplateMigration.versionWarning(parsed.spec)?.let { problems += it }
        TemplateMigration.scan(parsed.spec.body).forEach { problems += it.message }
        TemplateCatalog.unknownRoots(parsed.spec.body).forEach { (line, name) ->
            problems += "第 $line 行：未定义的模型根名字 $name（检查拼写，或先在头部 #set）"
        }

        val config = GenerationConfig()
        val model = try {
            TemplateModel.build(sampleTable(), GenContext(config))
        } catch (e: Exception) {
            problems += "示例表模型构建失败：${e.message}"
            null
        }
        val rendered = if (model != null && parsed.error == null) {
            try {
                TemplateRegistry.render(parsed.spec, model)
            } catch (e: TemplateException) {
                problems += "渲染错误：${e.message}"
                null
            }
        } else null

        previewArea.text = if (rendered == null) {
            "（当前模板无法渲染，修好左侧问题后会自动刷新）"
        } else {
            "output: ${rendered.path}\n\n${rendered.text}"
        }
        previewArea.caretPosition = 0
        problemsArea.text = if (problems.isEmpty()) {
            "✓ 头部合法、没有已下线的模型键，示例表渲染通过"
        } else {
            problems.joinToString("\n") { "· $it" }
        }
        problemsArea.caretPosition = 0
        status.text = "模板 ${templateCombo.selectedItem} · 模型版本 ${TemplateMigration.CURRENT_VERSION} · " +
            "${problems.size} 个问题 · 预览用的是示例表，不是真实数据库表"
    }

    private fun refreshCandidates() {
        val items = TemplateCatalog.candidates(searchField.text)
        candidateCombo.removeAllItems()
        items.forEach { candidateCombo.addItem(it.first) }
    }

    private fun insertCandidate() {
        val value = candidateCombo.selectedItem as? String ?: return
        editorArea.insert(value, editorArea.caretPosition)
        editorArea.requestFocus()
    }

    /** 存进 IDE 全局设置：与内置模板同 id 时覆盖内置，下次生成即生效 */
    private fun saveToSettings() {
        val spec = currentSpec() ?: return
        val text = editorArea.text
        val parsed = TemplateSpec.parse(text, spec.fileName, builtin = false)
        if (parsed.error != null) {
            Messages.showWarningDialog(project, "模板还有问题，先修好再保存：\n${parsed.error}", "模板工作台")
            return
        }
        val state = TemplateSettings.getInstance().state
        val existing = state.templates.firstOrNull { it.id == parsed.spec.id }
        if (existing != null) {
            existing.text = text
            existing.name = parsed.spec.name
        } else {
            state.templates.add(TemplateSettings.UserTemplate().apply {
                id = parsed.spec.id
                name = parsed.spec.name
                this.text = text
                enabled = true
            })
        }
        Messages.showInfoMessage(
            project,
            "已保存到全局模板（${parsed.spec.id}）。与内置模板同 id 时会覆盖内置版本，下次生成即生效。",
            "模板工作台"
        )
    }

    /** 把一整套方案的全部模板全文打包成一个 XML，便于发给同事 / 放进版本库 */
    private fun exportSet() {
        val available = TemplateRegistry.sets(project.basePath).map { it.id }
        val initial = currentSpec()?.set?.takeIf { it.isNotBlank() } ?: available.firstOrNull().orEmpty()
        val chosen = JOptionPane.showInputDialog(
            null as Component?,
            "导出哪套方案？当前可用：${available.joinToString("、").ifBlank { "（无）" }}",
            initial
        )?.trim().orEmpty()
        if (chosen.isEmpty()) return
        val items = TemplateRegistry.forSet(chosen, project.basePath).map { spec ->
            TemplateSetBundle.Item(spec.id, spec.name, specText(spec))
        }
        if (items.isEmpty()) {
            Messages.showWarningDialog(project, "方案「$chosen」里没有模板。", "模板工作台")
            return
        }
        val descriptor = FileSaverDescriptor("导出模板方案", "把整套模板保存为一个 XML 文件", "xml")
        val wrapper = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(null as VirtualFile?, "$chosen.templateset.xml") ?: return
        val outcome = runCatching {
            FileUtil.writeToFile(wrapper.file, TemplateSetBundle.encode(chosen, items))
        }
        if (outcome.isSuccess) {
            Messages.showInfoMessage(
                project,
                "已导出 ${items.size} 个模板到 ${wrapper.file.absolutePath}",
                "模板工作台"
            )
        } else {
            Messages.showWarningDialog(
                project, "写入失败：${outcome.exceptionOrNull()?.message ?: "未知错误"}", "模板工作台"
            )
        }
    }

    /** 导回成一套自包含的全局用户模板：头部注入 `set:`，列表键带方案前缀，不碰内置模板 */
    private fun importSet() {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false)
            .withTitle("导入模板方案")
            .withDescription("选择之前导出的模板方案 XML")
        val file = FileChooser.chooseFile(descriptor, project, null as VirtualFile?) ?: return
        val text = runCatching { VfsUtil.virtualToIoFile(file).readText(Charsets.UTF_8) }
            .getOrElse {
                Messages.showWarningDialog(project, "读取失败：${it.message}", "模板工作台")
                return
            }
        val bundle = runCatching { TemplateSetBundle.decode(text) }
            .getOrElse {
                Messages.showWarningDialog(project, it.message ?: "解析失败", "模板工作台")
                return
            }
        val problems = TemplateSetBundle.validate(bundle)
        if (problems.isNotEmpty()) {
            Messages.showWarningDialog(
                project,
                "方案不能导入：\n" + problems.joinToString("\n") { "· $it" },
                "模板工作台"
            )
            return
        }

        val state = TemplateSettings.getInstance().state
        var target = bundle.set
        if (state.templates.any { it.id.startsWith("$target/") }) {
            val answer = JOptionPane.showConfirmDialog(
                null as Component?,
                "方案「$target」已存在，覆盖它现有的用户模板？\n选「否」会导入为一个新方案。",
                "导入模板方案",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
            )
            if (answer == JOptionPane.YES_OPTION) {
                state.templates.removeIf { it.id.startsWith("$target/") }
            } else {
                var index = 2
                while (state.templates.any { it.id.startsWith("${bundle.set}-$index/") }) index++
                target = "${bundle.set}-$index"
            }
        }
        bundle.items.forEach { item ->
            val entry = TemplateSettings.UserTemplate()
            entry.id = TemplateSetBundle.keyOf(target, item.id)
            entry.name = item.name
            entry.text = TemplateSetBundle.withSet(item.text, target)
            entry.enabled = true
            state.templates.add(entry)
        }
        Messages.showInfoMessage(
            project,
            "已导入 ${bundle.items.size} 个模板到全局方案「$target」。重新打开生成弹窗即可在方案下拉里选到它。",
            "模板工作台"
        )
    }

    /** 升级体检：一次扫完内置 / 全局 / 项目三层模板，列出废弃键与拼写可疑的根名字 */
    private fun checkAll() {
        val report = StringBuilder()
        var total = 0
        specs.forEach { spec ->
            val lines = TemplateMigration.inspect(spec).toMutableList()
            TemplateCatalog.unknownRoots(spec.body).forEach { (line, name) ->
                lines += "${spec.fileName} 第 $line 行：未定义的模型根名字 $name"
            }
            if (lines.isNotEmpty()) {
                report.append("【").append(spec.set).append('/').append(spec.id).append("】\n")
                lines.forEach { report.append("  · ").append(it).append('\n') }
                total += lines.size
                report.append('\n')
            }
        }
        val text = if (total == 0) {
            "✓ 全部 ${specs.size} 个模板通过检查：没有已下线的模型键，也没有可疑的根名字"
        } else {
            report.insert(0, "共 ${specs.size} 个模板，$total 处待处理：\n\n").toString()
        }
        Messages.showInfoMessage(project, text, "模板兼容性检查")
    }

    private fun sampleTable(): TableInfo = TableInfo(
        tableName = "t_user",
        entityName = "User",
        comment = "用户表",
        baseName = "User",
        columns = listOf(
            ColumnInfo("id", "id", "bigint", JavaType("Long", null, "BIGINT"), "主键", true, true, false),
            ColumnInfo("user_name", "userName", "varchar(64)", JavaType("String", null, "VARCHAR"), "用户名", false, false, false),
            ColumnInfo(
                "create_at", "createAt", "datetime",
                JavaType("LocalDateTime", "java.time.LocalDateTime", "TIMESTAMP"), "创建时间", false, false, true
            )
        )
    )

    private fun codeArea(): JBTextArea = JBTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        lineWrap = false
        isEditable = true
    }

    private fun simpleListener(block: () -> Unit) = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = block()
        override fun removeUpdate(e: DocumentEvent) = block()
        override fun changedUpdate(e: DocumentEvent) = block()
    }
}
