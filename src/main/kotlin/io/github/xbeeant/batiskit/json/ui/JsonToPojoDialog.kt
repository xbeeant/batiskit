package io.github.xbeeant.batiskit.json.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.psi.PsiDirectory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.table.JBTable
import io.github.xbeeant.batiskit.json.GeneratedJavaFile
import io.github.xbeeant.batiskit.json.InferenceResult
import io.github.xbeeant.batiskit.json.JsonInferrer
import io.github.xbeeant.batiskit.json.JsonNames
import io.github.xbeeant.batiskit.json.JsonParser
import io.github.xbeeant.batiskit.json.JsonRenderer
import io.github.xbeeant.batiskit.json.JsonSettings
import io.github.xbeeant.batiskit.json.ClassModel
import io.github.xbeeant.batiskit.json.FieldModel
import io.github.xbeeant.batiskit.json.JsonType
import io.github.xbeeant.batiskit.ui.TableSearchSupport
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.DataFlavor
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.AbstractTableModel

/** 弹窗确认后交给 Action 的执行计划 */
class JsonGenerationPlan(
    val settings: JsonSettings,
    val directory: PsiDirectory,
    val files: List<GeneratedJavaFile>,
    val warnings: List<String>,
    val sampleCount: Int
)

/**
 * 「JSON → Java 对象」对话框：左边贴 JSON，中间是可编辑的字段表，右边实时预览生成的代码。
 *
 * 与 GsonFormatPlus 最大的体验差别在这里：
 *  - 一次能贴多份响应样本，字段取并集，可选字段与类型冲突直接列在表里；
 *  - 字段可以逐条勾选 / 改名 / 改类型，改完预览立刻跟着变，不用重新走一遍向导；
 *  - 预览就是最终会写进文件的文本（含 package / import / 注解），所见即所得。
 */
class JsonToPojoDialog(
    project: Project,
    initialText: String,
    initialPackage: String,
    private val directory: PsiDirectory,
    private val initialSettings: JsonSettings
) : DialogWrapper(project, true) {

    private val jsonArea = JBTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        tabSize = 2
        text = initialText
    }
    private val previewArea = JBTextArea().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        isEditable = false
        tabSize = 2
    }
    private val statusLabel = JBLabel(" ")

    private val tableModel = FieldTableModel { refreshPreview() }
    private val fieldsTable = JBTable(tableModel).apply {
        setShowGrid(true)
        columnModel.getColumn(0).preferredWidth = 48
        columnModel.getColumn(1).preferredWidth = 130
        columnModel.getColumn(2).preferredWidth = 130
        columnModel.getColumn(3).preferredWidth = 170
        columnModel.getColumn(4).preferredWidth = 60
        columnModel.getColumn(5).preferredWidth = 220
    }

    /** 字段表搜索 / 列头排序 / 结果计数：多类多字段时不用在长表里上下翻 */
    private val fieldSearchSupport = TableSearchSupport(fieldsTable, tableModel)

    private val options = JsonOptionsPanel(onRender = { refreshPreview() }, onInfer = { rebuild() })

    private var result: InferenceResult? = null
    private var files: List<GeneratedJavaFile> = emptyList()
    private var warnings: List<String> = emptyList()
    private var fatalError: String? = null

    private val debounce = Timer(220) { rebuild() }.apply { isRepeats = false }

    init {
        title = "JSON → Java 对象"
        val settings = initialSettings.copyOf().apply { if (packageName.isBlank()) packageName = initialPackage }
        options.resetFrom(settings)
        options.setDirectory(directory.virtualFile.presentableUrl)
        jsonArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = schedule()
            override fun removeUpdate(e: DocumentEvent) = schedule()
            override fun changedUpdate(e: DocumentEvent) = schedule()
            private fun schedule() {
                debounce.restart()
            }
        })
        rebuild()
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout()).apply {
        preferredSize = Dimension(1180, 720)
        add(options.component, BorderLayout.NORTH)
        val jsonPanel = JPanel(BorderLayout()).apply {
            add(JBLabel("JSON 样本（可一次贴多份 / 可直接带日志前缀）"), BorderLayout.NORTH)
            add(JBScrollPane(jsonArea), BorderLayout.CENTER)
            add(
                JPanel().apply {
                    add(JButton("从剪贴板粘贴").apply {
                        addActionListener { pasteFromClipboard() }
                    })
                    add(JButton("清空").apply {
                        addActionListener { jsonArea.text = "" }
                    })
                },
                BorderLayout.SOUTH
            )
        }
        val fieldsPanel = JPanel(BorderLayout()).apply {
            val header = JPanel(BorderLayout(6, 0))
            header.add(JBLabel("字段（可编辑 · 点列头排序）"), BorderLayout.WEST)
            header.add(fieldSearchSupport.field, BorderLayout.CENTER)
            header.add(fieldSearchSupport.countLabel, BorderLayout.EAST)
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(fieldsTable), BorderLayout.CENTER)
        }
        val previewPanel = JPanel(BorderLayout()).apply {
            add(JBLabel("代码预览（最终写入的内容）"), BorderLayout.NORTH)
            add(JBScrollPane(previewArea), BorderLayout.CENTER)
        }
        val inner = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, fieldsPanel, previewPanel).apply {
            resizeWeight = 0.46
        }
        val outer = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, jsonPanel, inner).apply {
            resizeWeight = 0.30
        }
        add(outer, BorderLayout.CENTER)
        add(statusLabel, BorderLayout.SOUTH)
    }

    override fun doValidate(): ValidationInfo? {
        fatalError?.let { return ValidationInfo(it, jsonArea) }
        val settings = currentSettings()
        if (!JsonNames.isValidClassName(settings.className)) {
            return ValidationInfo("类名不合法（需为合法 Java 标识符）", options.component)
        }
        if (settings.packageName.isNotBlank() &&
            !Regex("^[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*$").matches(settings.packageName)
        ) {
            return ValidationInfo("包名不合法", options.component)
        }
        if (result?.root?.fields?.none { it.enabled } != false) {
            return ValidationInfo("没有勾选任何字段", fieldsTable)
        }
        return null
    }

    fun plan(): JsonGenerationPlan = JsonGenerationPlan(
        currentSettings(), directory, files, warnings, result?.sampleCount ?: 0
    )

    // ------------------------------------------------------------------ 实时刷新

    private fun currentSettings(): JsonSettings = initialSettings.copyOf().also { options.applyTo(it) }

    private fun rebuild() {
        val settings = currentSettings()
        val samples = JsonParser.parseSamples(jsonArea.text)
        val inferred = if (samples.isValid) {
            JsonInferrer(settings).infer(samples.values, settings.className.ifBlank { "Response" })
        } else {
            null
        }
        result = inferred
        fatalError = if (inferred == null) samples.errors.firstOrNull() ?: "JSON 解析失败" else null
        warnings = samples.errors.filter { inferred != null } + (inferred?.warnings ?: emptyList())
        tableModel.refresh(inferred)
        fieldSearchSupport.refreshCount()
        refreshPreview()
    }

    private fun refreshPreview() {
        val inferred = result ?: run {
            files = emptyList()
            previewArea.text = ""
            updateStatus()
            return
        }
        val settings = currentSettings()
        files = try {
            JsonRenderer(settings).render(
                inferred, settings.packageName,
                settings.classTemplate.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            warnings = warnings + "渲染失败：${e.message}"
            emptyList()
        }
        previewArea.text = files.joinToString("\n\n") { "// ===== ${it.fileName} =====\n${it.text}" }
        previewArea.caretPosition = 0
        updateStatus()
    }

    private fun updateStatus() {
        val inferred = result
        val parts = mutableListOf<String>()
        if (inferred != null) {
            parts += "${inferred.sampleCount} 份样本 · ${inferred.classes.size} 个类 · " +
                "${inferred.root?.fields?.count { it.enabled } ?: 0} 个字段"
        }
        if (warnings.isNotEmpty()) parts += "${warnings.size} 条提示（见字段说明）"
        fatalError?.let { parts += it }
        statusLabel.text = parts.joinToString("　|　").ifBlank { " " }
    }

    private fun pasteFromClipboard() {
        val transferable = com.intellij.openapi.ide.CopyPasteManager.getInstance().contents ?: return
        val text = runCatching {
            transferable.getTransferData(DataFlavor.stringFlavor) as? String
        }.getOrNull() ?: return
        jsonArea.text = text
        rebuild()
    }

    // ------------------------------------------------------------------ 字段表

    private class FieldTableModel(private val onEdit: () -> Unit) : AbstractTableModel() {

        private val headers = arrayOf("生成", "JSON 字段", "字段名", "Java 类型", "出现", "说明")
        private val rows = mutableListOf<Pair<ClassModel, FieldModel>>()
        private var result: InferenceResult? = null

        fun refresh(value: InferenceResult?) {
            result = value
            rows.clear()
            value?.classes?.forEach { bean -> bean.fields.forEach { rows += bean to it } }
            fireTableDataChanged()
        }

        override fun getRowCount(): Int = rows.size
        override fun getColumnCount(): Int = headers.size
        override fun getColumnName(column: Int): String = headers[column]
        override fun getColumnClass(column: Int): Class<*> =
            if (column == 0) Boolean::class.javaObjectType else String::class.java

        override fun isCellEditable(row: Int, column: Int): Boolean = column in setOf(0, 2, 3)

        override fun getValueAt(row: Int, column: Int): Any {
            val (bean, field) = rows[row]
            return when (column) {
                0 -> field.enabled
                1 -> if (bean.depth == 0) field.jsonKey else "${bean.name}.${field.jsonKey}"
                2 -> field.name
                3 -> field.typeText()
                4 -> "${field.occurrences}/${field.sampleCount}"
                5 -> field.notes.joinToString("；")
                else -> ""
            }
        }

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            val field = rows[row].second
            when (column) {
                0 -> field.enabled = value as? Boolean ?: return
                2 -> field.name = value?.toString()?.trim().orEmpty().ifBlank { return }
                3 -> field.type = JsonType.parse(value?.toString().orEmpty())
            }
            fireTableRowsUpdated(row, row)
            onEdit()
        }
    }
}
